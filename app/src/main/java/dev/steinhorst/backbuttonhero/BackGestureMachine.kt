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

    // Captured once at this press's DOWN, not recomputed at UP: singleTap may have already
    // fired on its own by the time a long-held second press is released, which would make a
    // hasCallbacks() check at UP time wrongly say "no tap was pending".
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
    private val singleTap = Runnable {
        dlog("RUNNABLE singleTap fired -> onSingleTap()")
        actions.onSingleTap()
    }
    private val detector = DoubleTapDetector({ prefs.doubleTapMs }) {
        dlog("DETECTOR double-tap recognized -> showSwitcher()")
        scheduler.removeCallbacks(singleTap)
        actions.showSwitcher()
    }

    /** Called by SwitcherService.handleShoulder when a Back+shoulder combo fires. Mirrors the
     *  three statements that used to live inline there. */
    fun markComboFired() {
        comboFired = true
        scheduler.removeCallbacks(holdRunnable)
        scheduler.removeCallbacks(tapHoldRunnable)
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
                // A pending singleTap callback means an earlier release already started the
                // double-tap window, so this press is a second one following a tap. Cancel it
                // here, at DOWN - not just at the eventual UP - otherwise it keeps running on
                // its own schedule and fires mid-hold regardless of what this press becomes.
                pressFollowsTap = scheduler.hasCallbacks(singleTap)
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
                    return true
                }
                if (!prefs.doubleTapEnabled) {
                    dlog("UP     t=$now held=${heldFor}ms  doubleTapEnabled=false -> immediate onSingleTap()")
                    actions.onSingleTap(); return true
                }
                scheduler.removeCallbacks(singleTap)
                detector.onPress()
                val delay = singleTapDelay()
                // If this release did not complete a double-tap, the first tap is still pending.
                // Double-tap itself still resolves purely on prefs.doubleTapMs (via detector,
                // above) - this is only how long we wait before concluding no second press is
                // coming. With tap-hold on, that's stretched past doubleTapMs: pressing and
                // holding a second time takes a beat longer than tapping it, and doubleTapMs
                // alone is often gone before that second press even lands, so the single-tap
                // action fires first and the hold is read as a lone hold instead of a tap-hold.
                if (!pressFollowsTap) scheduler.postDelayed(singleTap, delay)
                dlog("UP     t=$now held=${heldFor}ms  pressFollowsTap=$pressFollowsTap -> " +
                    if (pressFollowsTap) "detector.onPress() only (this was the 2nd press of a tap combo)"
                    else "scheduled singleTap in ${delay}ms")
            }
        }
        return true
    }

    /** How long to wait after a tap before firing it as a plain single tap. */
    private fun singleTapDelay(): Long =
        if (prefs.tapHoldEnabled) maxOf(prefs.doubleTapMs, SwitcherService.TAP_HOLD_GRACE_MS) else prefs.doubleTapMs
}
