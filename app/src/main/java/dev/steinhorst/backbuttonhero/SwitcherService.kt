package dev.steinhorst.backbuttonhero

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.app.ActivityOptions
import android.graphics.Bitmap
import android.graphics.Path
import android.os.SystemClock
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Double-tap Back opens the switcher on the bottom screen; a single Back goes to the bottom screen's
 * launcher (app drawer) once the double-tap window has passed. Holding Back for a second swaps the apps
 * on the two screens - unless that hold follows a tap, in which case it opens the system's Recents
 * (task switcher) on the top screen instead (see [tapHoldRunnable]). Holding Back and pressing a
 * shoulder button (see [ShoulderButton]) launches whatever app is configured for that button instead,
 * on whichever screen is configured for it. The AYN button is left alone so its quick-settings panel
 * and hold-to-sleep keep working.
 */
class SwitcherService : AccessibilityService() {

    private lateinit var overlay: SwitcherOverlay
    private lateinit var source: AccessibilityAppSource
    private val prefs by lazy { Prefs(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var holdFired = false
    private var tapHoldFired = false
    private var comboFired = false
    private var backDown = false
    private var firstDownAt = 0L
    // Captured once at this press's DOWN, not recomputed at UP: singleTap may have already
    // fired on its own by the time a long-held second press is released, which would make a
    // hasCallbacks() check at UP time wrongly say "no tap was pending".
    private var pressFollowsTap = false
    private val holdRunnable = Runnable { holdFired = true; dlog("RUNNABLE holdRunnable fired -> swapScreens()"); swapScreens() }
    private val tapHoldRunnable = Runnable {
        tapHoldFired = true
        dlog("RUNNABLE tapHoldRunnable fired -> openTopRecents()")
        openTopRecents()
    }
    private val singleTap = Runnable { dlog("RUNNABLE singleTap fired -> onSingleTap()"); onSingleTap() }
    private val detector = DoubleTapDetector({ prefs.doubleTapMs }) {
        dlog("DETECTOR double-tap recognized -> showSwitcher()")
        handler.removeCallbacks(singleTap)
        showSwitcher()
    }

    override fun onServiceConnected() {
        isConnected = true
        source = AccessibilityAppSource(this)
        overlay = SwitcherOverlay(this, source, ::closeApp, ::closeAll, ::beforeLaunch)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return handleBack(event)
        if (SHOULDER_BUTTONS.any { it.keyCode == event.keyCode }) return handleShoulder(event)
        return false
    }

    /** Holding Back and pressing a shoulder button runs that button's configured action
     *  instead of whatever Back alone would have done, and swallows the shoulder button's
     *  own event so the focused app/game never sees a stray press. Buttons with nothing
     *  configured are left alone, even while Back is held. */
    private fun handleShoulder(event: KeyEvent): Boolean {
        if (!backDown) return false
        val button = SHOULDER_BUTTONS.first { it.keyCode == event.keyCode }
        val action = prefs.shoulderAction(button)
        if (action.isEmpty()) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            comboFired = true
            handler.removeCallbacks(holdRunnable)
            handler.removeCallbacks(tapHoldRunnable)
            when (action) {
                Prefs.SHOULDER_FLICK_LEFT -> navigationFlick(left = true)
                Prefs.SHOULDER_FLICK_RIGHT -> navigationFlick(left = false)
                Prefs.SHOULDER_HOME -> goHome(shoulderDisplayId(button))
                Prefs.SHOULDER_RECENTS -> openTopRecents()
                Prefs.SHOULDER_SCREENSHOT -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                Prefs.SHOULDER_QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                Prefs.SHOULDER_NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                Prefs.SHOULDER_INTERNET -> openSystemSettings(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, Settings.ACTION_WIRELESS_SETTINGS)
                Prefs.SHOULDER_BLUETOOTH -> openSystemSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
                Prefs.SHOULDER_NIGHT_LIGHT -> openSystemSettings(Settings.ACTION_NIGHT_DISPLAY_SETTINGS, Settings.ACTION_DISPLAY_SETTINGS)
                Prefs.SHOULDER_EXTRA_DIM -> openSystemSettings(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ACTION_EXTRA_DIM_SETTINGS else Settings.ACTION_DISPLAY_SETTINGS,
                    Settings.ACTION_DISPLAY_SETTINGS,
                )
                Prefs.SHOULDER_DISPLAY_SETTINGS -> openSystemSettings(Settings.ACTION_DISPLAY_SETTINGS)
                Prefs.SHOULDER_SOUND_SETTINGS -> openSystemSettings(Settings.ACTION_SOUND_SETTINGS)
                Prefs.SHOULDER_LOCATION_SETTINGS -> openSystemSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                Prefs.SHOULDER_CAST_SETTINGS -> openSystemSettings(Settings.ACTION_CAST_SETTINGS)
                Prefs.SHOULDER_DND_SETTINGS -> openSystemSettings(ACTION_ZEN_MODE_SETTINGS, Settings.ACTION_SOUND_SETTINGS)
                Prefs.SHOULDER_BATTERY_SAVER -> openSystemSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                else -> {
                    val displayId = shoulderDisplayId(button)
                    dlog("SHOULDER ${button.name} + Back -> launching $action on display $displayId")
                    launchOn(action, displayId)
                }
            }
        }
        return true
    }

    private fun shoulderDisplayId(button: ShoulderButton): Int =
        if (prefs.shoulderDisplay(button) == Prefs.DISPLAY_TOP) Display.DEFAULT_DISPLAY
        else bottomDisplay()?.displayId ?: Display.DEFAULT_DISPLAY

    /** Opens the narrowest system-owned panel available, with a safe fallback for OEM builds. */
    private fun openSystemSettings(action: String, fallback: String = Settings.ACTION_SETTINGS, data: Uri? = null) {
        val primary = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { this.data = data }
        val fallbackIntent = Intent(fallback).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(primary) }
            .recoverCatching { startActivity(fallbackIntent) }
            .onFailure { Toast.makeText(this, R.string.shoulder_action_unavailable, Toast.LENGTH_SHORT).show() }
    }

