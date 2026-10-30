package dev.steinhorst.backbuttonhero

import android.view.KeyEvent

/** Replaces SystemClock.uptimeMillis() so tests can control time. */
interface GestureClock {
    fun uptimeMillis(): Long
}

/** Replaces Handler.postDelayed/removeCallbacks/hasCallbacks so tests can control scheduling. */
interface GestureScheduler {
    fun postDelayed(runnable: Runnable, delayMs: Long)
    fun removeCallbacks(runnable: Runnable)
    fun hasCallbacks(runnable: Runnable): Boolean
}

/** The side effects a completed gesture triggers - implemented by SwitcherService. */
interface GestureActions {
    fun swapScreens()
    fun openTopRecents()
    fun showSwitcher()
    fun onSingleTap()
    /** Delivers the Back an app would have received without Back Button Hero (see [AppException]). */
    fun nativeBack(longPress: Boolean)
}

class BackGestureMachine(
    private val prefs: Prefs,
    private val clock: GestureClock,
    private val scheduler: GestureScheduler,
    private val actions: GestureActions,
    private val dlog: (String) -> Unit = {},
    /** The focused app's exception, read once per press; null when the app has none. */
    private val exception: () -> AppException? = { null },
    private val nativeLongPressMs: Long = 500L,
) {
    /** True while the Back key is physically held - read by SwitcherService.handleShoulder. */
    var backDown = false
        private set

    /** Set when a Back+shoulder combo ran, so the eventual UP does not also fire tap logic. */
    var comboFired = false
        private set

    private var holdFired = false
    private var tapHoldFired = false
    private var firstDownAt = 0L

    // Latch at DOWN, independently of whether the single-tap deadline has already elapsed.
    private var pressFollowsTap = false

    private val holdRunnable = Runnable {
        holdFired = true
        dlog("RUNNABLE holdRunnable fired -> swapScreens()")
        actions.swapScreens()
    }
    private val tapHoldRunnable = Runnable {
        tapHoldFired = true
        dlog("RUNNABLE tapHoldRunnable fired -> openTopRecents()")
        actions.openTopRecents()
    }
    // An excepted hold is the app's own long press, so it fires at the system long-press timeout.
    private val nativeHoldRunnable = Runnable {
        holdFired = true
        dlog("RUNNABLE nativeHoldRunnable fired -> nativeBack(longPress)")
        actions.nativeBack(longPress = true)
    }
    private var lastTapAt: Long? = null
    private val singleTap = Runnable { tap() }
    private var rules: AppException? = null

    private fun allows(control: ExceptionControl) = rules?.allows(control) ?: true
    private val doubleTapOn get() = prefs.doubleTapEnabled && allows(ExceptionControl.DOUBLE)
    private val tapHoldOn get() = prefs.tapHoldEnabled && allows(ExceptionControl.TAP_HOLD)

    private fun tap() = if (allows(ExceptionControl.SINGLE)) actions.onSingleTap() else actions.nativeBack(longPress = false)

    fun followsRecentTap(): Boolean = lastTapAt?.let {
        clock.uptimeMillis() - it <= maxOf(prefs.doubleTapMs,
            if (tapHoldOn) SwitcherService.TAP_HOLD_GRACE_MS else 0L)
    } == true

    /** Called by SwitcherService.handleShoulder when a Back+shoulder combo fires. Mirrors the
     *  three statements that used to live inline there. */
    fun markComboFired() {
        comboFired = true
        scheduler.removeCallbacks(holdRunnable)
        scheduler.removeCallbacks(tapHoldRunnable)
        scheduler.removeCallbacks(nativeHoldRunnable)
    }

    /** Discard pending gestures when interception is suspended or the service disconnects. */
    fun reset() {
        scheduler.removeCallbacks(holdRunnable)
        scheduler.removeCallbacks(tapHoldRunnable)
        scheduler.removeCallbacks(nativeHoldRunnable)
        scheduler.removeCallbacks(singleTap)
        rules = null
        backDown = false
        comboFired = false
        holdFired = false
        tapHoldFired = false
        firstDownAt = 0L
        lastTapAt = null
        pressFollowsTap = false
    }

    fun handleBack(event: KeyEvent): Boolean {
        when {
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> {
                val now = clock.uptimeMillis()
                val sinceFirstDown = if (firstDownAt == 0L) -1 else now - firstDownAt
                backDown = true
                holdFired = false
                tapHoldFired = false
                comboFired = false
                pressFollowsTap = followsRecentTap()
                if (pressFollowsTap) scheduler.removeCallbacks(singleTap)
                // Latched only after the previous tap's window was judged under the rules it was made in.
                rules = exception()
                val scheduled = when {
                    pressFollowsTap && tapHoldOn -> { scheduler.postDelayed(tapHoldRunnable, prefs.tapHoldMs); "tapHoldRunnable in ${prefs.tapHoldMs}ms" }
                    !allows(ExceptionControl.HOLD) -> { scheduler.postDelayed(nativeHoldRunnable, nativeLongPressMs); "nativeHoldRunnable in ${nativeLongPressMs}ms" }
                    prefs.holdEnabled -> { scheduler.postDelayed(holdRunnable, prefs.holdMs); "holdRunnable in ${prefs.holdMs}ms" }
                    else -> "nothing (hold disabled)"
                }
                dlog("DOWN   t=$now (+${sinceFirstDown}ms since previous DOWN)  afterTap=$pressFollowsTap tapHoldEnabled=$tapHoldOn -> scheduled $scheduled")
                firstDownAt = now
            }
            event.action == KeyEvent.ACTION_UP -> {
                val now = clock.uptimeMillis()
                val heldFor = now - event.downTime
                backDown = false
                scheduler.removeCallbacks(holdRunnable)
                scheduler.removeCallbacks(tapHoldRunnable)
                scheduler.removeCallbacks(nativeHoldRunnable)
                if (holdFired || tapHoldFired || comboFired) {
                    dlog("UP     t=$now held=${heldFor}ms  holdFired=$holdFired tapHoldFired=$tapHoldFired comboFired=$comboFired -> consumed, no tap logic")
                    lastTapAt = null
                    return true
                }
                val previousTap = lastTapAt
                if (pressFollowsTap && doubleTapOn && previousTap != null &&
                    now - previousTap <= prefs.doubleTapMs) {
                    lastTapAt = null
                    dlog("UP     t=$now -> double tap")
                    actions.showSwitcher()
                } else {
                    dlog("UP     t=$now -> tap (${if (doubleTapOn) "waits ${prefs.doubleTapMs}ms" else "immediate"})")
                    lastTapAt = now
                    if (doubleTapOn) scheduler.postDelayed(singleTap, prefs.doubleTapMs)
                    else tap()
                }
            }
        }
        return true
    }
}
