package dev.steinhorst.backbuttonhero

/**
 * One stick's click (L3/R3) gestures while Back is held, timed exactly like the Back button's own
 * (see [BackGestureMachine]): a press, a double press and tap-and-hold. A press fires on release,
 * or after [doubleMs] when a double press is assigned; a second press inside that window fires the
 * double press; pressing again right after a tap and holding for [tapHoldMs] fires tap-and-hold
 * while the stick is still down. The owner runs the actions.
 */
class StickClickMachine(
    private val scheduler: GestureScheduler,
    private val clock: GestureClock,
    private val doubleMs: () -> Long,
    private val tapHoldMs: () -> Long,
    private val bound: (StickClick) -> Boolean,
    private val fire: (StickClick) -> Unit,
) {
    private var held = false
    private var tapHoldFired = false
    private var pressFollowsTap = false
    private var lastTapAt: Long? = null

    private val singlePress = Runnable { if (bound(StickClick.PRESS)) fire(StickClick.PRESS) }
    private val tapHoldTimer = Runnable {
        if (held) {
            tapHoldFired = true
            fire(StickClick.TAP_HOLD)
        }
    }

    private val doubleOn get() = bound(StickClick.DOUBLE)
    private val tapHoldOn get() = bound(StickClick.TAP_HOLD)

    private fun followsRecentTap(): Boolean = lastTapAt?.let {
        clock.uptimeMillis() - it <= maxOf(doubleMs(), if (tapHoldOn) SwitcherService.TAP_HOLD_GRACE_MS else 0L)
    } == true

    /** True when this press belongs to a click gesture, so the key must not reach the game. */
    fun down(): Boolean {
        if (StickClick.entries.none(bound)) return false
        held = true
        tapHoldFired = false
        pressFollowsTap = followsRecentTap()
        if (pressFollowsTap) scheduler.removeCallbacks(singlePress)
        if (pressFollowsTap && tapHoldOn) scheduler.postDelayed(tapHoldTimer, tapHoldMs())
        return true
    }

    fun up() {
        if (!held) return
        held = false
        scheduler.removeCallbacks(tapHoldTimer)
        if (tapHoldFired) {
            tapHoldFired = false
            lastTapAt = null
            return
        }
        val now = clock.uptimeMillis()
        val previous = lastTapAt
        if (pressFollowsTap && doubleOn && previous != null && now - previous <= doubleMs()) {
            lastTapAt = null
            fire(StickClick.DOUBLE)
        } else {
            lastTapAt = now
            if (doubleOn) scheduler.postDelayed(singlePress, doubleMs())
            else if (bound(StickClick.PRESS)) fire(StickClick.PRESS)
        }
    }

    fun reset() {
        held = false
        tapHoldFired = false
        pressFollowsTap = false
        lastTapAt = null
        scheduler.removeCallbacks(singlePress)
        scheduler.removeCallbacks(tapHoldTimer)
    }
}
