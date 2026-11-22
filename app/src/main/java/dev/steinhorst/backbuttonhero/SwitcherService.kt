package dev.steinhorst.backbuttonhero

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.steinhorst.backbuttonhero.overlay.FocusIndicatorOverlay
import dev.steinhorst.backbuttonhero.overlay.NoticeOverlay
import dev.steinhorst.backbuttonhero.panels.BottomPanels
import dev.steinhorst.backbuttonhero.panels.PanelWindow
import java.io.File

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
    private lateinit var panels: BottomPanels
    private lateinit var parking: FrontendParking
    private lateinit var padHelper: PadHelper
    private val gyro by lazy {
        GyroControl(this, { stick, x, y -> if (::padHelper.isInitialized) padHelper.setGyro(stick, x, y) }) { status ->
            gyroStatus = status
            gyroStatusListener?.invoke(status)
        }
    }
    /** The devices holding the gyro's hold key, while it has one. */
    private val gyroHoldDevices = mutableSetOf<Int>()
    private lateinit var dpadOverlay: DpadOverlay
    private lateinit var appControlPrompt: AppControlPrompt
    private lateinit var levels: LevelControl
    private lateinit var controllerFocus: ControllerFocus
    private lateinit var aynControls: AynControls
    private lateinit var focusIndicator: FocusIndicatorOverlay
    private lateinit var recording: ScreenRecording
    private lateinit var recordIndicator: RecordingIndicatorOverlay
    /** The dot's place for the running recording; null when its button shows none. */
    private var recordPosition: String? = null
    private var screenOffReceiver: BroadcastReceiver? = null
    /** The running recording's push-to-talk key, or null; the devices currently holding it. */
    private var pushToTalkKey: Int? = null
    private var pendingPushToTalkKey: Int? = null
    private val pushToTalkDevices = mutableSetOf<Int>()
    private var interceptionEnabled = true
    private val interceptionGate = KeyInterceptionGate()
    private val toggleGesture by lazy {
        ServiceToggleGesture(prefs, gestureScheduler,
            // Paused, both keys reach Android at DOWN; active, so does the pad's Home unless it acts as Back.
            defers = { key -> !interceptionEnabled || key == KeyEvent.KEYCODE_HOME && !prefs.homeAsBack && !prefs.dualMode },
            onPaired = { if (modifierDown) heldGesture.markComboFired() },
            onReplay = ::replayUnpaired,
            onToggle = ::toggleInterception)
    }
    private val notice by lazy { NoticeOverlay(this) }
    private val autoControllerFocus by lazy { AutoControllerFocus(this) }
    private var switchingActiveApp = false
    @Volatile private var activeAppSwitchSession = 0
    private val shoulderKeys = PhysicalKeyTracker()
    private val pendingFocusTargets = mutableMapOf<Int, ComboButton>()
    private var pendingDpadFocus: ComboButton? = null
    private val stickKeys = PhysicalKeyTracker()
    private val stickClicks by lazy {
        Stick.entries.associateWith { stick ->
            StickClickMachine(
                gestureScheduler,
                gestureClock,
                { prefs.stickDoubleMs(stick, stickClickHome[stick] == true) },
                { prefs.stickTapHoldMs(stick, stickClickHome[stick] == true) },
                { prefs.shoulderAction(stickClickButton(stick, it)).isNotEmpty() },
            ) { runStickClick(stickClickButton(stick, it)) }
        }
    }
    /** Per stick: whether its click gesture began under Home in dual mode, so it runs the Home layer. */
    private val stickClickHome = mutableMapOf<Stick, Boolean>()
    private var repeatingButton: ComboButton? = null
    private val levelRepeater by lazy {
        LevelRepeater(gestureScheduler, gestureClock) {
            val button = repeatingButton
            if (button == null || !interceptionEnabled || !modifierDown) {
                repeatingButton = null
                false
            } else {
                levels.run(prefs.shoulderAction(button), prefs.levelTarget(button), repeating = true)
                true
            }
        }
    }
    private var pendingDpadReleased = false
    private val recentsThumbnails = RecentsThumbnailCache()
    private val requestedThumbnails = mutableMapOf<RecentsThumbnailCache.Key, Int>()
    private var thumbnailQueryRunning = false
    @Volatile private var bottomStateSession = 0
    private var bottomStateQueryRunning = false
    private var bottomSwitcherOpening = false
    private var bottomSwitcherChose = false
    private var aynButtonDeviceId: Int? = null
    // Our own root press of AYN's button must reach AYN instead of returning its panel.
    private var aynButtonPresses = 0
    private var aynButtonPassing: Int? = null
    private val aynButtonPressExpiry = Runnable { aynButtonPresses = 0 }
    private val bottomStateRunnable = Runnable { refreshBottomTaskStates() }
    private val panelStateRunnable = Runnable {
        if (::panels.isInitialized) panels.refresh(bottomDisplay())
    }
    private val reclaimRunnable = Runnable {
        bottomDisplay()?.let { parking.reclaimed(it, source.topApp(it.displayId)) }
    }
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
    // The focused app's exception, latched at Back DOWN for that press and its combos.
    private var backException: AppException? = null
    // Back presses an exception hands to the app untouched, per device so mirrored ids stay paired.
    private val nativeBackDevices = mutableSetOf<Int>()
    // Back that nativeBack injects arrives here again with the virtual device id and must pass.
    private var nativeBackInjections = 0
    private val homeAsBack = HomeAsBack()
    /** The hotkey whose macro the helper is playing, under [playingMacroId]; null when none is. */
    private var playingMacro: ComboButton? = null
    private var playingMacroId = 0
    /** The macro this Back press stopped: its own hotkey in the same press only cancels, it does not restart. */
    private var macroCancelled: ComboButton? = null
    /**
     * The helper stops a macro the moment Back goes down and reports it over its socket, which usually
     * arrives before the Back key itself has passed Android's input pipeline. That Back press must
     * still count as the one that cancelled.
     */
    private var macroStopAwaitsBack = false
    /** The macro editor's sink while it records; the helper then keeps the pad's input from the app. */
    private var macroRecordListener: ((List<String>) -> Unit)? = null
    /** True while a Home press standing in for Back runs through the Back path. */
    private var translatingHome = false
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
        BackGestureMachine(prefs, gestureClock, gestureScheduler, this, ::dlog,
            { backException }, ViewConfiguration.getLongPressTimeout().toLong())
    }
    // Dual mode (docs/DUAL-MODE.md): the Home key's own gestures, bound and timed by the Home layer.
    private val homeGesture by lazy {
        BackGestureMachine(prefs.homeGestures, gestureClock, gestureScheduler, HomeGestureActions(), ::dlog,
            { backException }, ViewConfiguration.getLongPressTimeout().toLong())
    }
    /** Latched at the DOWN of a Home press that owns the virtual Back: whether it is dual mode's Home layer. */
    private var homePressDual = false
    /** The machine of the press being handled on the Back path: Home's in dual mode, otherwise Back's. */
    private val pressGesture: BackGestureMachine get() = if (translatingHome && homePressDual) homeGesture else backGesture
    /** The machine of the modifier that is held. Only one can be: the other key is swallowed meanwhile. */
    private val heldGesture: BackGestureMachine get() = if (homeGesture.backDown) homeGesture else backGesture
    /** Whether Back, or Home in dual mode, is held: only then are combos ours. */
    private val modifierDown: Boolean get() = backGesture.backDown || homeGesture.backDown

    /** [button] in the layer of the held modifier: its Home layer copy while Home is held in dual mode. */
    private fun layered(button: ComboButton): ComboButton = if (homeGesture.backDown) HomeLayerButton.of(button) else button

    private fun stickClickButton(stick: Stick, click: StickClick): ComboButton =
        StickClickButton.of(stick, click).let { if (stickClickHome[stick] == true) HomeLayerButton.of(it) else it }

    /** The Home key's gestures run the Home layer's bindings; an excepted one is the app's own Home press. */
    private inner class HomeGestureActions : GestureActions {
        override fun swapScreens() = runBackGesture(HomeLayerButton.of(BackGestureButton.HOLD))
        override fun openTopRecents() = runBackGesture(HomeLayerButton.of(BackGestureButton.TAP_HOLD))
        override fun showSwitcher() = runBackGesture(HomeLayerButton.of(BackGestureButton.DOUBLE))
        override fun onSingleTap() = runSingleTap(HomeLayerButton.of(BackGestureButton.SINGLE))
        override fun nativeBack(longPress: Boolean) { performGlobalAction(GLOBAL_ACTION_HOME) }
        override fun holdSwapBlocked() = this@SwitcherService.holdSwapBlocked()
    }

    override fun onServiceConnected() {
        if (::panels.isInitialized) panels.close()
        if (::parking.isInitialized) parking.close()
        if (::padHelper.isInitialized) stopDpad()
        interceptionEnabled = true
        aynButtonDeviceId = null
        aynButtonPresses = 0
        aynButtonPassing = null
        interceptionGate.clear()
        toggleGesture.reset()
        homeAsBack.reset()
        backGesture.reset()
        homeGesture.reset()
        homePressDual = false
        isConnected = true
        previewFocusIndicator = { top, position, ms -> handler.post { focusIndicator.show(top, position, ms) } }
        if (::recording.isInitialized) endRecording()
        recordIndicator = RecordingIndicatorOverlay(this)
        recording = ScreenRecording(this, ::onRecordingChanged)
        previewRecordIndicator = { position -> handler.post { if (!recording.isRecording) recordIndicator.preview(position) } }
        macroRecorder = object : MacroRecorderBridge {
            override fun start(onInput: (List<String>) -> Unit) {
                handler.post { startMacroRecording(onInput) }
            }
            override fun stop() {
                handler.post { stopMacroRecording() }
            }
            override val ready: Boolean get() = ::padHelper.isInitialized && padHelper.active
        }
        PService.probeAsync()
        source = AccessibilityAppSource(this) { display -> panels.cards(display) }
        panels = BottomPanels(this, source::isCovered, ::capturePanel) {
            if (::overlay.isInitialized) overlay.reconcilePanels(panels.cards(bottomDisplay()?.displayId ?: -1))
        }
        parking = FrontendParking(this)
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
            ::restorePanel,
            ::closeAynPanel,
        )
        levels = LevelControl(this, { notice.show(it) }) { bottomDisplay()?.displayId }
        focusIndicator = FocusIndicatorOverlay(this)
        controllerFocus = ControllerFocus(this, { notice.show(it) }, onFailed = focusIndicator::dismiss)
        aynControls = AynControls(this) { notice.show(it) }
        padHelper = PadHelper(
            this,
            ::handleDpad,
            ::handleDpadRelease,
            ::onPadHelperActiveChanged,
            onStick = ::handleStick,
            onRecord = { line -> macroRecordListener?.invoke(line) },
            onMacroEnd = ::onMacroEnd,
        )
        gyroOverride = { rates -> handler.post { gyro.override = rates } }
        gyroRecenter = { gyro.recenter() }
        gyroCalibrate = { calibrateGyro() }
        dpadOverlay = DpadOverlay(this, ::handleDpad, ::handleDpadRelease, ::handleStick)
        appControlPrompt = AppControlPrompt(this) { it == confirmKeyCode() }
        prefs.registerListener(prefsListener)
        runCatching { unregisterReceiver(standbyReceiver) }
        registerReceiver(standbyReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) })
        updatePadHelper()
        handler.post(panelStateRunnable)
    }

    // The helper hides the pad from Android while it runs, so keep it only when shortcuts or native
    // switcher navigation need it.
    private val prefsListener = {
        toggleGesture.reset()
        // Removing the only activation shortcut must not leave BBH stranded in pass-through.
        if (prefs.toggleButtons == Prefs.TOGGLE_NONE && !interceptionEnabled) toggleInterception()
        updatePadHelper()
        updateDpadPassthrough(currentException())
        val calibration = prefs.gyroCalibration
        if (calibration != gyro.calibration) gyro.calibration = calibration
        val gyroButton = gyro.button
        if (gyroButton != null) {
            if (prefs.shoulderAction(gyroButton) == Prefs.SHOULDER_GYRO) gyro.update(prefs.gyro(gyroButton)) else stopGyro()
        }
    }

    /** The display AYN routes the controller to, which is where a native Back would go. */
    private fun controllerDisplayId(): Int =
        if (Settings.System.getString(contentResolver, ControllerFocus.FOCUS_SETTING) == ControllerFocus.BOTTOM_VALUE) {
            bottomDisplay()?.displayId ?: Display.DEFAULT_DISPLAY
        } else Display.DEFAULT_DISPLAY

    private fun currentException(): AppException? {
        val exceptions = prefs.appExceptions
        if (exceptions.none { it.active }) return null
        val focused = windowsOnAllDisplays.get(controllerDisplayId()).orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }
            .mapNotNull { it.root?.packageName?.toString() }
            .firstOrNull { it != packageName }
        return exceptions.firstOrNull { it.active && it.packageName == focused }
    }

    // The helper decides at the instant a direction is pressed, so it must already know the app.
    private fun updateDpadPassthrough(exception: AppException?) {
        if (!::padHelper.isInitialized) return
        val passed = DpadDirection.entries.filter { exception?.allows(ExceptionControl.of(it)) == false }.toSet()
        if (padHelper.setPassthrough(passed)) dlog("DPAD   passthrough=$passed")
        updateStickCapture(exception)
    }

    // The helper takes a stick only when one of its directions is ours to act on; a direction the app
    // keeps for itself is handed back to the game the moment that deflection is recognised.
    private fun updateStickCapture(exception: AppException?) {
        if (!::padHelper.isInitialized) return
        fun bound(button: StickButton) = prefs.shoulderAction(button).isNotEmpty() ||
            prefs.dualMode && prefs.shoulderAction(HomeLayerButton.of(button)).isNotEmpty()
        fun ours(button: StickButton) = bound(button) && exception?.allows(ExceptionControl.of(button)) != false
        padHelper.setSticks(Stick.entries.filter { stick -> StickButton.entries.any { it.stick == stick && ours(it) } }.toSet())
        padHelper.setStickPassthrough(StickButton.entries.filter { exception?.allows(ExceptionControl.of(it)) == false }.toSet())
    }

    private fun allowsCombo(button: ComboButton) = backException?.allows(ExceptionControl.of(button)) ?: true

    override fun holdSwapBlocked() {
        notice.show(R.string.swap_blocked_by_exceptions)
    }

    override fun nativeBack(longPress: Boolean) {
        val display = controllerDisplayId()
        nativeBackInjections++
        Thread {
            val sent = PService.cachedAvailable() && ControllerFocus.commandSucceeded(
                PService.exec("input -d $display keyevent ${if (longPress) "--longpress " else ""}4 && echo OK"),
            )
            handler.post {
                dlog("ACTION nativeBack longPress=$longPress display=$display sent=$sent")
                if (!sent) performGlobalAction(GLOBAL_ACTION_BACK)
                // The injected pair reaches onKeyEvent asynchronously; keep it exempt a little longer.
                handler.postDelayed({ nativeBackInjections-- }, NATIVE_BACK_SETTLE_MS)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun updatePadHelper() {
        // Controller-enabled switchers must be ready on their first frame; starting the helper only
        // after one opens would recreate the pad while the user is already trying to navigate.
        val needed = prefs.hasDpadAction() || prefs.hasStickAction() || prefs.switcherControllerEnabled ||
            prefs.hasMacroAction() || macroRecordListener != null || prefs.hasGyroAction()
        if (needed) padHelper.start() else padHelper.stop()
        padHelper.setHomeMode(when {
            prefs.homeAsBack -> PadHelper.HOME_AS_BACK
            prefs.dualMode -> PadHelper.HOME_DUAL
            else -> PadHelper.HOME_PLAIN
        })
        updateStickCapture(backException)
    }

    private fun toggleInterception() {
        interceptionEnabled = !interceptionEnabled
        Log.i(TAG, "Back interception active=$interceptionEnabled")
        interceptionGate.changed()
        forgetHeldKeys()
        padHelper.setSuspended(!interceptionEnabled)
        if (!interceptionEnabled) {
            cancelBottomSwitcherOpen()
            cancelTopRecentsLaunch()
            overlay.dismiss()
            restoreRecentsFocus()
            dpadOverlay.detach()
            appControlPrompt.dismiss()
            focusIndicator.dismiss()
            cancelActiveAppSwitch()
            // Paused, Bottom Recents cannot bring a parked frontend back; return it now.
            bottomDisplay()?.let { parking.restore(it.displayId) {} }
        }
        val buttons = ServiceToggleGesture.label(prefs.toggleButtons)
        val message = if (buttons == null) getString(R.string.service_toggle_no_shortcut)
        else getString(if (interceptionEnabled) R.string.service_toggle_active else R.string.service_toggle_inactive, buttons)
        notice.show(message, long = true)
    }

    /** Every key as released. Standby needs this too: the releases of the press that locked the
     *  device never reach the service, and a key still counted as held swallows its next press. */
    private fun forgetHeldKeys() {
        backGesture.reset()
        homeGesture.reset()
        stickClickHome.clear()
        shoulderKeys.clear()
        pendingFocusTargets.clear()
        pendingDpadFocus = null
        stopLevelRepeat()
        stickClicks.values.forEach(StickClickMachine::reset)
        stickKeys.clear()
        switcherBackDown = false
        switcherBackDeviceId = Int.MIN_VALUE
        topRecentsBackClosePending = false
        recentsConfirmKeys.clear()
        nativeBackDevices.clear()
        gyroHoldDevices.clear()
        gyro.setHeld(false)
    }

    private val standbyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val on = intent.action == Intent.ACTION_SCREEN_ON
            gyro.setScreenOn(on)
            if (on) return
            forgetHeldKeys()
            toggleGesture.reset()
            homeAsBack.reset()
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Ahead of the Home-as-Back routing, so Back + Home is seen as both keys whichever leads.
        if (toggleGesture.handle(event)) return true
        return dispatchKey(event)
    }

    /**
     * A Back or Home press the Back + Home shortcut held back turned out to be alone. It takes the
     * path it would have taken at once; whatever that leaves to Android is injected, since the
     * original pair was consumed. Timing-based handling only sees the press's length.
     */
    private fun replayUnpaired(down: KeyEvent, up: KeyEvent) {
        val consumed = dispatchKey(down)
        dispatchKey(up)
        if (consumed) return
        if (down.keyCode == KeyEvent.KEYCODE_BACK) {
            nativeBack(longPress = up.eventTime - down.downTime >= ViewConfiguration.getLongPressTimeout())
        } else {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun dispatchKey(event: KeyEvent): Boolean {
        val route = homeAsBackRoute(event)
        if (route == HomeAsBack.Route.SWALLOW) return true
        if (route == HomeAsBack.Route.AS_BACK) {
            // The owning press decides its layer once; mirrored devices and the UP keep it.
            if (event.action == KeyEvent.ACTION_DOWN && !homeGesture.backDown && !backGesture.backDown) homePressDual = prefs.dualMode
            translatingHome = true
            try {
                interceptionGate.handle(asBack(event), interceptionEnabled, ::handleActiveKeyEvent)
            } finally {
                translatingHome = false
            }
            // Whatever the Back path decides, the original Home must never reach Android as Home.
            return true
        }
        if (!isHomeCombo(event) && handleAynButton(event)) return true
        return interceptionGate.handle(event, interceptionEnabled, ::handleActiveKeyEvent)
    }

    /** Back and the pad's house key, routed through [homeAsBack]; AYN's own Home button never is. */
    private fun homeAsBackRoute(event: KeyEvent): HomeAsBack.Route {
        val home = event.keyCode == KeyEvent.KEYCODE_HOME && event.isFromSource(InputDevice.SOURCE_GAMEPAD)
        if (!home && event.keyCode != KeyEvent.KEYCODE_BACK) return HomeAsBack.Route.PASS
        // Injected Back (virtual device) is ours to let through, never a physical press to pair.
        if (event.deviceId < 0) return HomeAsBack.Route.PASS
        return homeAsBack.route(event.keyCode, event.deviceId, event.action, event.repeatCount,
            interceptionEnabled && (prefs.homeAsBack || prefs.dualMode))
    }

    private fun asBack(event: KeyEvent): KeyEvent = KeyEvent(
        event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_BACK, event.repeatCount, event.metaState,
        event.deviceId, event.scanCode, event.flags, event.source,
    )

    /**
     * Back + the pad's house key is an ordinary combo and bypasses [handleAynButton], DOWN and UP alike.
     * Only a press the combo really consumes does: Home alone, an unassigned or excepted Home, and AYN's
     * own button (the same key code from another input device) keep their established path.
     */
    private fun isHomeCombo(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME || !event.isFromSource(InputDevice.SOURCE_GAMEPAD)) return false
        if (event.action != KeyEvent.ACTION_DOWN) return shoulderKeys.isDown(event.keyCode)
        return interceptionEnabled && backGesture.backDown && prefs.shoulderAction(AndroidButton.HOME).isNotEmpty() &&
            allowsCombo(AndroidButton.HOME)
    }

    /** The combo button behind [event]: a shoulder, Home/Start/Select, or a face button by its printed label. */
    private fun comboButton(event: KeyEvent): ComboButton? {
        SHOULDER_BUTTONS.firstOrNull { it.keyCode == event.keyCode }?.let { return it }
        AndroidButton.of(event.keyCode)?.let { button ->
            return button.takeIf { it != AndroidButton.HOME || event.isFromSource(InputDevice.SOURCE_GAMEPAD) }
        }
        return FaceButton.of(event.keyCode, controllerFlipped())
    }

    private fun controllerFlipped(): Boolean = Settings.System.getInt(contentResolver, CONTROLLER_FLIP_SETTING, 0) != 0

    /**
     * AYN's button arrives as a physical Home key. While we hide AYN's panel, AYN still thinks it is
     * open, so its own press would close it. The whole press is ours instead and returns the panel,
     * a long one too: whether it becomes the screen-off hold is only known after AYN missed its DOWN.
     * Runs ahead of the interception gate, because the panel must come back even while paused.
     */
    private fun handleAynButton(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        aynButtonPassing?.let { passing ->
            if (event.deviceId != passing) return@let
            if (event.action == KeyEvent.ACTION_UP) aynButtonPassing = null
            return false
        }
        val pressing = aynButtonDeviceId
        if (pressing != null) {
            if (event.deviceId != pressing) return false
            if (event.action == KeyEvent.ACTION_UP) {
                aynButtonDeviceId = null
                if (overlay.isShowing) overlay.dismiss()
                panels.showCovering()
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0 || event.deviceId < 0) return false
        if (aynButtonPresses > 0) {
            aynButtonPresses--
            aynButtonPassing = event.deviceId
            return false
        }
        if (!::panels.isInitialized || !panels.hasHiddenCovering) return false
        aynButtonDeviceId = event.deviceId
        return true
    }

    private fun handleActiveKeyEvent(event: KeyEvent): Boolean {
        val confirmKey = event.deviceId to event.keyCode
        if (confirmKey in recentsConfirmKeys) {
            if (event.action == KeyEvent.ACTION_UP) recentsConfirmKeys.remove(confirmKey)
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            // Explicitly injected keyboard Back belongs to Quickstep or the excepted app, including its UP.
            if ((topRecentsExitRequested || nativeBackInjections > 0) && event.deviceId == -1) {
                dlog("BACK   injected passthrough action=${event.action} longPress=${event.isLongPress}")
                return false
            }
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
                if (overlay.isShowing && !pressGesture.followsRecentTap()) {
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
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !pressGesture.backDown) {
                // The helper stops the macro on this press too; mirror it and remember whose it was.
                if (playingMacro != null) {
                    macroCancelled = playingMacro
                    stopMacroPlayback()
                } else if (!macroStopAwaitsBack) {
                    macroCancelled = null
                }
                macroStopAwaitsBack = false
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                backException = currentException()
                updateDpadPassthrough(backException)
                dlog("EXCEPTION display=${controllerDisplayId()} rule=${backException?.let { "${it.packageName} allowed=${it.allowed} native=${it.backNative}" }}")
                if (backException?.backNative == true) nativeBackDevices += event.deviceId
            }
            if (event.deviceId in nativeBackDevices) {
                if (event.action == KeyEvent.ACTION_UP) nativeBackDevices -= event.deviceId
                pressGesture.observeNativeBack(event, nativeBackDevices.size)
                dlog("BACK   native passthrough action=${event.action} device=${event.deviceId} home=$translatingHome")
                // Passing a translated Home on would hand Android a Home press; the app gets a Back instead.
                // In dual mode Home is its own key, so the app gets the Home it pressed.
                if (translatingHome) {
                    if (event.action == KeyEvent.ACTION_UP) {
                        if (homePressDual) performGlobalAction(GLOBAL_ACTION_HOME) else nativeBack(longPress = false)
                    }
                    return true
                }
                return false
            }
            // Without the root helper the D-pad can only be caught by taking window focus; see DpadOverlay.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !padHelper.active &&
                (DpadDirection.entries + StickButton.entries).any { base ->
                    val button = if (pressGesture === homeGesture) HomeLayerButton.of(base) else base
                    prefs.shoulderAction(button).isNotEmpty() && allowsCombo(button)
                }) {
                dpadOverlay.attach()
            }
            if (event.action == KeyEvent.ACTION_UP) {
                stopLevelRepeat()
                val overlayWasAttached = dpadOverlay.attached
                dpadOverlay.detach()
                // Once the overlay is gone a still held D-pad is released to the app, unseen; Back's
                // release is the last moment left to commit an armed controller focus.
                if (overlayWasAttached) {
                    pendingDpadReleased = true
                    commitDpadFocus()
                }
            }
            return pressGesture.handleBack(event)
        }
        if (handlePushToTalk(event)) return true
        if (handleGyroHold(event)) return true
        comboButton(event)?.let { return handleShoulder(event, layered(it)) }
        Stick.entries.firstOrNull { it.keyCode == event.keyCode }?.let { return handleStickClick(it, event) }
        return false
    }

    /** The Thor's style switch swaps the confirm face button at runtime. */
    private fun isRecentsConfirm(event: KeyEvent): Boolean = event.keyCode == confirmKeyCode()

    private fun confirmKeyCode(): Int = if (controllerFlipped())
        KeyEvent.KEYCODE_BUTTON_A else KeyEvent.KEYCODE_BUTTON_B

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
        if (!interceptionEnabled) return
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
        if (!modifierDown) return
        val button = layered(direction)
        val action = prefs.shoulderAction(button)
        if (action.isEmpty()) return
        if (!allowsCombo(button)) {
            // The helper already forwarded this press to the game; see handleShoulder.
            heldGesture.markComboFired()
            dlog("DPAD   $button passthrough action=$action")
            return
        }
        heldGesture.markComboFired()
        pendingDpadFocus = null
        // Same firmware race as for the shoulder buttons (see handleShoulder): arm on press,
        // commit on release.
        if (action == Prefs.SHOULDER_CONTROLLER_FOCUS || action == Prefs.SHOULDER_SWITCH_APP) {
            pendingDpadFocus = button
            pendingDpadReleased = false
            if (action == Prefs.SHOULDER_CONTROLLER_FOCUS) showFocusIndicator(button)
        } else {
            stopLevelRepeat()
            runComboAction(button, action)
            startLevelRepeat(button, action)
        }
    }

    /** Back + analog stick, from [PadHelper] or [DpadOverlay]; a null [direction] is the stick returning to neutral. */
    private fun handleStick(stick: Stick, direction: DpadDirection?) {
        if (direction == null) {
            if ((repeatingButton?.base as? StickButton)?.stick == stick) stopLevelRepeat()
            if ((pendingDpadFocus?.base as? StickButton)?.stick == stick) {
                pendingDpadReleased = true
                commitDpadFocus()
            }
            return
        }
        if (!interceptionEnabled || overlay.isShowing || topRecentsControllerActive || !modifierDown) return
        val button = layered(StickButton.of(stick, direction))
        val action = prefs.shoulderAction(button)
        if (action.isEmpty() || !allowsCombo(button)) return
        heldGesture.markComboFired()
        pendingDpadFocus = null
        stopLevelRepeat()
        // One deflection is one action, except the stepping ones, which repeat while it is held.
        if (action == Prefs.SHOULDER_CONTROLLER_FOCUS || action == Prefs.SHOULDER_SWITCH_APP) {
            pendingDpadFocus = button
            pendingDpadReleased = false
            if (action == Prefs.SHOULDER_CONTROLLER_FOCUS) showFocusIndicator(button)
        } else {
            dlog("STICK  $button action=$action")
            runComboAction(button, action)
            startLevelRepeat(button, action)
        }
    }

    /** A held stepping control (volume, brightness) keeps stepping, faster the longer it is held. */
    private fun startLevelRepeat(button: ComboButton, action: String) {
        if (!Prefs.isLevelAction(action)) return
        repeatingButton = button
        levelRepeater.start()
    }

    /**
     * L3/R3 while Back is held. A press is ours only when the stick has a click action; otherwise
     * the game keeps it, like an unassigned shoulder button. The Thor mirrors its controller, so
     * both devices' DOWN/UP are consumed and the gesture sees one press.
     */
    private fun handleStickClick(stick: Stick, event: KeyEvent): Boolean {
        val machine = stickClicks.getValue(stick)
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (!interceptionEnabled || !modifierDown || !allowsCombo(StickClickButton.of(stick, StickClick.PRESS))) return false
                val first = stickKeys.down(event.keyCode, event.deviceId)
                if (first && event.repeatCount == 0) {
                    stickClickHome[stick] = homeGesture.backDown
                    if (!machine.down()) { stickKeys.up(event.keyCode, event.deviceId); return false }
                    heldGesture.markComboFired()
                }
                stickKeys.isDown(event.keyCode)
            }
            KeyEvent.ACTION_UP -> {
                val tracked = stickKeys.up(event.keyCode, event.deviceId)
                if (tracked && !stickKeys.isDown(event.keyCode)) {
                    if ((repeatingButton?.base as? StickClickButton)?.stick == stick) stopLevelRepeat()
                    machine.up()
                }
                tracked
            }
            else -> false
        }
    }

    private fun runStickClick(button: ComboButton) {
        val action = prefs.shoulderAction(button)
        if (action.isEmpty() || !interceptionEnabled) return
        dlog("STICK  $button action=$action")
        if ((button.base as StickClickButton).click == StickClick.TAP_HOLD) {
            // Fires while still held, like Back's tap-and-hold; stepping actions keep repeating until release.
            stopLevelRepeat()
            runComboAction(button, action)
            startLevelRepeat(button, action)
        } else {
            runComboAction(button, action)
        }
    }

    private fun stopLevelRepeat() {
        repeatingButton = null
        if (::padHelper.isInitialized) levelRepeater.stop()
    }

    private fun handleDpadRelease(horizontal: Boolean) {
        (repeatingButton?.base as? DpadDirection)?.let {
            if ((it == DpadDirection.LEFT || it == DpadDirection.RIGHT) == horizontal) stopLevelRepeat()
        }
        val pending = pendingDpadFocus?.base as? DpadDirection ?: return
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
        runReleasedCombo(pending)
    }

    /** Holding Back and pressing a shoulder, Home/Start/Select or face button runs that button's
     *  configured action instead of whatever Back alone would have done, and swallows the button's
     *  own event so the focused app/game never sees a stray press. Buttons with nothing
     *  configured are left alone, even while Back is held. */
    private fun handleShoulder(event: KeyEvent, button: ComboButton): Boolean {
        val action = prefs.shoulderAction(button)
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Outside an active Back combo the controller belongs entirely to the foreground
                // app. Do not even track the key in that case, so its UP remains untouched too.
                if (!modifierDown || action.isEmpty()) return false
                if (!allowsCombo(button)) {
                    // The app gets the button, yet the press was still a combo: Back's release must
                    // not turn into a tap that opens something on top of the app's own shortcut.
                    heldGesture.markComboFired()
                    dlog("SHOULDER $button passthrough action=$action")
                    return false
                }
                if (shoulderKeys.down(event.keyCode, event.deviceId) && event.repeatCount == 0) {
                    heldGesture.markComboFired()
                    // Changing the controller route while its trigger is physically held is racy
                    // in AYN's firmware: the setting changes, but the route can stay on the old
                    // display. Arm focus here and commit after both mirrored devices report UP.
                    if (action == Prefs.SHOULDER_CONTROLLER_FOCUS || action == Prefs.SHOULDER_SWITCH_APP) {
                        pendingFocusTargets[event.keyCode] = button
                        if (action == Prefs.SHOULDER_CONTROLLER_FOCUS) showFocusIndicator(button)
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
                    pendingFocusTargets.remove(event.keyCode)?.let(::runReleasedCombo)
                }
                tracked
            }
            else -> false
        }
    }

    private fun runReleasedCombo(button: ComboButton) {
        if (prefs.shoulderAction(button) == Prefs.SHOULDER_SWITCH_APP) switchActiveApp(button)
        else runControllerFocus(button)
    }

    private fun showFocusIndicator(button: ComboButton, target: String = prefs.focusTarget(button)) {
        // The modal has no target yet; the indicator follows once a screen is chosen.
        if (target == Prefs.FOCUS_MODAL) return
        val position = prefs.focusPosition(button).takeIf { prefs.focusIndicator(button) }
        focusIndicator.show(
            controllerFocus.targetIsTop(target),
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
        if (prefs.focusTarget(button) != Prefs.FOCUS_MODAL) {
            showFocusIndicator(button)
            controllerFocus.run(prefs.focusTarget(button))
            return
        }
        val options = listOf(
            AppControlPrompt.Option(Prefs.DISPLAY_TOP, getString(R.string.level_target_top)),
            AppControlPrompt.Option(Prefs.DISPLAY_BOTTOM, getString(R.string.level_target_bottom)),
        )
        appControlPrompt.show(promptDisplay(), getString(R.string.focus_target_dialog_title), options) { chosen ->
            showFocusIndicator(button, chosen)
            controllerFocus.run(chosen)
        }
    }

    /**
     * Plays [button]'s macro through the helper's pad clone. The helper starts it once Back is
     * released, so the macro's keys never read as Back + button shortcuts; pressing the same hotkey
     * while it runs only stops it.
     */
    private fun runMacro(button: ComboButton) {
        if (macroCancelled == button) {
            macroCancelled = null
            return
        }
        val macro = prefs.macro(button) ?: return
        val id = ++playingMacroId
        if (padHelper.active && padHelper.playMacro(id, macro.events(controllerFlipped()))) {
            playingMacro = button
            dlog("MACRO  play ${button.prefKey} steps=${macro.steps.size}")
        } else {
            notice.show(R.string.macro_helper_missing)
        }
    }

    private fun onMacroEnd(id: Int, stopped: Boolean) {
        if (id != playingMacroId || playingMacro == null) return
        if (stopped) {
            macroCancelled = playingMacro
            macroStopAwaitsBack = true
        }
        playingMacro = null
    }

    private fun stopMacroPlayback() {
        padHelper.stopMacro()
        playingMacro = null
    }

    private fun startMacroRecording(onInput: (List<String>) -> Unit) {
        if (::padHelper.isInitialized && playingMacro != null) stopMacroPlayback()
        macroRecordListener = onInput
        updatePadHelper()
        padHelper.setRecording(true)
    }

    private fun stopMacroRecording() {
        if (macroRecordListener == null) return
        macroRecordListener = null
        padHelper.setRecording(false)
        updatePadHelper()
    }

    /** The AYN tiles as hotkeys: a toggle flips, an option applies at once, "modal" asks first. */
    private fun runAynAction(button: ComboButton, action: String) {
        Ayn.toggleOf(action)?.let { return aynControls.toggle(it) }
        val group = Ayn.groupOf(action) ?: return
        val chosen = prefs.aynOption(button, group)
        fun apply(key: String) {
            group.options.firstOrNull { it.key == key }?.let { aynControls.apply(group, it) }
        }
        if (chosen != Ayn.MODAL) {
            apply(chosen)
            return
        }
        val options = group.options.map { AppControlPrompt.Option(it.key, getString(it.labelRes)) }
        appControlPrompt.show(promptDisplay(), getString(group.titleRes), options) { apply(it) }
    }

    private fun runComboAction(button: ComboButton, action: String) {
        when (action) {
            Prefs.SHOULDER_FLICK_LEFT -> navigationFlick(left = true)
            Prefs.SHOULDER_FLICK_RIGHT -> navigationFlick(left = false)
            Prefs.SHOULDER_VOLUME_UP, Prefs.SHOULDER_VOLUME_DOWN,
            Prefs.SHOULDER_BRIGHTNESS_UP, Prefs.SHOULDER_BRIGHTNESS_DOWN -> levels.run(action, prefs.levelTarget(button))
            Prefs.SHOULDER_CONTROLLER_FOCUS -> runControllerFocus(button)
            Prefs.SHOULDER_SWITCH_APP -> switchActiveApp(button)
            Prefs.SHOULDER_APP_CONTROL -> runAppControl(button)
            Prefs.SHOULDER_CLOSE_APPS -> runCloseApps(button, keepForeground = false)
            Prefs.SHOULDER_CLOSE_BACKGROUND_APPS -> runCloseApps(button, keepForeground = true)
            Prefs.SHOULDER_POWER_MENU -> showPowerMenu()
            Prefs.SHOULDER_STANDBY -> runStandby(button)
            Prefs.SHOULDER_VOLUME_MUTE -> levels.toggleMute(prefs.levelTarget(button))
            Prefs.SHOULDER_RECORD_VIDEO -> toggleRecording(button)
            Prefs.SHOULDER_MACRO -> runMacro(button)
            Prefs.SHOULDER_MEDIA_PLAY_PAUSE -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            Prefs.SHOULDER_MEDIA_NEXT -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            Prefs.SHOULDER_MEDIA_PREVIOUS -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            Prefs.SHOULDER_STATUS -> showStatus()
            Prefs.SHOULDER_GYRO -> toggleGyro(button)
            Prefs.SHOULDER_GYRO_CALIBRATE -> calibrateGyro()
            in Ayn.ACTIONS -> runAynAction(button, action)
            Prefs.SHOULDER_HOME -> onLaunchScreen(button, ::goHome)
            Prefs.SHOULDER_APP_ASK -> chooseApp(button, favorites = false)
            Prefs.SHOULDER_APP_FAVORITES -> chooseApp(button, favorites = true)
            Prefs.SHOULDER_RECENTS -> openTopRecentsNow()
            Prefs.SHOULDER_SWITCHER -> showSwitcherNow()
            Prefs.SHOULDER_SWAP_APPS -> swapScreensNow()
            Prefs.SHOULDER_LAST_APP -> switchToLastApp()
            Prefs.SHOULDER_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
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
            else -> onLaunchScreen(button) { displayId ->
                dlog("COMBO ${button.name} + Back -> launching $action on display $displayId")
                launchOn(action, displayId)
            }
        }
    }

    /** Lists every launchable app, or only the starred ones, and opens the one chosen on [button]'s screen. */
    private fun chooseApp(button: ComboButton, favorites: Boolean) {
        val starred = prefs.favoriteApps
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            .map { AppControlPrompt.Option(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
            .distinctBy { it.value }
            .filter { !favorites || it.value in starred }
            .sortedBy { it.label }
        if (apps.isEmpty()) return notice.show(R.string.app_favorites_none)
        appControlPrompt.show(promptDisplay(), getString(R.string.shoulder_choose_app_title), apps) { pkg ->
            onLaunchScreen(button) { displayId -> runCatching { launchOn(pkg, displayId) }.onFailure { toastUnavailable() } }
        }
    }

    /** Brings back the app used before the current one on the controller's screen; see [LastApp]. */
    private fun switchToLastApp() {
        val display = controllerDisplayId()
        val candidates = LastApp.candidates(source.appsOnDisplay(display).map { it.packageName }, source.topApp(display), packageName)
        if (candidates.isEmpty()) return notice.show(R.string.last_app_none)
        if (!PService.cachedAvailable()) {
            // Without root the task cannot be checked; the launch still brings an existing one back.
            runCatching { launchOn(candidates.first(), display) }.onFailure { toastUnavailable() }
            return
        }
        Thread {
            // History is per screen but never drops an app that moved to the other one; the launcher
            // refuses those, so the next app of this screen becomes the last app instead.
            val attempt = candidates.firstNotNullOfOrNull { pkg ->
                val result = PService.runEntryPoint(this, "RecentsTaskLauncher", pkg, display.toString(), LastApp.SAME_DISPLAY)
                Log.d(TAG, "Last app $pkg on $display: $result")
                if (result == LastApp.OTHER_DISPLAY) null else pkg to result
            }
            handler.post {
                val (previous, result) = attempt ?: return@post notice.show(R.string.last_app_none)
                when {
                    result?.startsWith("OK ") == true -> Unit
                    result == LastApp.TASK_GONE -> {
                        source.forget(previous)
                        notice.show(getString(R.string.last_app_gone, appLabel(previous)))
                    }
                    else -> notice.show(R.string.swap_unavailable, long = true)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun appLabel(pkg: String): String =
        runCatching { packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString() }.getOrDefault(pkg)

    private fun cancelActiveAppSwitch() {
        activeAppSwitchSession++
        switchingActiveApp = false
        autoControllerFocus.dismiss()
    }

    private fun switchActiveApp(button: ComboButton) {
        if (switchingActiveApp) return
        val display = bottomDisplay() ?: return
        val top = Display.DEFAULT_DISPLAY
        val bottom = display.displayId
        val route = controllerFocus.currentTarget()
        val follow = prefs.switchAppFollowFocus(button)
        val foreground = mapOf(top to source.topApp(top), bottom to source.topApp(bottom))
        switchingActiveApp = true
        val session = ++activeAppSwitchSession
        fun active() = isConnected && interceptionEnabled && session == activeAppSwitchSession
        Thread {
            val from = when (route) {
                Prefs.DISPLAY_TOP -> top
                Prefs.DISPLAY_BOTTOM -> bottom
                else -> AutoControllerFocus.focusedDisplay(PService.exec("dumpsys input | grep -m1 'FocusedDisplayId:'"))
            }
            val pkg = foreground[from]
            val task = if (pkg == null) null else {
                val tasks = RecentsTaskState.parse(PService.runEntryPoint(this, "RecentsStateReader", "include-excluded"))
                tasks?.firstOrNull { it.packageName == pkg && it.displayId == from && it.movable }
            }
            handler.post resolved@ {
                if (!active()) return@resolved
                if (from != top && from != bottom) {
                    switchingActiveApp = false
                    toastUnavailable()
                    return@resolved
                }
                val target = if (from == top) bottom else top
                if (pkg == null) {
                    switchingActiveApp = false
                    notice.show(R.string.app_control_no_app)
                    return@resolved
                }
                if (task == null) {
                    switchingActiveApp = false
                    toastUnavailable()
                    return@resolved
                }
                cancelBottomSwitcherOpen()
                overlay.dismiss()
                panels.refresh(display) {
                    if (!active()) return@refresh
                    if (panels.preventsSwap(pkg, null)) {
                        switchingActiveApp = false
                        notice.show(R.string.panel_swap_unavailable, long = true)
                        return@refresh
                    }
                    prepareBottomLaunch(target) {
                        if (!active()) return@prepareBottomLaunch
                        Thread {
                            val moved = session == activeAppSwitchSession && launchTask(pkg, target, task.taskId, allowRelaunch = false)
                            handler.post moved@ {
                                if (!active()) return@moved
                                switchingActiveApp = false
                                if (!moved) return@moved
                                if (route == ControllerFocus.AUTO_TARGET) {
                                    switchingActiveApp = true
                                    autoControllerFocus.run(if (follow) target else from) { ok ->
                                        switchingActiveApp = false
                                        if (active() && !ok) toastUnavailable()
                                    }
                                } else if (follow) {
                                    switchingActiveApp = true
                                    controllerFocus.run(if (target == top) Prefs.DISPLAY_TOP else Prefs.DISPLAY_BOTTOM) {
                                        if (active()) switchingActiveApp = false
                                    }
                                }
                            }
                        }.apply { isDaemon = true }.start()
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Minimizes or force-closes the foreground app of the chosen screen. The apps are resolved
     * before any prompt opens, so "the app behind the modal" is the one that gets hit even if the
     * window state shifts while the user decides.
     */
    private fun runAppControl(button: ComboButton) {
        val mode = prefs.appMode(button)
        val screen = prefs.appScreen(button)
        val bottomId = bottomDisplay()?.displayId
        fun resolve(chosen: String) = AppControl.targets(chosen, Display.DEFAULT_DISPLAY, bottomId, source::topApp)
        if (screen == Prefs.APP_SCREEN_ASK) {
            val labels = mapOf(
                Prefs.DISPLAY_TOP to getString(R.string.level_target_top),
                Prefs.DISPLAY_BOTTOM to getString(R.string.level_target_bottom),
                Prefs.TARGET_BOTH to getString(R.string.level_target_both),
            )
            val options = listOf(Prefs.DISPLAY_TOP, Prefs.DISPLAY_BOTTOM, Prefs.TARGET_BOTH)
                .map { AppControlPrompt.Option(it, labels.getValue(it)) }
            // Resolved up front: the modal must not change which app counts as "in front".
            val resolved = options.associate { it.value to resolve(it.value) }
            appControlPrompt.show(promptDisplay(), getString(R.string.app_control_prompt_title), options) { chosen ->
                applyAppControl(mode, resolved.getValue(chosen))
            }
            return
        }
        val targets = resolve(screen)
        if (mode != Prefs.APP_MINIMIZE && prefs.appConfirm(button) && targets.isNotEmpty()) {
            val pm = packageManager
            val names = targets.joinToString(", ") {
                runCatching { pm.getApplicationInfo(it.packageName, 0).loadLabel(pm).toString() }.getOrDefault(it.packageName)
            }
            val restart = mode == Prefs.APP_RESTART
            // The prompt adds its own Cancel row below.
            val options = listOf(
                AppControlPrompt.Option("yes", getString(if (restart) R.string.app_control_prompt_restart_yes else R.string.app_control_prompt_close_yes)),
            )
            val title = getString(if (restart) R.string.app_control_prompt_restart_title else R.string.app_control_prompt_close_title, names)
            appControlPrompt.show(promptDisplay(), title, options) {
                if (it == "yes") applyAppControl(mode, targets)
            }
            return
        }
        applyAppControl(mode, targets)
    }

    /** Closes the apps known to run on the button's screens, optionally sparing whatever is in front. */
    private fun runCloseApps(button: ComboButton, keepForeground: Boolean) {
        val all = listOfNotNull(Display.DEFAULT_DISPLAY, bottomDisplay()?.displayId)
        val displays = CloseApps.displays(prefs.levelTarget(button), Display.DEFAULT_DISPLAY, bottomDisplay()?.displayId)
        val packages = CloseApps.packages(displays, all, keepForeground, { id -> source.appsOnDisplay(id).map { it.packageName } }, source::topApp)
        Log.d(TAG, "Close apps on $displays (keep foreground: $keepForeground): $packages")
        if (packages.isEmpty()) {
            notice.show(R.string.close_apps_none)
            return
        }
        // Every screen, not only the chosen ones: a closed app may have moved in front of the other one.
        fun close() {
            packages.forEach(source::forget)
            killOnly(packages, all.toSet(), reportMemory = true)
        }
        if (!prefs.closeConfirm(button)) {
            close()
            return
        }
        val pm = packageManager
        val names = packages.joinToString(", ") {
            runCatching { pm.getApplicationInfo(it, 0).loadLabel(pm).toString() }.getOrDefault(it)
        }
        val options = listOf(AppControlPrompt.Option("yes", getString(R.string.close_apps_prompt_yes)))
        appControlPrompt.show(promptDisplay(), getString(R.string.close_apps_prompt_title, names), options) {
            if (it == "yes") close()
        }
    }

    /** Locks and switches off the screens as a short press of the power button does. */
    private fun runStandby(button: ComboButton) {
        if (!prefs.standbyConfirm(button)) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            return
        }
        val options = listOf(AppControlPrompt.Option("yes", getString(R.string.group_standby)))
        appControlPrompt.show(promptDisplay(), getString(R.string.standby_prompt_title), options) {
            if (it == "yes") performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }
    }

    /** Opens the system's own power dialog and gives its first option input focus, so the D-pad,
     *  the stick and A operate it right away. */
    private fun showPowerMenu() {
        val before = windowsOnAllDisplays.get(Display.DEFAULT_DISPLAY).orEmpty().map { it.id }.toSet()
        if (performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)) focusPowerMenu(before, POWER_MENU_FOCUS_ATTEMPTS)
    }

    /** The dialog's window appears asynchronously; retry until it is there and its option accepts focus.
     *  Only a system window qualifies: a second press closes an open dialog instead, and the app it
     *  uncovers then shows up as a new focused window whose views must not be touched. */
    private fun focusPowerMenu(before: Set<Int>, attemptsLeft: Int) {
        val window = windowsOnAllDisplays.get(Display.DEFAULT_DISPLAY).orEmpty()
            .firstOrNull { it.id !in before && it.isFocused && it.type == AccessibilityWindowInfo.TYPE_SYSTEM }
        val option = window?.root?.let { root ->
            PowerMenu.firstOption(root, { node -> (0 until node.childCount).mapNotNull(node::getChild) }) { it.isFocusable }
        }
        val focused = option?.isFocused == true || option?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) == true
        if (!focused && attemptsLeft > 1) {
            handler.postDelayed({ focusPowerMenu(before, attemptsLeft - 1) }, POWER_MENU_FOCUS_RETRY_MS)
        } else {
            Log.d(TAG, "Power menu focus: ${if (focused) "set" else "not set"} (window ${window?.id}, ${attemptsLeft - 1} attempts left)")
        }
    }

    /** The prompt belongs where the controller is, so its D-pad and buttons reach it. */
    private fun promptDisplay(): Display = getSystemService(DisplayManager::class.java)
        .getDisplay(controllerDisplayId()) ?: getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)

    private fun applyAppControl(mode: String, targets: List<AppControlTarget>) {
        if (targets.isEmpty()) {
            notice.show(R.string.app_control_no_app)
            return
        }
        if (mode == Prefs.APP_FORCE_CLOSE) {
            // killOnly already leaves the app for Home first and falls back to an unprivileged kill.
            killOnly(targets.map { it.packageName }.distinct(), targets.map { it.displayId }.toSet(), reportMemory = true)
        } else if (mode == Prefs.APP_RESTART) {
            // Each app comes back on the screen it left; our own settings only lose their task, see killOnly.
            killOnly(targets.map { it.packageName }.distinct(), targets.map { it.displayId }.toSet()) {
                targets.filter { it.packageName != packageName }.forEach { launchOn(it.packageName, it.displayId) }
            }
        } else {
            targets.forEach { goHome(it.displayId, dismissSwitcher = false) }
        }
    }

    /** Media keys go to whichever session plays, as a headset button would; no root needed. */
    private fun sendMediaKey(keyCode: Int) {
        val audio = getSystemService(AudioManager::class.java)
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    /** One line of battery facts as a toast on the top screen, the one AYN's assistant leaves visible. */
    private fun showStatus() {
        val battery = getSystemService(BatteryManager::class.java)
        // Sticky: no receiver is registered, the last battery broadcast is only read.
        val sticky = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val tenths = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val minutes = if (plugged) {
            battery.computeChargeTimeRemaining().takeIf { it > 0 }?.let { (it / 60_000L).toInt() }
        } else {
            StatusSummary.minutesLeft(
                battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
                battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            )
        }
        fun duration(m: Int) = if (m >= 60) getString(R.string.status_hours_minutes, m / 60, m % 60) else getString(R.string.status_minutes, m)
        val parts = listOfNotNull(
            getString(R.string.status_battery, level),
            when {
                plugged && minutes != null -> getString(R.string.status_full_in, duration(minutes))
                plugged -> getString(R.string.status_plugged)
                minutes != null -> getString(R.string.status_left, duration(minutes))
                else -> null
            },
            if (tenths == Int.MIN_VALUE) null else getString(R.string.status_temperature, tenths / 10f),
        )
        notice.show(parts.joinToString(" · "), long = true)
    }

    private fun toggleRecording(button: ComboButton) {
        recording.toggle {
            recordPosition = prefs.recordPosition(button).takeIf { prefs.recordIndicator(button) }
            val mic = prefs.recordMic(button)
            // Push to talk without a chosen button would leave the microphone closed for good.
            val key = prefs.recordPttKey(button).takeIf { mic && prefs.recordPtt(button) && PushToTalk.isAllowed(it) }
            pendingPushToTalkKey = key
            ScreenRecording.Request(
                prefs.levelTarget(button), Display.DEFAULT_DISPLAY, bottomDisplay()?.displayId,
                ScreenRecording.bitRate(prefs.recordQuality(button)), mic, key != null, prefs.recordDuck(button),
            )
        }
    }

    private fun onRecordingChanged(event: ScreenRecording.Event) {
        pushToTalkDevices.clear()
        pushToTalkKey = pendingPushToTalkKey.takeIf { event == ScreenRecording.Event.STARTED }
        if (event == ScreenRecording.Event.STARTED) {
            recordPosition?.let(recordIndicator::show)
            // A recording ends with the screens: standby must not leave it running in the dark.
            if (screenOffReceiver == null) {
                screenOffReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) = recording.stop()
                }.also { registerReceiver(it, IntentFilter(Intent.ACTION_SCREEN_OFF)) }
            }
        } else {
            recordIndicator.dismiss()
            unregisterScreenOff()
        }
        val message = when (event) {
            ScreenRecording.Event.STARTED -> R.string.record_started
            ScreenRecording.Event.SAVED -> R.string.record_saved
            ScreenRecording.Event.STOPPED -> R.string.record_stopped
            ScreenRecording.Event.FAILED -> R.string.record_failed
            ScreenRecording.Event.NEEDS_ROOT -> R.string.record_needs_root
        }
        notice.show(message, long = event == ScreenRecording.Event.NEEDS_ROOT)
    }

    /**
     * While a push-to-talk recording runs, its button opens the microphone for as long as it is held and
     * never reaches the app. With Back held it stays the button of its Back combination.
     */
    private fun handlePushToTalk(event: KeyEvent): Boolean {
        val key = pushToTalkKey ?: return false
        if (event.keyCode != key) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (pushToTalkDevices.isEmpty() && modifierDown) return false
                if (pushToTalkDevices.add(event.deviceId) && pushToTalkDevices.size == 1) setTalking(true)
            }
            KeyEvent.ACTION_UP -> {
                if (!pushToTalkDevices.remove(event.deviceId)) return false
                if (pushToTalkDevices.isEmpty()) setTalking(false)
            }
        }
        return true
    }

    /**
     * With holding chosen, the gyro steers only while its key is held. The key still reaches the game
     * unless the setup keeps it back; with Back held it stays the button of its Back combination.
     */
    private fun handleGyroHold(event: KeyEvent): Boolean {
        val key = gyro.settings.holdKeyInUse?.takeIf { gyro.on } ?: return false
        if (event.keyCode != key) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (gyroHoldDevices.isEmpty() && modifierDown) return false
                if (gyroHoldDevices.add(event.deviceId) && gyroHoldDevices.size == 1) gyro.setHeld(true)
            }
            KeyEvent.ACTION_UP -> {
                if (!gyroHoldDevices.remove(event.deviceId)) return false
                if (gyroHoldDevices.isEmpty()) gyro.setHeld(false)
            }
        }
        return !gyro.settings.holdPasses
    }

    /** Turns the gyro on with [button]'s setup, or off again; a notice says which. */
    private fun toggleGyro(button: ComboButton) {
        if (gyro.on) {
            stopGyro()
            return notice.show(R.string.gyro_off)
        }
        if (!gyro.available) return notice.show(R.string.gyro_no_sensor)
        if (!padHelper.active) return notice.show(R.string.gyro_helper_missing)
        gyroHoldDevices.clear()
        gyro.calibration = prefs.gyroCalibration
        gyro.start(button, prefs.gyro(button))
        notice.show(R.string.gyro_on)
    }

    /** Calibration measures rest independently of the posture used to aim. */
    private fun calibrateGyro() {
        if (!gyro.available) return notice.show(R.string.gyro_no_sensor)
        notice.show(R.string.gyro_calibration_started)
        gyro.calibrate { result ->
            if (result != null) prefs.gyroCalibration = result
            notice.show(if (result != null) R.string.gyro_calibrated else R.string.gyro_calibration_failed)
        }
    }

    private fun stopGyro() {
        gyroHoldDevices.clear()
        gyro.stop()
    }

    private fun setTalking(on: Boolean) {
        recording.talk(on)
        recordIndicator.setTalking(on)
    }

    private fun unregisterScreenOff() {
        screenOffReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenOffReceiver = null
    }

    /** Finishes a running recording when the service goes away; the recorder saves the file itself. */
    private fun endRecording() {
        if (!::recording.isInitialized) return
        recording.release()
        pushToTalkKey = null
        pushToTalkDevices.clear()
        recordIndicator.dismiss()
        unregisterScreenOff()
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
                        if (saved) notice.show(R.string.screenshot_saved)
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
        notice.show(R.string.shoulder_action_unavailable)

    /** Runs [launch] on [button]'s screen, asking for the screen first when it is set to Ask. */
    private fun onLaunchScreen(button: ComboButton, launch: (Int) -> Unit) {
        if (prefs.shoulderDisplay(button) != Prefs.APP_SCREEN_ASK) return launch(shoulderDisplayId(button))
        val options = listOf(
            AppControlPrompt.Option(Prefs.DISPLAY_TOP, getString(R.string.level_target_top)),
            AppControlPrompt.Option(Prefs.DISPLAY_BOTTOM, getString(R.string.level_target_bottom)),
        )
        appControlPrompt.show(promptDisplay(), getString(R.string.launch_screen_prompt_title), options) { chosen ->
            launch(if (chosen == Prefs.DISPLAY_TOP) Display.DEFAULT_DISPLAY else bottomDisplay()?.displayId ?: Display.DEFAULT_DISPLAY)
        }
    }

    private fun shoulderDisplayId(button: ComboButton): Int =
        if (prefs.shoulderDisplay(button) == Prefs.DISPLAY_TOP) Display.DEFAULT_DISPLAY
        else bottomDisplay()?.displayId ?: Display.DEFAULT_DISPLAY

    /** Opens the narrowest system-owned panel available, with a safe fallback for OEM builds. */
    private fun openSystemSettings(action: String, fallback: String = Settings.ACTION_SETTINGS, data: Uri? = null) {
        val primary = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { this.data = data }
        val fallbackIntent = Intent(fallback).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(primary) }
            .recoverCatching { startActivity(fallbackIntent) }
            .onFailure { notice.show(R.string.shoulder_action_unavailable) }
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
        previewRecordIndicator = null
        macroRecorder = null
        macroRecordListener = null
        playingMacro = null
        endRecording()
        runCatching { unregisterReceiver(standbyReceiver) }
        shoulderKeys.clear()
        pendingFocusTargets.clear()
        pendingDpadFocus = null
        stopDpad()
        if (::appControlPrompt.isInitialized) appControlPrompt.dismiss()
        if (::panels.isInitialized) panels.close()
        if (::parking.isInitialized) parking.close()
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
        previewRecordIndicator = null
        macroRecorder = null
        macroRecordListener = null
        playingMacro = null
        endRecording()
        runCatching { unregisterReceiver(standbyReceiver) }
        shoulderKeys.clear()
        pendingFocusTargets.clear()
        pendingDpadFocus = null
        stopDpad()
        if (::appControlPrompt.isInitialized) appControlPrompt.dismiss()
        if (::panels.isInitialized) panels.close()
        if (::parking.isInitialized) parking.close()
        return super.onUnbind(intent)
    }

    /** Hands the pad back to Android; the helper must not outlive the service it reports to. */
    private fun stopDpad() {
        stopGyro()
        gyroOverride = null
        gyroCalibrate = null
        gyroRecenter = null
        stopLevelRepeat()
        stickClicks.values.forEach(StickClickMachine::reset)
        stickKeys.clear()
        toggleGesture.reset()
        backGesture.reset()
        homeGesture.reset()
        interceptionGate.clear()
        notice.dismiss()
        cancelActiveAppSwitch()
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
        updateDpadPassthrough(currentException())
        handler.removeCallbacks(panelStateRunnable)
        handler.postDelayed(panelStateRunnable, 400L)
        if (parking.parkedPackage != null) {
            handler.removeCallbacks(reclaimRunnable)
            handler.postDelayed(reclaimRunnable, PARKING_RECLAIM_SETTLE_MS)
        }
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

    /** Take the displayed panel before hiding it; task snapshots belong to its owner's Activity. */
    private fun capturePanel(panel: PanelWindow, then: (Bitmap?) -> Unit) = capturePanel(panel, true, then)

    private fun capturePanel(panel: PanelWindow, retry: Boolean, then: (Bitmap?) -> Unit) {
        if (!isConnected || overlay.isShowing ||
            windowsOnAllDisplays.get(panel.displayId).orEmpty().none { it.id == panel.id }) {
            then(null)
            return
        }
        val remaining = MIN_CAPTURE_GAP_MS - (SystemClock.uptimeMillis() - lastCapture)
        if (remaining > 0) {
            handler.postDelayed({ capturePanel(panel, retry, then) }, remaining)
            return
        }
        lastCapture = SystemClock.uptimeMillis()
        takeScreenshot(panel.displayId, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val stillVisible = !overlay.isShowing &&
                    windowsOnAllDisplays.get(panel.displayId).orEmpty().any { it.id == panel.id }
                Thread {
                    val image = runCatching {
                        if (!stillVisible) return@runCatching null
                        val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return@runCatching null
                        try {
                            val software = hardware.copy(Bitmap.Config.ARGB_8888, false) ?: return@runCatching null
                            try {
                                val width = minOf(THUMB_WIDTH, software.width)
                                val height = (software.height.toLong() * width / software.width).toInt().coerceAtLeast(1)
                                // Keep a distinct immutable bitmap even when no scaling is needed.
                                Bitmap.createScaledBitmap(software, width, height, true).let {
                                    if (it === software) software.copy(Bitmap.Config.ARGB_8888, false) else it
                                }
                            } finally { software.recycle() }
                        } finally { hardware.recycle() }
                    }.getOrNull()
                    buffer.close()
                    handler.post { if (isConnected) then(image) }
                }.apply { isDaemon = true }.start()
            }

            override fun onFailure(errorCode: Int) {
                if (retry && errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                    handler.postDelayed({ capturePanel(panel, false, then) }, MIN_CAPTURE_GAP_MS)
                } else then(null)
            }
        })
    }

    /** Stores a preview of the app in front on the bottom screen; [then] runs whether or not it worked. */
    private fun captureFrontApp(waitForGap: Boolean = false, then: (() -> Unit)? = null) {
        val display = bottomDisplay()
        val pkg = display?.let { source.topApp(it.displayId) }
        val now = SystemClock.uptimeMillis()
        if (display == null || pkg == null || overlay.isShowing || source.isCovered(display.displayId)) {
            then?.invoke(); return
        }
        val remaining = MIN_CAPTURE_GAP_MS - (now - lastCapture)
        if (remaining > 0) {
            if (waitForGap) handler.postDelayed({ captureFrontApp(waitForGap = true, then = then) }, remaining) else then?.invoke()
            return
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
        killOnly(listOf(pkg), setOf(bottom), reportMemory = true)
    }

    /** Close all: every app task on the bottom screen, or on both screens when [bothScreens] is set. */
    private fun closeAll(packages: List<String>, bothScreens: Boolean) {
        val displays = if (bothScreens) getSystemService(DisplayManager::class.java).displays
            .filterNot(FrontendParking::isParkingDisplay).map { it.displayId }.toSet()
        else setOfNotNull(bottomDisplay()?.displayId)
        killOnly(packages, displays, reportMemory = true)
    }

    private fun launchFromRecents(pkg: String, displayId: Int) = launchFromRecents(pkg, displayId, null)

    private fun launchFromRecents(pkg: String, displayId: Int, taskId: Int?) {
        if (!Shell.isPkg(pkg)) return
        if (overlay.isShowing) bottomSwitcherChose = true
        if (parking.parkedPackage == pkg) {
            // The ordinary mover would treat the parking display as a source screen needing a Home.
            prepareBottomLaunch(displayId) {
                parking.restore(displayId) { restored -> if (!restored) toastUnavailable() }
            }
            return
        }
        if (!canRelaunch(pkg)) {
            // The package may offer Home while this task is a normal app. Verify/activate
            // the actual task first, so a rejected Home task cannot hide a bottom panel.
            Thread {
                if (launchTask(pkg, displayId, taskId)) {
                    handler.post { if (isConnected) prepareBottomLaunch(displayId) {} }
                }
            }.apply { isDaemon = true }.start()
            return
        }
        prepareBottomLaunch(displayId) {
            Thread { launchTask(pkg, displayId, taskId) }.apply { isDaemon = true }.start()
        }
    }

    private fun prepareBottomLaunch(displayId: Int, then: () -> Unit) {
        val display = bottomDisplay()
        if (display?.displayId != displayId) { then(); return }
        val top = source.topApp(Display.DEFAULT_DISPLAY)
        val bottom = source.topApp(displayId)
        val park = { parking.prepareLaunch(display, top, bottom) { if (isConnected) preparePanels(display, then) } }
        // A parked task gets no system snapshot of its own; take its card preview while it is still visible.
        if (!parking.wouldPark(top, bottom)) return park()
        // Card callbacks run before dismiss(). Capture only after the overlay's surface is gone.
        val delay = if (overlay.isShowing) SWITCHER_SCREENSHOT_DELAY_MS else 0L
        handler.postDelayed({ captureFrontApp(waitForGap = true) { if (isConnected) park() } }, delay)
    }

    private fun preparePanels(display: Display, then: () -> Unit) {
        if (!panels.hasPanels && !source.isCovered(display.displayId)) { then(); return }
        // Card callbacks run before dismiss(). Capture only after the overlay's surface is gone.
        val delay = if (overlay.isShowing) SWITCHER_SCREENSHOT_DELAY_MS else 0L
        handler.postDelayed({
            if (isConnected) panels.prepareLaunch(display) {
                if (isConnected) then()
            }
        }, delay)
    }

    /** Closing AYN's card closes its panel the way AYN does it: a press of its own button. */
    private fun closeAynPanel() {
        panels.closeCovering { done ->
            aynButtonPresses++
            handler.removeCallbacks(aynButtonPressExpiry)
            handler.postDelayed(aynButtonPressExpiry, AYN_BUTTON_PRESS_EXPIRY_MS)
            Thread {
                val pressed = ControllerFocus.commandSucceeded(PService.exec(AYN_BUTTON_PRESS))
                handler.post {
                    if (!pressed) { aynButtonPresses = 0; toastUnavailable() }
                    done()
                }
            }.apply { isDaemon = true }.start()
        }
    }

    private fun restorePanel(id: Int) {
        if (overlay.isShowing) bottomSwitcherChose = true
        handler.postDelayed({
            if (isConnected) panels.show(id) { restored -> if (!restored) toastUnavailable() }
        }, SWITCHER_SCREENSHOT_DELAY_MS)
    }

    /** Only the unprivileged fallback lacks task identity; never relaunch a potential launcher there. */
    private fun canRelaunch(pkg: String): Boolean = Shell.isPkg(pkg) &&
        listOf(Intent.CATEGORY_HOME, Intent.CATEGORY_SECONDARY_HOME).none { category ->
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(pkg), 0).isNotEmpty()
        }

    /** Runs on a worker; swap moves must use one ordered worker rather than competing launches. */
    private fun launchTask(pkg: String, displayId: Int, taskId: Int?, allowRelaunch: Boolean = true): Boolean {
        val args = listOfNotNull(pkg, displayId.toString(), taskId?.toString()).toTypedArray()
        val result = PService.runEntryPoint(this, "RecentsTaskLauncher", *args)
        Log.d(TAG, "Recents launch: $result")
        val launched = result?.startsWith("OK ") == true
        if (!launched) {
            if (allowRelaunch && TaskMovePolicy.mayRelaunch(result, canRelaunch(pkg))) {
                handler.post { runCatching { launchOn(pkg, displayId) }.onFailure { Log.e(TAG, "Recents launch failed", it) } }
                return true
            }
            handler.post { notice.show(R.string.swap_unavailable, long = true) }
        }
        return launched
    }

    /** A normal app can only kill background processes, so leave the app first. With AYN's root
     *  service reachable ([PService]) the task is also removed from Recents and the app force-stopped;
     *  if that fails, the unprivileged kill below still runs. Our own package only loses its tasks:
     *  this process hosts the service, so stopping it would end every gesture. With [reportMemory]
     *  the released memory is shown afterwards, as the stock launcher's Clear all does. */
    private fun killOnly(
        closing: List<String>,
        displays: Set<Int>,
        reportMemory: Boolean = false,
        afterwards: (() -> Unit)? = null,
    ) {
        val activityManager = getSystemService(ActivityManager::class.java)
        // Measured before the app leaves the screen, so the whole release is counted.
        val availableBefore = if (reportMemory) RecentsMemory.read(this).available else null
        val closesOwn = packageName in closing
        val packages = closing - packageName
        if (::parking.isInitialized) packages.forEach(parking::forget)
        packages.forEach { pkg ->
            displays.forEach { d -> if (source.topApp(d) == pkg) goHome(d, dismissSwitcher = false) }
        }
        val rootable = if (PService.cachedAvailable()) packages.filter(Shell::isPkg) else emptyList()
        handler.postDelayed({
            if (closesOwn) activityManager.appTasks.forEach { it.finishAndRemoveTask() }
            if (rootable.isEmpty()) {
                packages.forEach { activityManager.killBackgroundProcesses(it) }
                availableBefore?.let(::reportReleasedMemory)
                afterwards?.let { handler.postDelayed(it, RESTART_DELAY_MS) }
                return@postDelayed
            }
            Thread {
                val removed = PService.runEntryPoint(this, "TaskRemover", rootable.joinToString(","))
                val stopped = rootable.map { PService.forceStop(it) }
                if (removed?.startsWith("OK") != true || !stopped.all { it }) {
                    handler.post { packages.forEach { activityManager.killBackgroundProcesses(it) } }
                }
                availableBefore?.let { before -> handler.post { reportReleasedMemory(before) } }
                afterwards?.let { handler.postDelayed(it, RESTART_DELAY_MS) }
            }.start()
        }, 500)
    }

    /** The kernel hands memory back with a short lag; the stock launcher measures after the same wait. */
    private fun reportReleasedMemory(availableBefore: Long) {
        handler.postDelayed({
            val available = RecentsMemory.read(this).available
            val released = RecentsMemory.released(availableBefore, available)
            fun size(bytes: Long) = Formatter.formatShortFileSize(this, bytes).uppercase()
            notice.show(
                if (released > 0) getString(R.string.memory_released, size(released), size(available))
                else getString(R.string.memory_nothing_released),
            )
        }, MEMORY_REPORT_DELAY_MS)
    }

    /** Move the two captured foreground tasks; the first move must not change the second target. */
    // The Back gestures are assignable: the machine reports which gesture fired, the bound action runs.
    override fun swapScreens() = runBackGesture(BackGestureButton.HOLD)
    override fun openTopRecents() = runBackGesture(BackGestureButton.TAP_HOLD)
    override fun showSwitcher() = runBackGesture(BackGestureButton.DOUBLE)

    private fun runBackGesture(gesture: ComboButton) {
        val action = prefs.shoulderAction(gesture)
        dlog("BACK   gesture $gesture action=${action.ifEmpty { "(none)" }}")
        if (action.isNotEmpty()) runComboAction(gesture, action)
    }

    private fun swapScreensNow() {
        cancelBottomSwitcherOpen()
        val display = bottomDisplay() ?: return
        val bottom = display.displayId
        val top = Display.DEFAULT_DISPLAY
        overlay.dismiss()
        panels.refresh(display) {
            if (!isConnected) return@refresh
            val exceptions = prefs.appExceptions
            val topPackage = source.topApp(top)
            val bottomPackage = source.topApp(bottom)
            val topApp = TaskMovePolicy.swapParticipant(topPackage, exceptions)
            val bottomApp = TaskMovePolicy.swapParticipant(bottomPackage, exceptions)
            if (topApp == null && bottomApp == null &&
                (topPackage != null || bottomPackage != null)) {
                holdSwapBlocked()
                return@refresh
            }
            if (panels.preventsSwap(topApp, bottomApp)) {
                notice.show(R.string.panel_swap_unavailable, long = true)
                return@refresh
            }
            Thread {
                val states = RecentsTaskState.parse(PService.runEntryPoint(this, "RecentsStateReader", "include-excluded"))
                val moves = TaskMovePolicy.planSwap(topApp, bottomApp, top, bottom, states, ::canRelaunch)
                if (moves.isNullOrEmpty()) {
                    handler.post { notice.show(R.string.swap_unavailable, long = true) }
                    return@Thread
                }
                for (move in moves) {
                    if (!launchTask(move.packageName, move.targetDisplay, move.taskId)) break
                }
            }.apply { isDaemon = true }.start()
        }
    }

    private fun launchOn(pkg: String, displayId: Int) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        prepareBottomLaunch(displayId) {
            startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
        }
    }

    /**
     * Opens the system's Recents/Overview (task switcher) on the top screen - the same screen
     * a dedicated Overview button or the recents gesture would show. GLOBAL_ACTION_RECENTS
     * always targets the default display, which is exactly the top screen in this setup.
     */
    private fun openTopRecentsNow() {
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
        panels.switcherClosed(bottomSwitcherChose)
        bottomSwitcherChose = false
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
            val states = RecentsTaskState.parse(PService.runEntryPoint(this, "RecentsStateReader", "include-excluded"))
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
        // A parked frontend's newest task is usually its top-screen activity; keep the captured bottom preview.
        val packages = overlay.previewPackages() - setOfNotNull(parking.parkedPackage)
        val width = overlay.previewWidth().coerceIn(1, 1024)
        val entries = packages.mapNotNull { overlay.taskFor(it, states) }.filter { it.taskId >= 0 }
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
        if (!active) {
            // A lost pad stops a macro without any Back press that could claim the cancel.
            playingMacro = null
            macroCancelled = null
            macroStopAwaitsBack = false
        }
        if (!interceptionEnabled || !::overlay.isInitialized || !prefs.switcherControllerEnabled ||
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

    override fun onSingleTap() = runSingleTap(BackGestureButton.SINGLE)

    private fun runSingleTap(gesture: ComboButton) {
        if (bottomSwitcherOpening) { cancelBottomSwitcherOpen(); return }
        if (overlay.isShowing) { dlog("ACTION onSingleTap: overlay was showing -> dismiss only"); overlay.dismiss(); return }
        runBackGesture(gesture)
    }

    private fun goHome(displayId: Int? = null, dismissSwitcher: Boolean = true, component: ComponentName? = null) {
        val target = displayId ?: bottomDisplay()?.displayId ?: return
        if (dismissSwitcher) { cancelBottomSwitcherOpen(); overlay.dismiss() }
        val category = if (target == Display.DEFAULT_DISPLAY) Intent.CATEGORY_HOME else Intent.CATEGORY_SECONDARY_HOME
        val home = Intent(Intent.ACTION_MAIN).addCategory(category).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        component?.let { home.component = it }
        prepareBottomLaunch(target) {
            startActivity(home, ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle())
        }
    }

    private fun showSwitcherNow() {
        cancelTopRecentsLaunch()
        val display = bottomDisplay() ?: return
        if (bottomSwitcherOpening) { cancelBottomSwitcherOpen(); return }
        if (overlay.isShowing) { overlay.dismiss(); return }
        bottomSwitcherOpening = true
        bottomSwitcherChose = false
        parking.verify()
        val session = ++bottomStateSession
        panels.prepareSwitcher(display) { captureFrontApp {
            Thread {
                val states = RecentsTaskState.parse(PService.runEntryPoint(this, "RecentsStateReader", "include-excluded"))
                handler.post {
                    if (session != bottomStateSession || !isConnected) return@post
                    bottomSwitcherOpening = false
                    if (states != null) source.retainRecentTasks(states)
                    runCatching { overlay.toggle(display) }.onFailure { Log.e(TAG, "toggle failed", it) }
                }
            }.apply { isDaemon = true }.start()
        } }
    }

    private fun cancelBottomSwitcherOpen() {
        bottomSwitcherOpening = false
        bottomStateSession++
    }

    /** The AYN Thor's second screen is the non-default internal display (id 4, "Screen-2" here).
     *  The parking display has a higher id but is never a screen. */
    private fun bottomDisplay(): Display? =
        getSystemService(DisplayManager::class.java).displays
            .filter { it.displayId != Display.DEFAULT_DISPLAY && !FrontendParking.isParkingDisplay(it) }
            .maxByOrNull { it.displayId }

    companion object {
        @Volatile
        var isConnected = false
            private set

        /** Lets the settings screen preview the focus pictogram, which only the service may draw. */
        @Volatile
        var previewFocusIndicator: ((top: Boolean, position: String?, visibleMs: Long) -> Unit)? = null
        /** What the gyro does now, and who wants to know: the settings preview shows it live. */
        @Volatile var gyroStatus = GyroStatus()
        var gyroStatusListener: ((GyroStatus) -> Unit)? = null
        var gyroCalibrate: (() -> Unit)? = null
        var gyroRecenter: (() -> Unit)? = null
        /** Debug builds' device test only: fixed rotation rates (rad/s) instead of the sensor's, or null. */
        var gyroOverride: ((FloatArray?) -> Unit)? = null
            private set

        /** Lets the macro editor record through the helper, which only the service talks to. */
        @Volatile
        var macroRecorder: MacroRecorderBridge? = null
            private set

        /** Lets the settings screen show where the recording dot will appear. */
        @Volatile
        var previewRecordIndicator: ((position: String) -> Unit)? = null
            private set

        const val TAG = "BackButtonHero"
        const val CAPTURE_DELAY_MS = 800L
        const val MIN_CAPTURE_GAP_MS = 1100L
        const val SWITCHER_SCREENSHOT_DELAY_MS = 100L
        const val PARKING_RECLAIM_SETTLE_MS = 300L
        const val SYSTEM_SCREENSHOT_CONNECT_MS = 2_000L
        const val AYN_BUTTON_PRESS_EXPIRY_MS = 2_000L
        // AYN's button is KEY_F24 (194) on the gpio-keys device, found by name since event numbers can
        // vary. Held about as long as a real tap, so AYN never mistakes it for its screen-off hold.
        const val AYN_BUTTON_PRESS = "for d in /sys/class/input/event*; do " +
            "if [ \"$(cat \$d/device/name)\" = gpio-keys ]; then e=/dev/input/\${d##*/}; " +
            "sendevent \$e 1 194 1 && sendevent \$e 0 0 0 && sleep 0.1 && sendevent \$e 1 194 0 && sendevent \$e 0 0 0 && echo OK; break; fi; done"
        const val COMBINED_DISPLAY_SHIFT = 12
        const val THUMB_WIDTH = 480
        /** Minimum time to wait for a second, held-down press before firing a plain single tap. */
        const val TAP_HOLD_GRACE_MS = 600L
        const val NATIVE_BACK_SETTLE_MS = 1_000L
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
        const val POWER_MENU_FOCUS_RETRY_MS = 80L
        /** After a restart's kill: the force-stop must have settled, or the launch could land in the dying process. */
        const val RESTART_DELAY_MS = 400L
        const val MEMORY_REPORT_DELAY_MS = 600L
        const val POWER_MENU_FOCUS_ATTEMPTS = 20
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

/** The service's side of macro recording, for the editor in the settings screen. */
interface MacroRecorderBridge {
    /** Starts recording; every input arrives in [onInput] on the main thread until [stop]. */
    fun start(onInput: (List<String>) -> Unit)
    fun stop()
    /** Whether the helper holds the pad, so a recording actually receives input. */
    val ready: Boolean
}
