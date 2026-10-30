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
}

class BackGestureMachine(
    private val prefs: Prefs,
    private val clock: GestureClock,
    private val scheduler: GestureScheduler,
    private val actions: GestureActions,
    private val dlog: (String) -> Unit = {},
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
    private var lastTapAt: Long? = null
    private val singleTap = Runnable { actions.onSingleTap() }


    fun followsRecentTap(): Boolean = lastTapAt?.let {
        clock.uptimeMillis() - it <= maxOf(prefs.doubleTapMs,
            if (prefs.tapHoldEnabled) SwitcherService.TAP_HOLD_GRACE_MS else 0L)
    } == true

    /** Called by SwitcherService.handleShoulder when a Back+shoulder combo fires. Mirrors the
     *  three statements that used to live inline there. */
    fun markComboFired() {
        comboFired = true
        scheduler.removeCallbacks(holdRunnable)
        scheduler.removeCallbacks(tapHoldRunnable)
    }

    /** Discard pending gestures when interception is suspended or the service disconnects. */
    fun reset() {
        scheduler.removeCallbacks(holdRunnable)
        scheduler.removeCallbacks(tapHoldRunnable)
        scheduler.removeCallbacks(singleTap)
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
                val scheduled = when {
                    pressFollowsTap && prefs.tapHoldEnabled -> { scheduler.postDelayed(tapHoldRunnable, prefs.tapHoldMs); "tapHoldRunnable in ${prefs.tapHoldMs}ms" }
                    prefs.holdEnabled -> { scheduler.postDelayed(holdRunnable, prefs.holdMs); "holdRunnable in ${prefs.holdMs}ms" }
                    else -> "nothing (hold disabled)"
                }
                dlog("DOWN   t=$now (+${sinceFirstDown}ms since previous DOWN)  afterTap=$pressFollowsTap tapHoldEnabled=${prefs.tapHoldEnabled} -> scheduled $scheduled")
                firstDownAt = now
            }
            event.action == KeyEvent.ACTION_UP -> {
                val now = clock.uptimeMillis()
                val heldFor = now - event.downTime
                backDown = false
                scheduler.removeCallbacks(holdRunnable)
                scheduler.removeCallbacks(tapHoldRunnable)
                if (holdFired || tapHoldFired || comboFired) {
                    dlog("UP     t=$now held=${heldFor}ms  holdFired=$holdFired tapHoldFired=$tapHoldFired comboFired=$comboFired -> consumed, no tap logic")
                    lastTapAt = null
                    return true
                }
                val previousTap = lastTapAt
                if (pressFollowsTap && prefs.doubleTapEnabled && previousTap != null &&
                    now - previousTap <= prefs.doubleTapMs) {
                    lastTapAt = null
                    actions.showSwitcher()
                } else {
                    lastTapAt = now
                    if (prefs.doubleTapEnabled) scheduler.postDelayed(singleTap, prefs.doubleTapMs)
                    else actions.onSingleTap()
                }
            }
        }
        return true
    }
}
