package dev.steinhorst.backbuttonhero

/**
 * How fast a held volume or brightness shortcut steps: slowly at first, then easing in to a fast
 * pace the longer it is held, so a single nudge stays precise and a long hold crosses the range.
 */
object RepeatCurve {
    const val INITIAL_DELAY_MS = 250L
    const val SLOWEST_INTERVAL_MS = 130L
    const val FASTEST_INTERVAL_MS = 40L
    /** Held time after the first repeat until the fastest pace is reached. */
    const val RAMP_MS = 1100L

    /** The pause before the next step once the control has been held [heldMs] after its first repeat. */
    fun intervalAfter(heldMs: Long): Long {
        val t = (heldMs.coerceAtLeast(0L).toFloat() / RAMP_MS).coerceAtMost(1f)
        val eased = t * t * (3f - 2f * t) // smoothstep: gentle start, gentle arrival
        return (SLOWEST_INTERVAL_MS + (FASTEST_INTERVAL_MS - SLOWEST_INTERVAL_MS) * eased).toLong()
    }
}

/**
 * Repeats a held control's step action on the [RepeatCurve]. [step] returns false once the repeat
 * no longer applies (for instance Back was released), which ends it.
 */
class LevelRepeater(
    private val scheduler: GestureScheduler,
    private val clock: GestureClock,
    private val step: () -> Boolean,
) {
    private var firstRepeatAt = 0L
    private val tick = object : Runnable {
        override fun run() {
            val now = clock.uptimeMillis()
            if (firstRepeatAt == 0L) firstRepeatAt = now
            if (step()) scheduler.postDelayed(this, RepeatCurve.intervalAfter(now - firstRepeatAt))
        }
    }

    val running: Boolean get() = scheduler.hasCallbacks(tick)

    fun start() {
        stop()
        scheduler.postDelayed(tick, RepeatCurve.INITIAL_DELAY_MS)
    }

    fun stop() {
        scheduler.removeCallbacks(tick)
        firstRepeatAt = 0L
    }
}