    /** Replays the fast bottom-edge flick measured from the Thor's navigation pill. */
    private fun navigationFlick(left: Boolean) {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val size = android.graphics.Point().also { display.getRealSize(it) }
        val startX = size.x * 0.495f
        // Quickstep moves the selected app opposite to the finger's travel direction.
        val endX = size.x * if (left) 0.781f else 0.182f
        val y = size.y - 2f
        val path = Path().apply {
            moveTo(startX, y)
            lineTo(endX, y)
        }
        val gesture = GestureDescription.Builder()
            .setDisplayId(Display.DEFAULT_DISPLAY)
            .addStroke(GestureDescription.StrokeDescription(path, 0, NAV_FLICK_DURATION_MS))
            .build()
        val accepted = dispatchGesture(gesture, null, handler)
        dlog("navigationFlick left=$left accepted=$accepted ${startX.toInt()}->${endX.toInt()} y=${y.toInt()}")
    }

    private fun handleBack(event: KeyEvent): Boolean {
        when {
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> {
                val now = SystemClock.uptimeMillis()
                val sinceFirstDown = if (firstDownAt == 0L) -1 else now - firstDownAt
                backDown = true
                holdFired = false
                tapHoldFired = false
                comboFired = false
                // A pending singleTap callback means an earlier release already started the
                // double-tap window, so this press is a second one following a tap. Cancel it
                // here, at DOWN - not just at the eventual UP - otherwise it keeps running on
                // its own schedule and fires mid-hold regardless of what this press becomes.
                pressFollowsTap = handler.hasCallbacks(singleTap)
                if (pressFollowsTap) handler.removeCallbacks(singleTap)
                val scheduled = when {
                    pressFollowsTap && prefs.tapHoldEnabled -> { handler.postDelayed(tapHoldRunnable, prefs.tapHoldMs); "tapHoldRunnable in ${prefs.tapHoldMs}ms" }
                    prefs.holdEnabled -> { handler.postDelayed(holdRunnable, prefs.holdMs); "holdRunnable in ${prefs.holdMs}ms" }
                    else -> "nothing (hold disabled)"
                }
                dlog("DOWN   t=$now (+${sinceFirstDown}ms since previous DOWN)  afterTap=$pressFollowsTap tapHoldEnabled=${prefs.tapHoldEnabled} -> scheduled $scheduled")
                firstDownAt = now
            }
            event.action == KeyEvent.ACTION_UP -> {
                val now = SystemClock.uptimeMillis()
                val heldFor = now - event.downTime
                backDown = false
                handler.removeCallbacks(holdRunnable)
                handler.removeCallbacks(tapHoldRunnable)
                if (holdFired || tapHoldFired || comboFired) {
                    dlog("UP     t=$now held=${heldFor}ms  holdFired=$holdFired tapHoldFired=$tapHoldFired comboFired=$comboFired -> consumed, no tap logic")
                    return true
                }
                if (!prefs.doubleTapEnabled) {
                    dlog("UP     t=$now held=${heldFor}ms  doubleTapEnabled=false -> immediate onSingleTap()")
                    onSingleTap(); return true
                }
                handler.removeCallbacks(singleTap)
                detector.onPress()
                val delay = singleTapDelay()
                // If this release did not complete a double-tap, the first tap is still pending.
                // Double-tap itself still resolves purely on prefs.doubleTapMs (via detector,
                // above) - this is only how long we wait before concluding no second press is
                // coming. With tap-hold on, that's stretched past doubleTapMs: pressing and
                // holding a second time takes a beat longer than tapping it, and doubleTapMs
                // alone is often gone before that second press even lands, so the single-tap
                // action fires first and the hold is read as a lone hold instead of a tap-hold.
                if (!pressFollowsTap) handler.postDelayed(singleTap, delay)
                dlog("UP     t=$now held=${heldFor}ms  pressFollowsTap=$pressFollowsTap -> " +
                    if (pressFollowsTap) "detector.onPress() only (this was the 2nd press of a tap combo)"
                    else "scheduled singleTap in ${delay}ms")
            }
        }
        return true
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        isConnected = false
        handler.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) overlay.dismiss()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isConnected = false
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        source.record(Display.DEFAULT_DISPLAY)
        bottomDisplay()?.let { source.record(it.displayId) }
        handler.removeCallbacks(captureRunnable)
        handler.postDelayed(captureRunnable, CAPTURE_DELAY_MS)
    }

    private val captureRunnable = Runnable { captureFrontApp() }
    private var lastCapture = 0L

    /** Stores a preview of the app in front on the bottom screen; [then] runs whether or not it worked. */
    private fun captureFrontApp(then: (() -> Unit)? = null) {
        val display = bottomDisplay()
        val pkg = display?.let { source.topApp(it.displayId) }
        val now = SystemClock.uptimeMillis()
        if (display == null || pkg == null || overlay.isShowing || source.isCovered(display.displayId) || now - lastCapture < MIN_CAPTURE_GAP_MS) {
            then?.invoke(); return
        }
        lastCapture = now
        takeScreenshot(display.displayId, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                // Drop the shot if the app changed or our overlay appeared while it was being taken.
                val stillFront = !overlay.isShowing && source.topApp(display.displayId) == pkg
                if (bitmap != null && stillFront) {
                    val scale = THUMB_WIDTH.toFloat() / bitmap.width
                    val small = Bitmap.createScaledBitmap(
                        bitmap.copy(Bitmap.Config.ARGB_8888, false), THUMB_WIDTH, (bitmap.height * scale).toInt(), true)
                    source.saveThumbnail(pkg, small)
                }
                buffer.close()
                then?.invoke()
            }

            override fun onFailure(errorCode: Int) {
                Log.d(TAG, "screenshot failed: $errorCode")
                then?.invoke()
            }
        })
    }

    /** Swiping a card up closes the app on the bottom screen. */
    private fun closeApp(pkg: String) {
        val bottom = bottomDisplay()?.displayId ?: return
        killOnly(listOf(pkg), setOf(bottom))
    }

    /** Close all: every app task on the bottom screen, or on both screens when [bothScreens] is set. */
    private fun closeAll(packages: List<String>, bothScreens: Boolean) {
        val displays = if (bothScreens) getSystemService(DisplayManager::class.java).displays.map { it.displayId }.toSet()
        else setOfNotNull(bottomDisplay()?.displayId)
        killOnly(packages, displays)
    }

    /** Called just before an app is launched from the switcher. Deals with windows that would hide it. */
    private fun beforeLaunch(displayId: Int) {
        if (!source.isCovered(displayId)) return
        Toast.makeText(this, getString(R.string.covered_hint), Toast.LENGTH_LONG).show()
    }

    /** A normal app can only kill background processes, so leave the app first. */
    private fun killOnly(packages: List<String>, displays: Set<Int>) {
        val activityManager = getSystemService(ActivityManager::class.java)
        packages.forEach { pkg ->
            displays.forEach { d -> if (source.topApp(d) == pkg) goHome(d, dismissSwitcher = false) }
        }
        handler.postDelayed({ packages.forEach { activityManager.killBackgroundProcesses(it) } }, 500)
    }

    /** Moves the front app of each screen to the other one by relaunching it there. */
    private fun swapScreens() {
        val bottom = bottomDisplay()?.displayId ?: return
        val top = Display.DEFAULT_DISPLAY
        overlay.dismiss()
        val topApp = source.topApp(top)
        val bottomApp = source.topApp(bottom)
        topApp?.let { launchOn(it, bottom) }
        bottomApp?.let { launchOn(it, top) }
    }

    private fun launchOn(pkg: String, displayId: Int) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
    }

    /**
     * Opens the system's Recents/Overview (task switcher) on the top screen - the same screen
     * a dedicated Overview button or the recents gesture would show. GLOBAL_ACTION_RECENTS
     * always targets the default display, which is exactly the top screen in this setup.
     */
    private fun openTopRecents() {
        overlay.dismiss()
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    /** How long to wait after a tap before firing it as a plain single tap. */
    private fun singleTapDelay(): Long =
        if (prefs.tapHoldEnabled) maxOf(prefs.doubleTapMs, TAP_HOLD_GRACE_MS) else prefs.doubleTapMs

    private fun onSingleTap() {
        if (overlay.isShowing) { dlog("ACTION onSingleTap: overlay was showing -> dismiss only"); overlay.dismiss(); return }
        dlog("ACTION onSingleTap: singleAction=${prefs.singleAction}")
        when (prefs.singleAction) {
            Prefs.SINGLE_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            else -> goHome(component = prefs.singleLauncher)
        }
    }

    private fun goHome(displayId: Int? = null, dismissSwitcher: Boolean = true, component: ComponentName? = null) {
        val target = displayId ?: bottomDisplay()?.displayId ?: return
        if (dismissSwitcher) overlay.dismiss()
        val category = if (target == Display.DEFAULT_DISPLAY) Intent.CATEGORY_HOME else Intent.CATEGORY_SECONDARY_HOME
        val home = Intent(Intent.ACTION_MAIN).addCategory(category).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        component?.let { home.component = it }
        startActivity(home, ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle())
    }

    private fun showSwitcher() {
        val display = bottomDisplay() ?: return
        if (overlay.isShowing) { overlay.dismiss(); return }
        captureFrontApp {
            runCatching { overlay.toggle(display) }.onFailure { Log.e(TAG, "toggle failed", it) }
        }
    }

    /** The AYN Thor's second screen is the non-default internal display (id 4, "Screen-2" here). */
    private fun bottomDisplay(): Display? =
        getSystemService(DisplayManager::class.java).displays
            .filter { it.displayId != Display.DEFAULT_DISPLAY }
            .maxByOrNull { it.displayId }

    companion object {
        @Volatile
        var isConnected = false
            private set

        const val TAG = "BackButtonHero"
        const val CAPTURE_DELAY_MS = 800L
        const val MIN_CAPTURE_GAP_MS = 1100L
        const val THUMB_WIDTH = 480
        /** Minimum time to wait for a second, held-down press before firing a plain single tap. */
        const val TAP_HOLD_GRACE_MS = 600L
        const val NAV_FLICK_DURATION_MS = 120L
        const val ACTION_EXTRA_DIM_SETTINGS = "android.settings.REDUCE_BRIGHT_COLORS_SETTINGS"
        const val ACTION_ZEN_MODE_SETTINGS = "android.settings.ZEN_MODE_SETTINGS"
        /** Flip on to trace every key-event decision (DOWN/UP, what got scheduled, what fired)
         *  in logcat under [TAG]. Off by default - this is noisy on every Back press. */
        const val DEBUG_GESTURES = false
        private val SHOULDER_BUTTONS = ShoulderButton.entries
    }

    private fun dlog(msg: String) {
        if (DEBUG_GESTURES) Log.d(TAG, msg)
    }
}
