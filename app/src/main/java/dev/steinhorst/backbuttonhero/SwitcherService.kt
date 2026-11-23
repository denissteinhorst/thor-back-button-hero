package dev.steinhorst.backbuttonhero

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.steinhorst.backbuttonhero.actions.AppControlActions
import dev.steinhorst.backbuttonhero.actions.AppControlPrompt
import dev.steinhorst.backbuttonhero.actions.AutoControllerFocus
import dev.steinhorst.backbuttonhero.actions.Ayn
import dev.steinhorst.backbuttonhero.actions.AynControls
import dev.steinhorst.backbuttonhero.actions.ControllerFocus
import dev.steinhorst.backbuttonhero.actions.LastAppAction
import dev.steinhorst.backbuttonhero.actions.PowerMenu
import dev.steinhorst.backbuttonhero.actions.SystemActions
import dev.steinhorst.backbuttonhero.capture.PushToTalk
import dev.steinhorst.backbuttonhero.capture.RecordingIndicatorOverlay
import dev.steinhorst.backbuttonhero.capture.ScreenRecording
import dev.steinhorst.backbuttonhero.capture.ScreenshotSaver
import dev.steinhorst.backbuttonhero.core.AccessibilityAppSource
import dev.steinhorst.backbuttonhero.core.Displays
import dev.steinhorst.backbuttonhero.core.PService
import dev.steinhorst.backbuttonhero.core.Shell
import dev.steinhorst.backbuttonhero.input.AndroidButton
import dev.steinhorst.backbuttonhero.input.BackGestureButton
import dev.steinhorst.backbuttonhero.input.BackGestureMachine
import dev.steinhorst.backbuttonhero.input.ComboButton
import dev.steinhorst.backbuttonhero.input.ComboRouter
import dev.steinhorst.backbuttonhero.input.DirectionalRouter
import dev.steinhorst.backbuttonhero.input.StickClickRouter
import dev.steinhorst.backbuttonhero.input.DpadDirection
import dev.steinhorst.backbuttonhero.input.FaceButton
import dev.steinhorst.backbuttonhero.input.GestureActions
import dev.steinhorst.backbuttonhero.input.GestureClock
import dev.steinhorst.backbuttonhero.input.GestureScheduler
import dev.steinhorst.backbuttonhero.input.HomeAsBack
import dev.steinhorst.backbuttonhero.input.HomeLayerButton
import dev.steinhorst.backbuttonhero.input.KeyInterceptionGate
import dev.steinhorst.backbuttonhero.input.ServiceToggleGesture
import dev.steinhorst.backbuttonhero.input.ShoulderButton
import dev.steinhorst.backbuttonhero.input.Stick
import dev.steinhorst.backbuttonhero.input.StickButton
import dev.steinhorst.backbuttonhero.input.StickClick
import dev.steinhorst.backbuttonhero.input.StickClickButton
import dev.steinhorst.backbuttonhero.input.base
import dev.steinhorst.backbuttonhero.macro.MacroPlayback
import dev.steinhorst.backbuttonhero.overlay.FocusIndicatorOverlay
import dev.steinhorst.backbuttonhero.overlay.NoticeOverlay
import dev.steinhorst.backbuttonhero.pad.DpadOverlay
import dev.steinhorst.backbuttonhero.pad.GyroActions
import dev.steinhorst.backbuttonhero.pad.GyroControl
import dev.steinhorst.backbuttonhero.pad.GyroStatus
import dev.steinhorst.backbuttonhero.pad.LevelControl
import dev.steinhorst.backbuttonhero.pad.LevelRepeater
import dev.steinhorst.backbuttonhero.pad.PadHelper
import dev.steinhorst.backbuttonhero.pad.PadNavigationMode
import dev.steinhorst.backbuttonhero.panels.BottomPanels
import dev.steinhorst.backbuttonhero.panels.PanelWindow
import dev.steinhorst.backbuttonhero.prefs.Action
import dev.steinhorst.backbuttonhero.prefs.ActionCodec
import dev.steinhorst.backbuttonhero.prefs.AppException
import dev.steinhorst.backbuttonhero.prefs.ExceptionControl
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.recents.FrontendParking
import dev.steinhorst.backbuttonhero.recents.TaskOps
import dev.steinhorst.backbuttonhero.recents.TopRecentsLauncher
import dev.steinhorst.backbuttonhero.recents.RecentsSelection
import dev.steinhorst.backbuttonhero.recents.BottomSwitcherController
import dev.steinhorst.backbuttonhero.recents.RecentsTaskState
import dev.steinhorst.backbuttonhero.recents.SwapCoordinator
import dev.steinhorst.backbuttonhero.recents.SwitcherOverlay
import dev.steinhorst.backbuttonhero.recents.TopRecentsNavigation
import dev.steinhorst.backbuttonhero.recents.TopRecentsState
import dev.steinhorst.backbuttonhero.recents.WindowTracker

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
    private val gyroActions by lazy { GyroActions({ gyro }, prefs, notice, { padHelper.active }, { modifierDown }) }
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
    private val system by lazy { SystemActions(this, notice) }
    private val autoControllerFocus by lazy { AutoControllerFocus(this) }
    private val comboRouter = ComboRouter(object : ComboRouter.Host {
        override val modifierDown get() = this@SwitcherService.modifierDown
        override fun action(button: ComboButton) = prefs.shoulderAction(button)
        override fun allows(button: ComboButton) = allowsCombo(button)
        override fun markComboFired() = heldGesture.markComboFired()
        override fun log(message: String) = dlog(message)
        override fun runCombo(button: ComboButton, action: String) = runComboAction(button, action)
        override fun showFocusIndicator(button: ComboButton) = this@SwitcherService.showFocusIndicator(button)
        override fun runReleased(button: ComboButton) = runReleasedCombo(button)
    })
    private val directionalRouter = DirectionalRouter(object : DirectionalRouter.Host {
        override val modifierDown get() = this@SwitcherService.modifierDown
        override val stickInputBlocked get() = !interceptionEnabled || overlay.isShowing || topRecentsControllerActive
        override val overlayAttached get() = dpadOverlay.attached
        override val repeatingButton get() = this@SwitcherService.repeatingButton
        override fun layered(button: ComboButton) = this@SwitcherService.layered(button)
        override fun action(button: ComboButton) = prefs.shoulderAction(button)
        override fun allows(button: ComboButton) = allowsCombo(button)
        override fun markComboFired() = heldGesture.markComboFired()
        override fun log(message: String) = dlog(message)
        override fun showFocusIndicator(button: ComboButton) = this@SwitcherService.showFocusIndicator(button)
        override fun runCombo(button: ComboButton, action: String) = runComboAction(button, action)
        override fun startLevelRepeat(button: ComboButton, action: String) = this@SwitcherService.startLevelRepeat(button, action)
        override fun stopLevelRepeat() = this@SwitcherService.stopLevelRepeat()
        override fun runReleased(button: ComboButton) = runReleasedCombo(button)
    })
    private val stickClickRouter by lazy {
        StickClickRouter(gestureScheduler, gestureClock, object : StickClickRouter.Host {
            override val interceptionEnabled get() = this@SwitcherService.interceptionEnabled
            override val modifierDown get() = this@SwitcherService.modifierDown
            override val homeDown get() = homeGesture.backDown
            override val repeatingButton get() = this@SwitcherService.repeatingButton
            override fun allows(button: ComboButton) = allowsCombo(button)
            override fun markComboFired() = heldGesture.markComboFired()
            override fun stopLevelRepeat() = this@SwitcherService.stopLevelRepeat()
            override fun doubleMs(stick: Stick, home: Boolean) = prefs.stickDoubleMs(stick, home)
            override fun tapHoldMs(stick: Stick, home: Boolean) = prefs.stickTapHoldMs(stick, home)
            override fun action(button: ComboButton) = prefs.shoulderAction(button)
            override fun fire(button: ComboButton) = runStickClick(button)
        })
    }
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
    private val bottomSwitcher: BottomSwitcherController by lazy {
        BottomSwitcherController(this, handler, object : BottomSwitcherController.Host {
            override val connected get() = isConnected
            override val overlay get() = this@SwitcherService.overlay
            override val parkedPackage get() = parking.parkedPackage
            override fun bottomDisplay() = this@SwitcherService.bottomDisplay()
            override fun cancelTopRecentsLaunch() = this@SwitcherService.cancelTopRecentsLaunch()
            override fun verifyParking() = parking.verify()
            override fun prepareSwitcher(display: Display, then: () -> Unit) { panels.prepareSwitcher(display, then) }
            override fun captureFrontApp(then: () -> Unit) = this@SwitcherService.captureFrontApp(then = then)
            override fun retainRecentTasks(states: List<RecentsTaskState>) = source.retainRecentTasks(states)
            override fun switcherClosed(chose: Boolean) { panels.switcherClosed(chose) }
        })
    }
    private var aynButtonDeviceId: Int? = null
    // Our own root press of AYN's button must reach AYN instead of returning its panel.
    private var aynButtonPresses = 0
    private var aynButtonPassing: Int? = null
    private val aynButtonPressExpiry = Runnable { aynButtonPresses = 0 }
    private val windowTracker by lazy { WindowTracker(handler, object : WindowTracker.Host {
        override fun recordWindows() {
            source.record(Display.DEFAULT_DISPLAY)
            bottomDisplay()?.let { source.record(it.displayId) }
            updateDpadPassthrough(currentException())
        }
        override fun refreshPanels() {
            if (::panels.isInitialized) panels.refresh(bottomDisplay())
        }
        override fun parked() = parking.parkedPackage != null
        override fun reclaimParked() {
            bottomDisplay()?.let { parking.reclaimed(it, source.topApp(it.displayId)) }
        }
        override fun topRecentsActive() = topRecentsControllerActive
        override fun checkTopRecents() = scheduleTopRecentsStateCheck(TOP_RECENTS_EVENT_SETTLE_MS)
        override fun capture() = captureFrontApp()
    }) }
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
    private val topLaunch: TopRecentsLauncher by lazy {
        TopRecentsLauncher(this, handler, object : TopRecentsLauncher.Host {
            override val connected get() = isConnected
            override val controllerEnabled get() = prefs.switcherControllerEnabled
            override fun leaveSwitcher() {
                cancelBottomSwitcherOpen()
                overlay.dismiss()
            }
            override fun openNative() = openNativeTopRecents()
            override fun launchFromRecents(pkg: String, displayId: Int, taskId: Int) =
                this@SwitcherService.launchFromRecents(pkg, displayId, taskId)
            override fun selected() = restoreRecentsFocus()
            override fun scheduleStateCheck(delayMs: Long) = scheduleTopRecentsStateCheck(delayMs)
            override fun trace(message: String) = dlog(message)
        })
    }
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
    private val macroPlayback = MacroPlayback(object : MacroPlayback.Host {
        override fun play(button: ComboButton, id: Int): Boolean? {
            val macro = prefs.macro(button) ?: return null
            if (!padHelper.active || !padHelper.playMacro(id, macro.events(controllerFlipped()))) return false
            dlog("MACRO  play ${button.prefKey} steps=${macro.steps.size}")
            return true
        }
        override fun stopHelper() = padHelper.stopMacro()
        override fun helperMissing() = notice.show(R.string.macro_helper_missing)
    })
    /** The macro editor's sink while it records; the helper then keeps the pad's input from the app. */
    private var macroRecordListener: ((List<String>) -> Unit)? = null
    /** True while a Home press standing in for Back runs through the Back path. */
    private var translatingHome = false
    private val prefs by lazy { Prefs(this) }
    private val lastApp by lazy {
        LastAppAction(this, handler, notice, object : LastAppAction.Host {
            override fun controllerDisplayId() = this@SwitcherService.controllerDisplayId()
            override fun appsOnDisplay(displayId: Int) = source.appsOnDisplay(displayId).map { it.packageName }
            override fun topApp(displayId: Int) = source.topApp(displayId)
            override fun forget(packageName: String) = source.forget(packageName)
            override fun launchOn(packageName: String, displayId: Int) = this@SwitcherService.launchOn(packageName, displayId)
            override fun unavailable() = toastUnavailable()
        })
    }
    private val taskOps by lazy {
        TaskOps(this, handler, notice, object : TaskOps.Host {
            override fun topApp(displayId: Int) = source.topApp(displayId)
            override fun forgetParked(packageName: String) { if (::parking.isInitialized) parking.forget(packageName) }
            override fun goHome(displayId: Int) = this@SwitcherService.goHome(displayId, dismissSwitcher = false)
            override fun launchOn(packageName: String, displayId: Int) = this@SwitcherService.launchOn(packageName, displayId)
        })
    }
    private val swap by lazy {
        SwapCoordinator(this, handler, notice, object : SwapCoordinator.Host {
            override val connected get() = isConnected
            override val interceptionEnabled get() = this@SwitcherService.interceptionEnabled
            override val appExceptions get() = prefs.appExceptions
            override fun bottomDisplay() = this@SwitcherService.bottomDisplay()
            override fun topApp(displayId: Int) = source.topApp(displayId)
            override fun cancelSwitcherOpen() = cancelBottomSwitcherOpen()
            override fun dismissSwitcher() = overlay.dismiss()
            override fun refreshPanels(display: Display, then: () -> Unit) { panels.refresh(display, then) }
            override fun panelsPreventSwap(topPackage: String?, bottomPackage: String?) = panels.preventsSwap(topPackage, bottomPackage)
            override fun prepareBottomLaunch(displayId: Int, then: () -> Unit) = this@SwitcherService.prepareBottomLaunch(displayId, then)
            override fun launchTask(pkg: String, displayId: Int, taskId: Int?, allowRelaunch: Boolean) =
                this@SwitcherService.launchTask(pkg, displayId, taskId, allowRelaunch)
            override fun canRelaunch(pkg: String) = this@SwitcherService.canRelaunch(pkg)
            override fun holdSwapBlocked() = this@SwitcherService.holdSwapBlocked()
            override fun unavailable() = toastUnavailable()
            override fun focusRoute() = controllerFocus.currentTarget()
            override fun switchAppFollowFocus(button: ComboButton) = prefs.switchAppFollowFocus(button)
            override fun runFocus(target: String, onComplete: (Boolean) -> Unit) = controllerFocus.run(target, onComplete)
            override fun runAutoFocus(displayId: Int, then: (Boolean) -> Unit) = autoControllerFocus.run(displayId, then)
            override fun dismissAutoFocus() = autoControllerFocus.dismiss()
        })
    }
    private val appControl by lazy {
        AppControlActions(this, prefs, notice, object : AppControlActions.Host {
            override fun bottomDisplayId() = bottomDisplay()?.displayId
            override fun topApp(displayId: Int) = source.topApp(displayId)
            override fun appsOnDisplay(displayId: Int) = source.appsOnDisplay(displayId).map { it.packageName }
            override fun forget(packageName: String) = source.forget(packageName)
            override fun prompt(title: String, options: List<AppControlPrompt.Option>, onChoice: (String) -> Unit) =
                appControlPrompt.show(promptDisplay(), title, options, onChoice)
            override fun kill(packages: List<String>, displays: Set<Int>, reportMemory: Boolean, afterwards: (() -> Unit)?) =
                killOnly(packages, displays, reportMemory, afterwards)
            override fun launchOn(packageName: String, displayId: Int) = this@SwitcherService.launchOn(packageName, displayId)
            override fun goHome(displayId: Int) = this@SwitcherService.goHome(displayId, dismissSwitcher = false)
            override fun lockScreen() {
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            }
        })
    }
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
            onMacroEnd = macroPlayback::ended,
        )
        gyroOverride = { rates -> handler.post { gyro.override = rates } }
        gyroRecenter = { gyro.recenter() }
        gyroCalibrate = { gyroActions.calibrate() }
        dpadOverlay = DpadOverlay(this, ::handleDpad, ::handleDpadRelease, ::handleStick)
        appControlPrompt = AppControlPrompt(this) { it == confirmKeyCode() }
        prefs.registerListener(prefsListener)
        runCatching { unregisterReceiver(standbyReceiver) }
        registerReceiver(standbyReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) })
        updatePadHelper()
        windowTracker.connected()
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
            if (prefs.shoulderAction(gyroButton) == Prefs.SHOULDER_GYRO) gyro.update(prefs.gyro(gyroButton)) else gyroActions.stop()
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
        stickClickRouter.forgetLayers()
        comboRouter.clear()
        directionalRouter.forgetArmed()
        stopLevelRepeat()
        stickClickRouter.reset()
        switcherBackDown = false
        switcherBackDeviceId = Int.MIN_VALUE
        topRecentsBackClosePending = false
        recentsConfirmKeys.clear()
        nativeBackDevices.clear()
        gyroActions.releaseHold()
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
        if (event.action != KeyEvent.ACTION_DOWN) return comboRouter.isDown(event.keyCode)
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
                macroPlayback.backDown()
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
                if (overlayWasAttached) directionalRouter.overlayDetached()
            }
            return pressGesture.handleBack(event)
        }
        if (handlePushToTalk(event)) return true
        if (gyroActions.handleHold(event)) return true
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
                val baseline = topLaunch.baseline
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
            if (topLaunch.selectionPending || topRecentsBackClosePending) return
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
        directionalRouter.press(direction)
    }

    /** Back + analog stick, from [PadHelper] or [DpadOverlay]; a null [direction] is the stick returning to neutral. */
    private fun handleStick(stick: Stick, direction: DpadDirection?) = directionalRouter.stick(stick, direction)

    /** A held stepping control (volume, brightness) keeps stepping, faster the longer it is held. */
    private fun startLevelRepeat(button: ComboButton, action: String) {
        if (!Prefs.isLevelAction(action)) return
        repeatingButton = button
        levelRepeater.start()
    }

    private fun handleStickClick(stick: Stick, event: KeyEvent): Boolean =
        stickClickRouter.handle(stick, event.action, event.keyCode, event.deviceId, event.repeatCount)

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

    private fun handleDpadRelease(horizontal: Boolean) = directionalRouter.release(horizontal)

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

    private fun handleShoulder(event: KeyEvent, button: ComboButton): Boolean =
        comboRouter.handle(event.action, event.keyCode, event.deviceId, event.repeatCount, button)

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

    private fun startMacroRecording(onInput: (List<String>) -> Unit) {
        if (::padHelper.isInitialized && macroPlayback.playing != null) macroPlayback.stop()
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
        when (ActionCodec.decode(action)) {
            Action.Builtin.FLICK_LEFT -> navigationFlick(left = true)
            Action.Builtin.FLICK_RIGHT -> navigationFlick(left = false)
            Action.Builtin.VOLUME_UP, Action.Builtin.VOLUME_DOWN,
            Action.Builtin.BRIGHTNESS_UP, Action.Builtin.BRIGHTNESS_DOWN -> levels.run(action, prefs.levelTarget(button))
            Action.Builtin.CONTROLLER_FOCUS -> runControllerFocus(button)
            Action.Builtin.SWITCH_ACTIVE_APP -> switchActiveApp(button)
            Action.Builtin.APP_CONTROL -> appControl.runAppControl(button)
            Action.Builtin.CLOSE_APPS -> appControl.runCloseApps(button, keepForeground = false)
            Action.Builtin.CLOSE_BACKGROUND_APPS -> appControl.runCloseApps(button, keepForeground = true)
            Action.Builtin.POWER_MENU -> showPowerMenu()
            Action.Builtin.STANDBY -> appControl.runStandby(button)
            Action.Builtin.VOLUME_MUTE -> levels.toggleMute(prefs.levelTarget(button))
            Action.Builtin.RECORD_VIDEO -> toggleRecording(button)
            Action.Builtin.MACRO -> macroPlayback.run(button)
            Action.Builtin.MEDIA_PLAY_PAUSE -> system.sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            Action.Builtin.MEDIA_NEXT -> system.sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            Action.Builtin.MEDIA_PREVIOUS -> system.sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            Action.Builtin.STATUS_OVERLAY -> system.showStatus()
            Action.Builtin.GYRO -> gyroActions.toggle(button)
            Action.Builtin.GYRO_CALIBRATE -> gyroActions.calibrate()
            is Action.AynChoice, is Action.AynSwitch -> runAynAction(button, action)
            Action.Builtin.HOME -> onLaunchScreen(button, ::goHome)
            Action.Builtin.APP_ASK -> chooseApp(button, favorites = false)
            Action.Builtin.APP_FAVORITES -> chooseApp(button, favorites = true)
            Action.Builtin.RECENTS -> openTopRecentsNow()
            Action.Builtin.SWITCHER -> showSwitcherNow()
            Action.Builtin.SWAP_APPS -> swapScreensNow()
            Action.Builtin.LAST_APP -> lastApp.run()
            Action.Builtin.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            Action.Builtin.SCREENSHOT -> screenshot(prefs.levelTarget(button))
            Action.Builtin.QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            Action.Builtin.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            Action.Builtin.INTERNET -> system.openSystemSettings(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, Settings.ACTION_WIRELESS_SETTINGS)
            Action.Builtin.BLUETOOTH -> system.openSystemSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
            Action.Builtin.NIGHT_LIGHT -> system.openSystemSettings(Settings.ACTION_NIGHT_DISPLAY_SETTINGS, Settings.ACTION_DISPLAY_SETTINGS)
            Action.Builtin.EXTRA_DIM -> system.openSystemSettings(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ACTION_EXTRA_DIM_SETTINGS else Settings.ACTION_DISPLAY_SETTINGS,
                Settings.ACTION_DISPLAY_SETTINGS,
            )
            Action.Builtin.DISPLAY_SETTINGS -> system.openSystemSettings(Settings.ACTION_DISPLAY_SETTINGS)
            Action.Builtin.SOUND_SETTINGS -> system.openSystemSettings(Settings.ACTION_SOUND_SETTINGS)
            Action.Builtin.LOCATION_SETTINGS -> system.openSystemSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            Action.Builtin.CAST_SETTINGS -> system.openSystemSettings(Settings.ACTION_CAST_SETTINGS)
            Action.Builtin.DND_SETTINGS -> system.openSystemSettings(ACTION_ZEN_MODE_SETTINGS, Settings.ACTION_SOUND_SETTINGS)
            Action.Builtin.BATTERY_SAVER -> system.openSystemSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            // An app, and also an empty or unknown value: both reach the launch path as before.
            is Action.LaunchApp, is Action.Unknown, Action.Unassigned -> onLaunchScreen(button) { displayId ->
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

    private fun cancelActiveAppSwitch() = swap.cancel()

    private fun switchActiveApp(button: ComboButton) = swap.switchActiveApp(button)

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

    /** Media keys go to whichever session plays, as a headset button would; no root needed. */
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
        macroPlayback.forget()
        endRecording()
        runCatching { unregisterReceiver(standbyReceiver) }
        comboRouter.clear()
        directionalRouter.forgetArmed()
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
        macroPlayback.forget()
        endRecording()
        runCatching { unregisterReceiver(standbyReceiver) }
        comboRouter.clear()
        directionalRouter.forgetArmed()
        stopDpad()
        if (::appControlPrompt.isInitialized) appControlPrompt.dismiss()
        if (::panels.isInitialized) panels.close()
        if (::parking.isInitialized) parking.close()
        return super.onUnbind(intent)
    }

    /** Hands the pad back to Android; the helper must not outlive the service it reports to. */
    private fun stopDpad() {
        gyroActions.stop()
        gyroOverride = null
        gyroCalibrate = null
        gyroRecenter = null
        stopLevelRepeat()
        stickClickRouter.reset()
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
            if (event.packageName == "com.android.launcher3" && topLaunch.baseline != null) {
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
        windowTracker.windowsChanged()
    }

    private fun topRecentsLostFocus(): Boolean {
        val root = windowsOnAllDisplays.get(Display.DEFAULT_DISPLAY).orEmpty()
            .mapNotNull { it.root }.firstOrNull { it.packageName == "com.android.launcher3" }
            ?: return false // Window events can trail the authoritative focus snapshot.
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return true
        val card = node.collectionItemInfo != null && node.parent?.viewIdResourceName == "com.android.launcher3:id/overview_panel"
        return !card && node.viewIdResourceName !in setOf("com.android.launcher3:id/action_screenshot", "com.android.launcher3:id/clear_all_button")
    }

    private fun selectTopRecentsCard(description: String) = topLaunch.selectCard(description)

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
        bottomSwitcher.markChosen()
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
        bottomSwitcher.markChosen()
        handler.postDelayed({
            if (isConnected) panels.show(id) { restored -> if (!restored) toastUnavailable() }
        }, SWITCHER_SCREENSHOT_DELAY_MS)
    }

    private fun canRelaunch(pkg: String) = taskOps.canRelaunch(pkg)

    private fun launchTask(pkg: String, displayId: Int, taskId: Int?, allowRelaunch: Boolean = true) =
        taskOps.launch(pkg, displayId, taskId, allowRelaunch)

    private fun killOnly(closing: List<String>, displays: Set<Int>, reportMemory: Boolean = false, afterwards: (() -> Unit)? = null) =
        taskOps.kill(closing, displays, reportMemory, afterwards)

    // The Back gestures are assignable: the machine reports which gesture fired, the bound action runs.
    override fun swapScreens() = runBackGesture(BackGestureButton.HOLD)
    override fun openTopRecents() = runBackGesture(BackGestureButton.TAP_HOLD)
    override fun showSwitcher() = runBackGesture(BackGestureButton.DOUBLE)

    private fun runBackGesture(gesture: ComboButton) {
        val action = prefs.shoulderAction(gesture)
        dlog("BACK   gesture $gesture action=${action.ifEmpty { "(none)" }}")
        if (action.isNotEmpty()) runComboAction(gesture, action)
    }

    private fun swapScreensNow() = swap.swapScreens()

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
    private fun openTopRecentsNow() = topLaunch.open()

    private fun cancelTopRecentsLaunch(): Unit = topLaunch.cancel()

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
        bottomSwitcher.shown()
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
        bottomSwitcher.dismissed()
        if (!prefs.switcherControllerEnabled) return
        padHelper.setSwitcherNavigation(PadNavigationMode.OFF)
        dpadOverlay.detach()
        restoreRecentsFocus()
        updatePadHelper()
    }

    private fun onPadHelperActiveChanged(active: Boolean) {
        if (!active) {
            macroPlayback.padLost()
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
        if (topLaunch.selectionPending) {
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
                if (topLaunch.selectionPending) {
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
        if (bottomSwitcher.opening) { cancelBottomSwitcherOpen(); return }
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

    private fun showSwitcherNow() = bottomSwitcher.toggle()

    private fun cancelBottomSwitcherOpen(): Unit = bottomSwitcher.cancelOpen()

    private fun refreshBottomTaskStates() = bottomSwitcher.refreshTaskStates()

    /** The AYN Thor's second screen is the non-default internal display (id 4, "Screen-2" here).
     *  The parking display has a higher id but is never a screen. */
    private fun bottomDisplay(): Display? = Displays.bottom(this)

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
        const val MIN_CAPTURE_GAP_MS = 1100L
        const val SWITCHER_SCREENSHOT_DELAY_MS = 100L
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
        const val QUICKSTEP_STATE_COMMAND = TopRecentsLauncher.QUICKSTEP_STATE_COMMAND
        const val TOP_RECENTS_READY_RETRY_MS = 40L
        const val TOP_RECENTS_READY_ATTEMPTS = TopRecentsLauncher.READY_ATTEMPTS
        const val TOP_RECENTS_EVENT_SETTLE_MS = TopRecentsLauncher.EVENT_SETTLE_MS
        const val TOP_RECENTS_DELETE_SETTLE_MS = 260L
        const val TOP_RECENTS_DISMISS_MS = 110L
        const val POWER_MENU_FOCUS_RETRY_MS = 80L
        /** After a restart's kill: the force-stop must have settled, or the launch could land in the dying process. */
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
