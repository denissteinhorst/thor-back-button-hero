package dev.steinhorst.backbuttonhero

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.app.ActivityOptions
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import android.graphics.Path
import android.os.SystemClock
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
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
import android.view.accessibility.AccessibilityNodeInfo

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
    private val recentsThumbnails = RecentsThumbnailCache()
    private val requestedThumbnails = mutableMapOf<RecentsThumbnailCache.Key, Int>()
    private var thumbnailQueryRunning = false
    @Volatile private var bottomStateSession = 0
    private var bottomStateQueryRunning = false
    private var bottomSwitcherOpening = false
    private val bottomStateRunnable = Runnable { refreshBottomTaskStates() }
    private var recentsFocusRestore: String? = null
    private var topRecentsControllerActive = false
    private var topRecentsConfirmed = false
    private var topRecentsSession = 0
    private var topRecentsStateCheckRunning = false
    private var topRecentsStateCheckPending = false
    private var topRecentsReadyAttempts = 0
    private var topRecentsCardBounds: TopRecentsState.CardBounds? = null
    private var topRecentsInputReady = false
    private var topRecentsFocusPrimed = false
    private var topRecentsFocusWorkerRunning = false
    private var topRecentsFocusVersion = 0
    private var topRecentsExitRequested = false
    private val topNavigation = TopRecentsNavigation(
        { padHelper.navigateTop(it) },
        { queueTopRecentsDismissal() },
    )
    private var topRecentsPendingDismissals = 0
    private var topRecentsClosing = false
    private var topLaunchSession = 0
    private var topLaunchBaseline: List<RecentsTaskState>? = null
    private var topLaunchOverviewSeen = false
    private var topLaunchAttempts = 0
    private var topLaunchQueryRunning = false
    private var topLaunchSelectionPending = false
    private var topLaunchStartedAt = 0L
    private val topLaunchRunnable = Runnable { checkTopRecentsLaunch() }
    // A switcher-close Back press must consume its matching UP too. Otherwise that UP reaches the
    // gesture machine and can turn the same physical press into a tap, double-tap, or hold gesture.
    private var switcherBackDown = false
    private var switcherBackDeviceId = Int.MIN_VALUE
    private var topRecentsBackClosePending = false
    private val recentsConfirmKeys = mutableSetOf<Pair<Int, Int>>()
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
        if (::padHelper.isInitialized) stopDpad()
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
            ::launchFromRecents,
            ::onBottomSwitcherShown,
            ::onBottomSwitcherDismissed,
        )
        levels = LevelControl(this) { bottomDisplay()?.displayId }
        focusIndicator = FocusIndicatorOverlay(this)
        controllerFocus = ControllerFocus(this, onFailed = focusIndicator::dismiss)
        padHelper = PadHelper(
            this,
            ::handleDpad,
            ::handleDpadRelease,
            ::onPadHelperActiveChanged,
        )
        dpadOverlay = DpadOverlay(this, ::handleDpad, ::handleDpadRelease)
        prefs.registerListener(prefsListener)
        updatePadHelper()
    }

    // The helper hides the pad from Android while it runs, so keep it only when shortcuts or native
    // switcher navigation need it.
    private val prefsListener = { updatePadHelper() }

    private fun updatePadHelper() {
        // Controller-enabled switchers must be ready on their first frame; starting the helper only
        // after one opens would recreate the pad while the user is already trying to navigate.
        if (prefs.hasDpadAction() || prefs.switcherControllerEnabled) padHelper.start() else padHelper.stop()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val confirmKey = event.deviceId to event.keyCode
        if (confirmKey in recentsConfirmKeys) {
            if (event.action == KeyEvent.ACTION_UP) recentsConfirmKeys.remove(confirmKey)
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            // Explicitly injected keyboard Back belongs to Quickstep, including its UP.
            if (topRecentsExitRequested && event.deviceId == -1) return false
            if (switcherBackDown) {
                // Root-injected Back uses a virtual device id and must reach Quickstep; only the
                // physical press that requested the close belongs to this consumed key pair.
                if (event.deviceId != switcherBackDeviceId) return false
                if (event.action == KeyEvent.ACTION_UP) {
                    switcherBackDown = false
                    switcherBackDeviceId = Int.MIN_VALUE
                    val closeTop = topRecentsBackClosePending
                    topRecentsBackClosePending = false
                    if (closeTop && topRecentsControllerActive) closeTopRecents()
                }
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (overlay.isShowing && !backGesture.followsRecentTap()) {
                    switcherBackDown = true
                    switcherBackDeviceId = event.deviceId
                    overlay.dismiss()
                    return true
                }
                if (topRecentsControllerActive) {
                    switcherBackDown = true
                    switcherBackDeviceId = event.deviceId
                    // Inject only after physical UP. A second Back pair while the controller's
                    // Back is still down can be acknowledged without dismissing native Overview.
                    topRecentsBackClosePending = true
                    cancelTopRecentsLaunch()
                    topNavigation.reset()
                    return true
                }
            }
        }
        handleRecentsControllerKey(event)?.let { return it }
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
            val consumed = backGesture.handleBack(event)
            return consumed
        }
        if (SHOULDER_BUTTONS.any { it.keyCode == event.keyCode }) return handleShoulder(event)
        return false
    }

    /** The Thor's style switch swaps the confirm face button at runtime. */
    private fun isRecentsConfirm(event: KeyEvent): Boolean = event.keyCode == if (
        Settings.System.getInt(contentResolver, CONTROLLER_FLIP_SETTING, 0) != 0
    ) KeyEvent.KEYCODE_BUTTON_A else KeyEvent.KEYCODE_BUTTON_B

    private fun handleRecentsControllerKey(event: KeyEvent): Boolean? {
        if (!prefs.switcherControllerEnabled) return null
        if (overlay.isShowing) {
            if (event.action == KeyEvent.ACTION_UP &&
                (isRecentsConfirm(event) || event.keyCode == KeyEvent.KEYCODE_BUTTON_X)
            ) return true
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return null
            return when {
                isRecentsConfirm(event) -> { overlay.activateSelection(); true }
                event.keyCode == KeyEvent.KEYCODE_BUTTON_X -> { overlay.closeAllFromController(); true }
                else -> null
            }
        }
        if (!topRecentsControllerActive) return null
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return null
        return when {
            isRecentsConfirm(event) -> {
                val card = windowsOnAllDisplays.get(Display.DEFAULT_DISPLAY).orEmpty()
                    .mapNotNull { it.root }.firstOrNull { it.packageName == "com.android.launcher3" }
                    ?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                val description = card?.contentDescription?.toString()
                val baseline = topLaunchBaseline
                val task = if (baseline != null && description != null && card.collectionItemInfo != null &&
                    card.parent?.viewIdResourceName == "com.android.launcher3:id/overview_panel") {
                    RecentsSelection.fromCard(baseline, baseline, description)
                } else null
                if (task != null) {
                    // Native Quickstep explicitly starts on the task's old display. Consume both
                    // halves of confirm and move first, before that old-display start can happen.
                    recentsConfirmKeys += event.deviceId to event.keyCode
                    cancelTopRecentsLaunch()
                    restoreRecentsFocus()
                    launchFromRecents(task.packageName, Display.DEFAULT_DISPLAY, task.taskId)
                    return true
                }
                // Action buttons and unidentified cards retain their native behavior.
                scheduleTopRecentsStateCheck(TOP_RECENTS_EVENT_SETTLE_MS)
                false
            }
            else -> null
        }
    }

    /** Back + D-pad, from [PadHelper] or [DpadOverlay]; both only report presses made while Back is held. */
    private fun handleDpad(direction: DpadDirection) {
        if (topRecentsControllerActive && prefs.switcherControllerEnabled) {
            if (topLaunchSelectionPending || topRecentsBackClosePending) return
            if (topRecentsInputReady && topRecentsLostFocus()) {
                topRecentsInputReady = false
                topNavigation.pause()
            }
            if (!topRecentsInputReady && !topRecentsFocusWorkerRunning && !topRecentsExitRequested) {
                topRecentsFocusPrimed = false
                topRecentsReadyAttempts = 0
                scheduleTopRecentsStateCheck(0L)
            }
            topNavigation.press(direction)
            return
        }
        if (overlay.isShowing && prefs.switcherControllerEnabled) {
            when (direction) {
                DpadDirection.LEFT -> overlay.moveSelection(right = false)
                DpadDirection.RIGHT -> overlay.moveSelection(right = true)
                DpadDirection.UP -> overlay.moveSelectionUp()
                DpadDirection.DOWN -> overlay.moveSelectionDown()
            }
            return
        }
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

    private fun queueTopRecentsDismissal() {
        topNavigation.pause()
        topRecentsPendingDismissals++
        drainTopRecentsDismissals()
    }

    private fun drainTopRecentsDismissals() {
        if (!topRecentsControllerActive || topRecentsClosing ||
            topRecentsCardBounds == null || topRecentsPendingDismissals == 0) return
        topRecentsPendingDismissals--
        dispatchTopRecentsSwipeUp()
    }

    private fun dispatchTopRecentsSwipeUp() {
        if (topRecentsClosing) return
        val bounds = topRecentsCardBounds ?: return
        val display = getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val size = android.graphics.Point().also(display::getRealSize)
        val cardHeight = bounds.height
        val startY = (bounds.bottom - cardHeight * 0.06f).coerceAtMost(size.y - 1f)
        val endY = (bounds.top - cardHeight * 0.35f).coerceAtLeast(1f)
        if (startY <= endY) return
        val path = Path().apply {
            moveTo(size.x / 2f, startY)
            lineTo(size.x / 2f, endY)
        }
        val gesture = GestureDescription.Builder()
            .setDisplayId(Display.DEFAULT_DISPLAY)
            .addStroke(GestureDescription.StrokeDescription(path, 0, TOP_RECENTS_DISMISS_MS))
            .build()
        topRecentsClosing = true
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = finishTopRecentsDismiss()
            override fun onCancelled(gestureDescription: GestureDescription?) = finishTopRecentsDismiss()
        }, handler)
        if (!accepted) topRecentsClosing = false
    }

    private fun finishTopRecentsDismiss() {
        topRecentsInputReady = false
        topRecentsFocusPrimed = false
        topRecentsClosing = false
        scheduleTopRecentsStateCheck(TOP_RECENTS_DELETE_SETTLE_MS)
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
        cancelBottomSwitcherOpen()
        cancelTopRecentsLaunch()
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
        cancelBottomSwitcherOpen()
        cancelTopRecentsLaunch()
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
        // isConnected is shared with a replacement service. Retire this instance's focus workers
        // explicitly so they cannot initialize an old Recents session after reconnection.
        topRecentsControllerActive = false
        topRecentsSession++
        topNavigation.reset()
        handler.removeCallbacks(topRecentsStateRunnable)
        if (!::padHelper.isInitialized) return
        prefs.unregisterListener(prefsListener)
        padHelper.dispose()
        dpadOverlay.detach()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::overlay.isInitialized) handler.post { overlay.refreshTheme() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            // Other applications' clicks are irrelevant; only native top Recents identifies
            // card activations here, including an app already visible on the bottom screen.
            if (event.packageName == "com.android.launcher3" && topLaunchBaseline != null) {
                val node = event.source
                val parent = node?.parent
                val description = node?.contentDescription?.toString()
                val topWindow = windowsOnAllDisplays.get(Display.DEFAULT_DISPLAY).orEmpty().any { it.id == event.windowId }
                if (topWindow && parent?.viewIdResourceName == "com.android.launcher3:id/overview_panel" && node.collectionItemInfo != null && description != null) {
                    selectTopRecentsCard(description)
                }
            }
            return
        }
        source.record(Display.DEFAULT_DISPLAY)
        bottomDisplay()?.let { source.record(it.displayId) }
        if (topRecentsControllerActive) {
            scheduleTopRecentsStateCheck(TOP_RECENTS_EVENT_SETTLE_MS)
        }
        handler.removeCallbacks(captureRunnable)
        handler.postDelayed(captureRunnable, CAPTURE_DELAY_MS)
    }

    private fun topRecentsLostFocus(): Boolean {
        val root = windowsOnAllDisplays.get(Display.DEFAULT_DISPLAY).orEmpty()
            .mapNotNull { it.root }.firstOrNull { it.packageName == "com.android.launcher3" }
            ?: return false // Window events can trail the authoritative focus snapshot.
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return true
        val card = node.collectionItemInfo != null && node.parent?.viewIdResourceName == "com.android.launcher3:id/overview_panel"
        return !card && node.viewIdResourceName !in setOf("com.android.launcher3:id/action_screenshot", "com.android.launcher3:id/clear_all_button")
    }

    private fun selectTopRecentsCard(description: String) {
        val baseline = topLaunchBaseline ?: return
        if (topLaunchSelectionPending) return
        topLaunchSelectionPending = true
        val session = topLaunchSession
        Thread {
            val states = RecentsTaskState.parse(PService.runEntryPoint(this, "RecentsStateReader"))
            handler.post {
                if (session != topLaunchSession || !isConnected) return@post
                topLaunchSelectionPending = false
                val task = states?.let { RecentsSelection.fromCard(baseline, it, description) }
                if (task != null) {
                    cancelTopRecentsLaunch()
                    // End controller initialization before moving the app. A late window-change
                    // check must not bring Overview back over the task we just selected.
                    restoreRecentsFocus()
                    if (task.displayId != Display.DEFAULT_DISPLAY) launchFromRecents(task.packageName, Display.DEFAULT_DISPLAY, task.taskId)
                    scheduleTopRecentsStateCheck(TOP_RECENTS_EVENT_SETTLE_MS)
                }
            }
        }.apply { isDaemon = true }.start()
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

    private fun launchFromRecents(pkg: String, displayId: Int) = launchFromRecents(pkg, displayId, null)

    private fun launchFromRecents(pkg: String, displayId: Int, taskId: Int?) {
        if (!canMoveApp(pkg)) return
        beforeLaunch(displayId)
        Thread { launchTask(pkg, displayId, taskId) }.apply { isDaemon = true }.start()
    }

    private fun canMoveApp(pkg: String): Boolean = Shell.isPkg(pkg) &&
        listOf(Intent.CATEGORY_HOME, Intent.CATEGORY_SECONDARY_HOME).none { category ->
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(pkg), 0).isNotEmpty()
        }

    /** Runs on a worker; swap moves must use one ordered worker rather than competing launches. */
    private fun launchTask(pkg: String, displayId: Int, taskId: Int?) {
        val args = listOfNotNull(pkg, displayId.toString(), taskId?.toString()).toTypedArray()
        val result = PService.runEntryPoint(this, "RecentsTaskLauncher", *args)
        Log.d(TAG, "Recents launch: $result")
        if (result?.startsWith("OK ") != true) {
            handler.post { runCatching { launchOn(pkg, displayId) }.onFailure { Log.e(TAG, "Recents launch failed", it) } }
        }
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

    /** Move the two captured foreground tasks; the first move must not change the second target. */
    override fun swapScreens() {
        cancelBottomSwitcherOpen()
        val bottom = bottomDisplay()?.displayId ?: return
        val top = Display.DEFAULT_DISPLAY
        overlay.dismiss()
        val topApp = source.topApp(top)
        val bottomApp = source.topApp(bottom)
        Thread {
            val states = RecentsTaskState.parse(PService.runEntryPoint(this, "RecentsStateReader"))
            val topTask = states?.firstOrNull { it.packageName == topApp && it.displayId == top }
            val bottomTask = states?.firstOrNull { it.packageName == bottomApp && it.displayId == bottom }
            topApp?.takeIf(::canMoveApp)?.let { launchTask(it, bottom, topTask?.taskId) }
            bottomApp?.takeIf(::canMoveApp)?.let { launchTask(it, top, bottomTask?.taskId) }
        }.apply { isDaemon = true }.start()
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
        cancelBottomSwitcherOpen()
        overlay.dismiss()
        val session = ++topLaunchSession
        handler.removeCallbacks(topLaunchRunnable)
        topLaunchBaseline = null
        topLaunchOverviewSeen = false
        topLaunchSelectionPending = false
        topLaunchAttempts = 0
        Thread {
            val baseline = RecentsTaskState.parse(PService.runEntryPoint(this, "RecentsStateReader"))
            handler.post {
                if (session != topLaunchSession || !isConnected) return@post
                dlog("TOP_RECENTS launch baseline=${baseline?.size}")
                topLaunchBaseline = baseline
                topLaunchStartedAt = System.currentTimeMillis() * 1_000_000L
                openNativeTopRecents()
                if (baseline != null) handler.postDelayed(topLaunchRunnable, 250L)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun checkTopRecentsLaunch() {
        val baseline = topLaunchBaseline ?: return
        if (topLaunchQueryRunning || topLaunchSelectionPending) {
            handler.postDelayed(topLaunchRunnable, 250L)
            return
        }
        topLaunchQueryRunning = true
        val session = topLaunchSession
        val overviewSeen = topLaunchOverviewSeen
        val startedAt = topLaunchStartedAt
        Thread {
            val dump = PService.exec(QUICKSTEP_STATE_COMMAND)
            val overview = dump?.let { TopRecentsState.parse(it).overview }
            val states = if (overview == false && overviewSeen) {
                RecentsTaskState.parse(PService.runEntryPoint(this, "RecentsStateReader", "selection", startedAt.toString()))
            } else null
            handler.post {
                topLaunchQueryRunning = false
                if (session != topLaunchSession || !isConnected) return@post
                if (topLaunchSelectionPending) {
                    handler.postDelayed(topLaunchRunnable, 250L)
                    return@post
                }
                when {
                    overview == true -> {
                        if (!topLaunchOverviewSeen && !prefs.switcherControllerEnabled) {
                            Thread { PService.runEntryPoint(this, "TopRecentsFocus") }.apply { isDaemon = true }.start()
                        }
                        topLaunchOverviewSeen = true
                    }
                    overview == false && topLaunchOverviewSeen -> {
                        topLaunchBaseline = null
                        val task = states?.let { RecentsSelection.movedTask(baseline, it, Display.DEFAULT_DISPLAY) }
                        dlog("TOP_RECENTS selected task=${task?.taskId} states=${states?.size}")
                        if (task != null) launchFromRecents(task.packageName, Display.DEFAULT_DISPLAY, task.taskId)
                        return@post
                    }
                    ++topLaunchAttempts >= TOP_RECENTS_READY_ATTEMPTS -> {
                        topLaunchBaseline = null
                        return@post
                    }
                }
                handler.postDelayed(topLaunchRunnable, 250L)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun cancelTopRecentsLaunch() {
        topLaunchSession++
        topLaunchBaseline = null
        topLaunchSelectionPending = false
        handler.removeCallbacks(topLaunchRunnable)
    }

    private fun openNativeTopRecents() {
        if (prefs.switcherControllerEnabled && PService.cachedAvailable()) {
            recentsFocusRestore = controllerFocus.currentTarget()
            showSwitcherFocusIndicator(top = true)
            topRecentsControllerActive = true
            topRecentsConfirmed = false
            topRecentsInputReady = false
            topRecentsFocusPrimed = false
            topRecentsFocusWorkerRunning = false
            topRecentsExitRequested = false
            topNavigation.reset()
            topRecentsSession++
            topRecentsReadyAttempts = 0
            topRecentsStateCheckRunning = false
            topRecentsStateCheckPending = false
            topRecentsCardBounds = null
            topRecentsPendingDismissals = 0
            topRecentsClosing = false
            dlog("TOP_RECENTS session started")
            updatePadHelper()
            padHelper.setSwitcherNavigation(PadNavigationMode.TOP)
            dpadOverlay.detach()
            routeTopRecentsController()
            return
        }
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    private fun routeTopRecentsController() {
        if (!topRecentsControllerActive) return
        val session = topRecentsSession
        controllerFocus.run(Prefs.DISPLAY_TOP) { routed ->
            if (session != topRecentsSession || topRecentsExitRequested) return@run
            if (routed && topRecentsControllerActive) {
                if (performGlobalAction(GLOBAL_ACTION_RECENTS)) {
                    topRecentsReadyAttempts = 0
                    scheduleTopRecentsStateCheck(TOP_RECENTS_READY_RETRY_MS)
                } else {
                    restoreRecentsFocus()
                }
            } else if (topRecentsControllerActive) {
                restoreRecentsFocus()
            }
        }
    }

    private fun onBottomSwitcherShown(displayId: Int) {
        bottomStateSession++
        requestedThumbnails.clear()
        refreshBottomTaskStates()
        if (!prefs.switcherControllerEnabled) return
        recentsFocusRestore = controllerFocus.currentTarget()
        showSwitcherFocusIndicator(top = displayId == Display.DEFAULT_DISPLAY)
        // Capture before routing: the overlay is already visible at this point.
        padHelper.setSwitcherNavigation(PadNavigationMode.CAPTURE)
        if (!padHelper.active) dpadOverlay.attach()
        val session = topRecentsSession
        controllerFocus.run(if (displayId == Display.DEFAULT_DISPLAY) Prefs.DISPLAY_TOP else Prefs.DISPLAY_BOTTOM) { routed ->
            if (!overlay.isShowing || session != topRecentsSession) return@run
            if (routed) {
                updatePadHelper()
                if (padHelper.active) {
                    padHelper.setSwitcherNavigation(PadNavigationMode.CAPTURE)
                } else {
                    dpadOverlay.attach()
                }
            }
        }
    }

    private fun onBottomSwitcherDismissed() {
        bottomStateSession++
        handler.removeCallbacks(bottomStateRunnable)
        if (!prefs.switcherControllerEnabled) return
        padHelper.setSwitcherNavigation(PadNavigationMode.OFF)
        dpadOverlay.detach()
        restoreRecentsFocus()
        updatePadHelper()
    }

    private fun refreshBottomTaskStates() {
        if (!overlay.isShowing || bottomStateQueryRunning) return
        bottomStateQueryRunning = true
        val session = bottomStateSession
        Thread {
            val states = RecentsTaskState.parse(PService.runEntryPoint(this, "RecentsStateReader"))
            handler.post {
                bottomStateQueryRunning = false
                if (!overlay.isShowing) return@post
                if (session == bottomStateSession && states != null) {
                    if (overlay.reconcileTaskStates(states)) source.retainRecentTasks(states)
                    if (!overlay.isShowing) return@post
                    loadBottomThumbnails(states, session)
                }
                handler.removeCallbacks(bottomStateRunnable)
                handler.postDelayed(bottomStateRunnable, 1000L)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun loadBottomThumbnails(states: List<RecentsTaskState>, session: Int) {
        val packages = overlay.previewPackages()
        val width = overlay.previewWidth().coerceIn(1, 1024)
        val entries = states.filter { it.packageName in packages && it.taskId >= 0 }.distinctBy { it.packageName }
        val requests = entries.map { it to RecentsThumbnailCache.Key(it.taskId, it.lastActiveTime, width, it.displayId) }
        requests.forEach { (state, key) -> recentsThumbnails.get(key)?.let { overlay.updateThumbnail(state.packageName, it) } }
        if (thumbnailQueryRunning) return
        val pending = requests.filter { (requestedThumbnails[it.second] ?: 0) < 3 }
        if (pending.isEmpty()) return
        thumbnailQueryRunning = true
        pending.forEach { requestedThumbnails[it.second] = (requestedThumbnails[it.second] ?: 0) + 1 }
        Thread {
            try {
                for ((state, key) in pending) {
                    if (session != bottomStateSession) break
                    val bitmap = runCatching {
                        val file = File.createTempFile("task_snapshot_", ".png", cacheDir)
                        try {
                            val output = PService.runEntryPoint(this, "RecentsStateReader", "snapshot",
                                state.taskId.toString(), width.toString(), file.absolutePath)
                            if (output?.startsWith("OK snapshot") == true) BitmapFactory.decodeFile(file.absolutePath) else null
                        } finally { file.delete() }
                    }.getOrNull()
                    if (bitmap != null) {
                        recentsThumbnails.put(key, bitmap)
                        handler.post {
                            if (session == bottomStateSession) requestedThumbnails[key] = 3
                            if (session == bottomStateSession && overlay.isShowing &&
                                overlay.representsTask(state)) overlay.updateThumbnail(state.packageName, bitmap)
                        }
                    }
                }
            } finally { handler.post { thumbnailQueryRunning = false } }
        }.apply { isDaemon = true }.start()
    }

    private fun onPadHelperActiveChanged(active: Boolean) {
        if (!::overlay.isInitialized || !prefs.switcherControllerEnabled ||
            (!overlay.isShowing && !topRecentsControllerActive)
        ) return
        if (active) {
            dpadOverlay.detach()
            padHelper.setSwitcherNavigation(
                when {
                    topRecentsControllerActive && topRecentsConfirmed -> PadNavigationMode.TOP
                    topRecentsControllerActive -> PadNavigationMode.TOP
                    else -> PadNavigationMode.CAPTURE
                },
            )
        } else if (overlay.isShowing) {
            dpadOverlay.attach()
        } else {
            // Top Recents is already the focused top-screen window. A focus-taking fallback would
            // sit in front of Quickstep and swallow Back; until the helper reconnects, let
            // Quickstep receive the controller directly.
            dpadOverlay.detach()
        }
    }

    private fun restoreRecentsFocus() {
        val target = recentsFocusRestore ?: return
        dlog("TOP_RECENTS session ended; restoring $target")
        recentsFocusRestore = null
        topRecentsControllerActive = false
        topRecentsConfirmed = false
        topRecentsInputReady = false
        topRecentsFocusPrimed = false
        topRecentsFocusWorkerRunning = false
        topRecentsExitRequested = false
        topNavigation.reset()
        topRecentsSession++
        topRecentsReadyAttempts = 0
        topRecentsStateCheckRunning = false
        topRecentsStateCheckPending = false
        topRecentsCardBounds = null
        topRecentsPendingDismissals = 0
        topRecentsClosing = false
        handler.removeCallbacks(topRecentsStateRunnable)
        focusIndicator.dismiss()
        padHelper.setSwitcherNavigation(PadNavigationMode.OFF)
        if (overlay.isShowing) {
            padHelper.setSwitcherNavigation(PadNavigationMode.CAPTURE)
        } else {
            dpadOverlay.detach()
            updatePadHelper()
        }
        controllerFocus.run(target)
    }

    private fun scheduleTopRecentsStateCheck(delayMs: Long) {
        if (!topRecentsControllerActive) return
        handler.removeCallbacks(topRecentsStateRunnable)
        handler.postDelayed(topRecentsStateRunnable, delayMs)
    }

    private val topRecentsStateRunnable = Runnable { requestTopRecentsStateCheck() }

    /** AYN's Quickstep keeps its task cards outside the accessibility window tree while its Recents
     *  animation owns input. Query its authoritative state only at lifecycle boundaries; controller
     *  navigation itself never waits for this root channel. */
    private fun requestTopRecentsStateCheck() {
        if (!topRecentsControllerActive) return
        if (topRecentsBackClosePending) return
        if (topLaunchSelectionPending) {
            scheduleTopRecentsStateCheck(TOP_RECENTS_EVENT_SETTLE_MS)
            return
        }
        if (topRecentsStateCheckRunning) {
            topRecentsStateCheckPending = true
            return
        }
        topRecentsStateCheckRunning = true
        val session = topRecentsSession
        val focusVersion = topRecentsFocusVersion
        Thread {
            val state = PService.exec(QUICKSTEP_STATE_COMMAND)
            handler.post {
                if (!topRecentsControllerActive || session != topRecentsSession) return@post
                topRecentsStateCheckRunning = false
                if (topRecentsBackClosePending) return@post
                if (focusVersion != topRecentsFocusVersion) {
                    scheduleTopRecentsStateCheck(0L)
                    return@post
                }
                if (topLaunchSelectionPending) {
                    scheduleTopRecentsStateCheck(TOP_RECENTS_EVENT_SETTLE_MS)
                    return@post
                }
                val snapshot = TopRecentsState.parse(state)
                snapshot.cardBounds?.let { topRecentsCardBounds = it }
                val overview = state?.let { snapshot.overview }
                when {
                    overview == true -> {
                        topRecentsConfirmed = true
                        if (topRecentsCardBounds != null) {
                            if (topRecentsInputReady && !snapshot.windowFocused) {
                                topRecentsInputReady = false
                                topNavigation.pause()
                            }
                            if (!topRecentsInputReady && topRecentsFocusPrimed && !topRecentsFocusWorkerRunning && snapshot.windowFocused && (snapshot.cardFocused || snapshot.actionsFocused)) {
                                topRecentsInputReady = true
                                topRecentsFocusPrimed = true
                                topNavigation.ready(snapshot.actionsFocused)
                            }
                            if (topRecentsInputReady || topRecentsExitRequested) {
                                drainTopRecentsDismissals()
                            } else if (!topRecentsFocusPrimed && !topRecentsFocusWorkerRunning) {
                                primeTopRecentsFocus()
                            } else if (++topRecentsReadyAttempts < TOP_RECENTS_READY_ATTEMPTS) {
                                scheduleTopRecentsStateCheck(TOP_RECENTS_READY_RETRY_MS)
                            } else {
                                restoreRecentsFocus()
                                return@post
                            }

                        } else if (++topRecentsReadyAttempts < TOP_RECENTS_READY_ATTEMPTS) {
                            scheduleTopRecentsStateCheck(TOP_RECENTS_READY_RETRY_MS)
                        } else {
                            restoreRecentsFocus()
                            return@post
                        }
                    }
                    overview == false && topRecentsConfirmed -> {
                        restoreRecentsFocus()
                        return@post
                    }
                    ++topRecentsReadyAttempts < TOP_RECENTS_READY_ATTEMPTS -> {
                        scheduleTopRecentsStateCheck(TOP_RECENTS_READY_RETRY_MS)
                    }
                    else -> {
                        restoreRecentsFocus()
                        return@post
                    }
                }
                if (topRecentsStateCheckPending) {
                    topRecentsStateCheckPending = false
                    scheduleTopRecentsStateCheck(0L)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun primeTopRecentsFocus() {
        topRecentsFocusPrimed = true
        topRecentsFocusWorkerRunning = true
        topRecentsFocusVersion++
        val session = topRecentsSession
        Thread {
            val focused = PService.runEntryPoint(this, "TopRecentsFocus")?.startsWith("OK ") == true
            handler.post {
                if (session != topRecentsSession || topRecentsExitRequested) return@post
                // Capture the centered card after the launcher handoff but before Tab can advance
                // a pooled focus into another card/action. Its node identity survives scrolling.
                val card = if (focused) centeredTopRecentsCard() else null
                if (card == null) {
                    finishTopRecentsFocus(false)
                    return@post
                }
                Thread {
                    val primed = PService.runEntryPoint(this, "TopRecentsFocus", "controller")?.startsWith("OK ") == true
                    handler.post {
                        if (session != topRecentsSession || topRecentsExitRequested) return@post
                        val restored = primed && card.refresh() &&
                            (card.isFocused || card.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
                        finishTopRecentsFocus(restored)
                    }
                }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun centeredTopRecentsCard(): AccessibilityNodeInfo? {
        val root = windowsOnAllDisplays.get(Display.DEFAULT_DISPLAY).orEmpty()
            .mapNotNull { it.root }.firstOrNull { it.packageName == "com.android.launcher3" } ?: return null
        val panel = root.findAccessibilityNodeInfosByViewId("com.android.launcher3:id/overview_panel").firstOrNull() ?: return null
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val size = android.graphics.Point().also(display::getRealSize)
        return (0 until panel.childCount).mapNotNull(panel::getChild)
            .filter { node ->
                val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                node.collectionItemInfo != null && bounds.width() > 0 && bounds.right > 0 && bounds.left < size.x
            }.minByOrNull { node ->
                val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                kotlin.math.abs(bounds.centerX() - size.x / 2)
            }
    }

    private fun finishTopRecentsFocus(primed: Boolean) {
        topRecentsFocusWorkerRunning = false
        topRecentsFocusVersion++
        if (!primed) topRecentsFocusPrimed = false
        // Only a fresh snapshot after the focus transaction may release queued directions.
        scheduleTopRecentsStateCheck(if (primed) TOP_RECENTS_READY_RETRY_MS else TOP_RECENTS_EVENT_SETTLE_MS)
    }

    private fun closeTopRecents() {
        cancelTopRecentsLaunch()
        if (topRecentsExitRequested) return
        topRecentsExitRequested = true
        topNavigation.reset()
        val session = topRecentsSession
        Thread {
            val injected = ControllerFocus.commandSucceeded(PService.exec(
                "input keyboard -d ${Display.DEFAULT_DISPLAY} keyevent ${KeyEvent.KEYCODE_BACK} && echo OK",
            ))
            handler.post {
                if (session != topRecentsSession) return@post
                if (!injected) performGlobalAction(GLOBAL_ACTION_BACK)
                scheduleTopRecentsStateCheck(TOP_RECENTS_EVENT_SETTLE_MS)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun showSwitcherFocusIndicator(top: Boolean) {
        focusIndicator.showPersistent(top, Prefs.DEFAULT_FOCUS_POSITION)
    }

    override fun onSingleTap() {
        if (bottomSwitcherOpening) { cancelBottomSwitcherOpen(); return }
        if (overlay.isShowing) { dlog("ACTION onSingleTap: overlay was showing -> dismiss only"); overlay.dismiss(); return }
        dlog("ACTION onSingleTap: singleAction=${prefs.singleAction}")
        when (prefs.singleAction) {
            Prefs.SINGLE_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            else -> goHome(component = prefs.singleLauncher)
        }
    }

    private fun goHome(displayId: Int? = null, dismissSwitcher: Boolean = true, component: ComponentName? = null) {
        val target = displayId ?: bottomDisplay()?.displayId ?: return
        if (dismissSwitcher) { cancelBottomSwitcherOpen(); overlay.dismiss() }
        val category = if (target == Display.DEFAULT_DISPLAY) Intent.CATEGORY_HOME else Intent.CATEGORY_SECONDARY_HOME
        val home = Intent(Intent.ACTION_MAIN).addCategory(category).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        component?.let { home.component = it }
        startActivity(home, ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle())
    }

    override fun showSwitcher() {
        cancelTopRecentsLaunch()
        val display = bottomDisplay() ?: return
        if (bottomSwitcherOpening) { cancelBottomSwitcherOpen(); return }
        if (overlay.isShowing) { overlay.dismiss(); return }
        bottomSwitcherOpening = true
        val session = ++bottomStateSession
        captureFrontApp {
            Thread {
                val states = RecentsTaskState.parse(PService.runEntryPoint(this, "RecentsStateReader"))
                handler.post {
                    if (session != bottomStateSession || !isConnected) return@post
                    bottomSwitcherOpening = false
                    if (states != null) source.retainRecentTasks(states)
                    runCatching { overlay.toggle(display) }.onFailure { Log.e(TAG, "toggle failed", it) }
                }
            }.apply { isDaemon = true }.start()
        }
    }

    private fun cancelBottomSwitcherOpen() {
        bottomSwitcherOpening = false
        bottomStateSession++
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
        const val QUICKSTEP_STATE_COMMAND =
            "dumpsys activity com.android.launcher3 | " +
                "awk '/ACTIVITY /{a=/QuickstepLauncher/} " +
                "a&&(/mState:|mResumed=|mHasWindowFocus=/||(/TaskView\\{/&&(!t++||/ \\.F/))||" +
                "(/ \\.F/&&/app:id\\/(action_screenshot|clear_all_button)/)){printf \"%s|\",\$0}'"
        const val TOP_RECENTS_READY_RETRY_MS = 40L
        const val TOP_RECENTS_READY_ATTEMPTS = 25
        const val TOP_RECENTS_EVENT_SETTLE_MS = 140L
        const val TOP_RECENTS_DELETE_SETTLE_MS = 260L
        const val TOP_RECENTS_DISMISS_MS = 110L
        const val CONTROLLER_FLIP_SETTING = "flip_button_layout"
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
