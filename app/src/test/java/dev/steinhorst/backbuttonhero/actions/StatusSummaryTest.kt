package dev.steinhorst.backbuttonhero.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusSummaryTest {
    @Test
    fun `remaining minutes follow charge over current, whatever its sign`() {
        assertEquals(180, StatusSummary.minutesLeft(6_000_000, 2_000_000))
        assertEquals(180, StatusSummary.minutesLeft(6_000_000, -2_000_000))
    }

    @Test
    fun `unknown or implausible values give no estimate`() {
        assertNull(StatusSummary.minutesLeft(6_000_000, 0))
        assertNull(StatusSummary.minutesLeft(0, 1_000_000))
        assertNull(StatusSummary.minutesLeft(6_000_000, Long.MIN_VALUE))
        // A trickle current would claim days of runtime.
        assertNull(StatusSummary.minutesLeft(6_000_000, 1_000))
        // Less than a minute left rounds to nothing worth showing.
        assertNull(StatusSummary.minutesLeft(100, 10_000_000))
    }
}
