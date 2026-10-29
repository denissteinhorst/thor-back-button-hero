package dev.steinhorst.backbuttonhero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentsMemoryTest {
    @Test
    fun `kernel available memory is used instead of lower ActivityManager value`() {
        val result = RecentsMemory.calculate("MemTotal: 15600000 kB\nMemAvailable: 10725000 kB\nMemFree: 100000 kB", 15_974_400_000L, 8_000_000_000L)
        assertEquals(16_000_000_000L, result.total)
        assertEquals(11_000_000_000.0, result.available.toDouble(), 1024.0)
        assertTrue(result.available > 8_000_000_000L)
    }

    @Test
    fun `unavailable proc data follows the OEM ActivityManager fallback`() {
        val result = RecentsMemory.calculate(null, 8_000_000_000L, 3_000_000_000L)
        assertEquals(RecentsMemory(3_000_000_000L, 8_000_000_000L), result)
        assertEquals(result, RecentsMemory.calculate("MemTotal: invalid\nMemAvailable: invalid", 8_000_000_000L, 3_000_000_000L))
    }

    @Test
    fun `zero and malformed totals never divide by zero`() {
        assertEquals(RecentsMemory(0, 0), RecentsMemory.calculate("MemTotal: 0 kB\nMemAvailable: 0 kB", 0, 0))
    }
}
