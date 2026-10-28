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
 * (task switcher) on the top screen instead (see [BackGestureMachine]). Holding Back and pressing a
 * shoulder button (see [ShoulderButton]) or a D-pad direction (see [DpadDirection]) runs whatever is
 * configured for it instead. The AYN button is left alone so its quick-settings panel and
 * hold-to-sleep keep working.
 */
class SwitcherService : AccessibilityService(), GestureActions {

    private lateinit var overlay: SwitcherOverlay
    private lateinit var source: AccessibilityAppSource
    private lateinit var padHelper: PadHelper
    private lateinit var dpadOverlay: DpadOverlay
    private lateinit var levels: LevelControl
    private lateinit var controllerFocus: ControllerFocus
    private lateinit var focusIndicator: FocusIndicatorOverlay
    private val shoulderKeys = PhysicalKeyTracker()
    private val pendingFocusTargets = mutableMapOf<Int, ComboButton>()
    private var pendingDpadFocus: DpadDirection? = null
    private var pendingDpadReleased = false
    private val prefs by lazy { Prefs(this) }
    private val handler = Handler(Looper.getMainLooper())

    private val gestureClock = object : GestureClock {
        override fun uptimeMillis() = SystemClock.uptimeMillis()
    }
    private val gestureScheduler = object : GestureScheduler {
        override fun postDelayed(runnable: Runnable, delayMs: Long) {
            handler.postDelayed(runnable, delayMs)
        }
        override fun removeCallbacks(runnable: Runnable) = handler.removeCallbacks(runnable)
        override fun hasCallbacks(runnable: Runnable) = handler.hasCallbacks(runnable)
    }
    private val backGesture by lazy {
        BackGestureMachine(prefs, gestureClock, gestureScheduler, this, ::dlog)
    }

    override fun onServiceConnected() {
        isConnected = true
        previewFocusIndicator = { top, position, ms -> handler.post { focusIndicator.show(top, position, ms) } }
        PService.probeAsync()
        source = AccessibilityAppSource(this)
        overlay = SwitcherOverlay(
            this,
            source,
            prefs::switcherShowsAllApps,
            ::closeApp,
            ::closeAll,
            ::screenshotFromSwitcher,
            ::beforeLaunch,
        )
        levels = LevelControl(this) { bottomDisplay()?.displayId }
        focusIndicator = FocusIndicatorOverlay(this)
        controllerFocus = ControllerFocus(this, onFailed = focusIndicator::dismiss)
        padHelper = PadHelper(this, ::handleDpad, ::handleDpadRelease)
        dpadOverlay = DpadOverlay(this, ::handleDpad, ::handleDpadRelease)
        prefs.registerListener(prefsListener)
        updatePadHelper()
    }

    // The helper hides the pad from Android while it runs, so only run it while the D-pad has a job.
    private val prefsListener = { updatePadHelper() }

