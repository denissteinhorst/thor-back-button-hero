package dev.steinhorst.backbuttonhero

import kotlin.math.abs

/** The arithmetic behind the status overlay, kept apart from the battery service to be testable. */
object StatusSummary {
    /** Estimates beyond this are noise from a near-zero current, not a runtime worth showing. */
    private const val MAX_MINUTES = 48 * 60

    /**
     * Minutes until empty from the remaining charge (µAh) and the momentary current (µA). The sign of
     * the current differs between devices, so only its size counts; null when either is unknown.
     */
    fun minutesLeft(chargeMicroAh: Long, currentMicroA: Long): Int? {
        if (chargeMicroAh <= 0 || currentMicroA == 0L || currentMicroA == Long.MIN_VALUE) return null
        val minutes = chargeMicroAh * 60 / abs(currentMicroA)
        return if (minutes in 1..MAX_MINUTES) minutes.toInt() else null
    }
}