    private fun updatePadHelper() {
        if (prefs.hasDpadAction()) padHelper.start() else padHelper.stop()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            // Without the root helper the D-pad can only be caught by taking window focus; see DpadOverlay.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !padHelper.active && prefs.hasDpadAction()) {
                dpadOverlay.attach()
            }
            if (event.action == KeyEvent.ACTION_UP) {
                val overlayWasAttached = dpadOverlay.attached
                dpadOverlay.detach()
                // Once the overlay is gone a still held D-pad is released to the app, unseen; Back's
                // release is the last moment left to commit an armed controller focus.
                if (overlayWasAttached) {
                    pendingDpadReleased = true
                    commitDpadFocus()
                }
            }
            return backGesture.handleBack(event)
        }
        if (SHOULDER_BUTTONS.any { it.keyCode == event.keyCode }) return handleShoulder(event)
        return false
    }

    /** Back + D-pad, from [PadHelper] or [DpadOverlay]; both only report presses made while Back is held. */
    private fun handleDpad(direction: DpadDirection) {
        if (!backGesture.backDown) return
        val action = prefs.shoulderAction(direction)
        if (action.isEmpty()) return
        backGesture.markComboFired()
        pendingDpadFocus = null
        // Same firmware race as for the shoulder buttons (see handleShoulder): arm on press,
        // commit on release.
        if (action == Prefs.SHOULDER_CONTROLLER_FOCUS) {
            pendingDpadFocus = direction
            pendingDpadReleased = false
            showFocusIndicator(direction)
        } else {
            runComboAction(direction, action)
        }
    }

    private fun handleDpadRelease(horizontal: Boolean) {
        val pending = pendingDpadFocus ?: return
        if ((pending == DpadDirection.LEFT || pending == DpadDirection.RIGHT) != horizontal) return
        pendingDpadReleased = true
        commitDpadFocus()
    }

    /** Without the root helper, the overlay holds window focus while Back is held and would swallow
     *  the key event that commits the route, so the commit also waits until it is gone. */
    private fun commitDpadFocus() {
        val pending = pendingDpadFocus ?: return
        if (!pendingDpadReleased || dpadOverlay.attached) return
        pendingDpadFocus = null
        runControllerFocus(pending)
    }

    /** Holding Back and pressing a shoulder button runs that button's configured action
     *  instead of whatever Back alone would have done, and swallows the shoulder button's
     *  own event so the focused app/game never sees a stray press. Buttons with nothing
     *  configured are left alone, even while Back is held. */
    private fun handleShoulder(event: KeyEvent): Boolean {
        val button = SHOULDER_BUTTONS.first { it.keyCode == event.keyCode }
        val action = prefs.shoulderAction(button)
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Outside an active Back combo the controller belongs entirely to the foreground
                // app. Do not even track the key in that case, so its UP remains untouched too.
                if (!backGesture.backDown || action.isEmpty()) return false
                if (shoulderKeys.down(event.keyCode, event.deviceId) && event.repeatCount == 0) {
                    backGesture.markComboFired()
                    // Changing the controller route while its trigger is physically held is racy
                    // in AYN's firmware: the setting changes, but the route can stay on the old
                    // display. Arm focus here and commit after both mirrored devices report UP.
                    if (action == Prefs.SHOULDER_CONTROLLER_FOCUS) {
                        pendingFocusTargets[event.keyCode] = button
                        showFocusIndicator(button)
                    } else {
                        runComboAction(button, action)
                    }
                }
                true
            }
            // Consume releases only for shoulder presses that began as a Back combo. The Thor can
            // report one physical controller through two device ids, so both releases are tracked.
            KeyEvent.ACTION_UP -> {
                val tracked = shoulderKeys.up(event.keyCode, event.deviceId)
                if (tracked && !shoulderKeys.isDown(event.keyCode)) {
                    pendingFocusTargets.remove(event.keyCode)?.let(::runControllerFocus)
                }
                tracked
            }
            else -> false
        }
    }

    private fun showFocusIndicator(button: ComboButton) {
        val position = prefs.focusPosition(button).takeIf { prefs.focusIndicator(button) }
        focusIndicator.show(
            controllerFocus.targetIsTop(prefs.focusTarget(button)),
            position,
            prefs.focusDuration(button),
        ) { openIndicatorSettings(button) }
    }

    private fun openIndicatorSettings(button: ComboButton) {
        // AYN's assistant permanently covers the bottom display, so the settings would open
        // invisibly there. CLEAR_TASK also pulls an already running settings task up from it.
        val intent = Intent(this, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_INDICATOR_BUTTON, button.prefKey)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
        runCatching { startActivity(intent, options) }
    }

    private fun runControllerFocus(button: ComboButton) {
        showFocusIndicator(button)
        controllerFocus.run(prefs.focusTarget(button))
    }

    private fun runComboAction(button: ComboButton, action: String) {
        when (action) {
            Prefs.SHOULDER_FLICK_LEFT -> navigationFlick(left = true)
            Prefs.SHOULDER_FLICK_RIGHT -> navigationFlick(left = false)
            Prefs.SHOULDER_VOLUME_UP, Prefs.SHOULDER_VOLUME_DOWN,
            Prefs.SHOULDER_BRIGHTNESS_UP, Prefs.SHOULDER_BRIGHTNESS_DOWN -> levels.run(action, prefs.levelTarget(button))
            Prefs.SHOULDER_CONTROLLER_FOCUS -> runControllerFocus(button)
            Prefs.SHOULDER_HOME -> goHome(shoulderDisplayId(button))
            Prefs.SHOULDER_RECENTS -> openTopRecents()
            Prefs.SHOULDER_SCREENSHOT -> screenshot(prefs.levelTarget(button))
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
                dlog("COMBO ${button.name} + Back -> launching $action on display $displayId")
                launchOn(action, displayId)
            }
        }
    }

    /**
     * Both displays use Android's native screenshot flow. AYN's SystemUI can capture the bottom
     * display but always renders its preview and action strip on the top display. The accessibility
     * capture remains as a compatibility fallback when the firmware's pservice is unavailable.
     */
    private fun screenshot(target: String) {
        if (target == Prefs.DISPLAY_TOP) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            return
        }
        val displayId = bottomDisplay()?.displayId ?: return toastUnavailable()
        if (target != Prefs.DISPLAY_BOTTOM) {
            // AYN's SystemUI interprets this encoded id as default display + the referenced
            // secondary display, stacked vertically with the narrower image centered.
            nativeSystemScreenshot(displayId shl COMBINED_DISPLAY_SHIFT) {
                // Preserve the old two-file behavior if the firmware bridge is unavailable.
                performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                handler.postDelayed({ saveScreenshot(displayId, "bottom", retry = true) }, MIN_CAPTURE_GAP_MS)
            }
        } else {
            nativeSystemScreenshot(displayId) { saveScreenshot(displayId, "bottom", retry = true) }
        }
    }

    private fun screenshotFromSwitcher(displayId: Int) {
        overlay.dismiss()
        handler.postDelayed({
            nativeSystemScreenshot(displayId) { saveScreenshot(displayId, "bottom", retry = true) }
        }, SWITCHER_SCREENSHOT_DELAY_MS)
    }

    private fun nativeSystemScreenshot(displayId: Int, fallback: () -> Unit) {
        Thread {
            val result = PService.runEntryPoint(this, "SystemScreenshot", displayId.toString())
            if (result != "OK") handler.post(fallback)
        }.apply { isDaemon = true }.start()
        Thread {
            val deadline = SystemClock.uptimeMillis() + SYSTEM_SCREENSHOT_CONNECT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                if (SystemScreenshot.connect()) return@Thread
                SystemClock.sleep(25L)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun saveScreenshot(displayId: Int, suffix: String, retry: Boolean) {
        takeScreenshot(displayId, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                // Encoding a PNG is slow; keep it off the main thread, and release the buffer after.
                Thread {
                    val saved = bitmap != null && ScreenshotSaver.save(this@SwitcherService, bitmap, suffix)
                    buffer.close()
                    handler.post {
                        if (saved) Toast.makeText(this@SwitcherService, R.string.screenshot_saved, Toast.LENGTH_SHORT).show()
                        else toastUnavailable()
                    }
                }.start()
            }

            override fun onFailure(errorCode: Int) {
                // Android refuses two screenshots from one service within about a second.
                if (retry && errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                    handler.postDelayed({ saveScreenshot(displayId, suffix, retry = false) }, MIN_CAPTURE_GAP_MS)
                } else {
                    Log.d(TAG, "screenshot failed: $errorCode")
                    toastUnavailable()
                }
            }
        })
    }

    private fun toastUnavailable() =
        Toast.makeText(this, R.string.shoulder_action_unavailable, Toast.LENGTH_SHORT).show()

    private fun shoulderDisplayId(button: ComboButton): Int =
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

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        isConnected = false
        previewFocusIndicator = null
        shoulderKeys.clear()
        pendingFocusTargets.clear()
        pendingDpadFocus = null
        stopDpad()
        handler.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) overlay.dismiss()
        if (::focusIndicator.isInitialized) focusIndicator.dismiss()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isConnected = false
        previewFocusIndicator = null
        shoulderKeys.clear()
        pendingFocusTargets.clear()
        pendingDpadFocus = null
        stopDpad()
        return super.onUnbind(intent)
    }

    /** Hands the pad back to Android; the helper must not outlive the service it reports to. */
    private fun stopDpad() {
        if (!::padHelper.isInitialized) return
        prefs.unregisterListener(prefsListener)
        padHelper.stop()
        dpadOverlay.detach()
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

    /** A normal app can only kill background processes, so leave the app first. With AYN's root
     *  service reachable ([PService]) the task is also removed from Recents and the app force-stopped;
     *  if that fails, the unprivileged kill below still runs. */
    private fun killOnly(packages: List<String>, displays: Set<Int>) {
        val activityManager = getSystemService(ActivityManager::class.java)
        packages.forEach { pkg ->
            displays.forEach { d -> if (source.topApp(d) == pkg) goHome(d, dismissSwitcher = false) }
        }
        val rootable = if (PService.cachedAvailable()) packages.filter(Shell::isPkg) else emptyList()
        handler.postDelayed({
            if (rootable.isEmpty()) {
                packages.forEach { activityManager.killBackgroundProcesses(it) }
                return@postDelayed
            }
            Thread {
                val removed = PService.runEntryPoint(this, "TaskRemover", rootable.joinToString(","))
                val stopped = rootable.map { PService.forceStop(it) }
                if (removed?.startsWith("OK") != true || !stopped.all { it }) {
                    handler.post { packages.forEach { activityManager.killBackgroundProcesses(it) } }
                }
            }.start()
        }, 500)
    }

    /** Moves the front app of each screen to the other one by relaunching it there. */
    override fun swapScreens() {
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
    override fun openTopRecents() {
        overlay.dismiss()
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    override fun onSingleTap() {
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

    override fun showSwitcher() {
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

        /** Lets the settings screen preview the focus pictogram, which only the service may draw. */
        @Volatile
        var previewFocusIndicator: ((top: Boolean, position: String?, visibleMs: Long) -> Unit)? = null
            private set

        const val TAG = "BackButtonHero"
        const val CAPTURE_DELAY_MS = 800L
        const val MIN_CAPTURE_GAP_MS = 1100L
        const val SWITCHER_SCREENSHOT_DELAY_MS = 100L
        const val SYSTEM_SCREENSHOT_CONNECT_MS = 2_000L
        const val COMBINED_DISPLAY_SHIFT = 12
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
