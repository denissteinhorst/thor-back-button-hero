package dev.steinhorst.backbuttonhero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class VoiceBandTest {
    private val rate = 48_000

    /** Level change in dB of a sine at [hz] after the filter has settled. */
    private fun gainDb(hz: Int): Double {
        val n = rate
        val input = ShortArray(n) { i -> (10_000 * sin(2 * PI * hz * i / rate)).toInt().toShort() }
        val out = FloatArray(n)
        VoiceBand(rate).process(input, n, out)
        fun rms(from: Int, values: (Int) -> Double) = sqrt((from until n).sumOf { values(it).let { v -> v * v } } / (n - from))
        return 20 * log10(rms(n / 2) { out[it].toDouble() } / rms(n / 2) { input[it].toDouble() })
    }

    @Test
    fun `the speech band passes unchanged`() {
        assertEquals(0.0, gainDb(1_000), 0.5)
        assertEquals(0.0, gainDb(300), 0.5)
        assertEquals(0.0, gainDb(3_000), 1.0)
    }

    @Test
    fun `rumble and hiss are cut`() {
        assertTrue(gainDb(30) < -20)
        assertTrue(gainDb(15_000) < -20)
        // Both corners sit 3 dB down per Butterworth section.
        assertEquals(-3.0, gainDb(VoiceBand.LOW_CUT_HZ), 0.5)
        assertEquals(-6.0, gainDb(VoiceBand.HIGH_CUT_HZ), 0.7)
    }

    @Test
    fun `the gate opens late after a press and closes early before a release`() {
        val gate = TalkGate(openLate = 100, closeEarly = 50)
        gate.press(1_000)
        assertFalse(gate.isOpen(1_099))
        assertTrue(gate.isOpen(1_100))
        gate.release(2_000)
        assertTrue(gate.isOpen(1_949))
        assertFalse(gate.isOpen(1_950))
        assertFalse(gate.isOpen(5_000))
    }

    @Test
    fun `a talk can be told to open later than usual`() {
        val gate = TalkGate(openLate = 100, closeEarly = 50)
        gate.press(1_000, late = 300)
        assertFalse(gate.isOpen(1_299))
        assertTrue(gate.isOpen(1_300))
        gate.release(2_000)
        gate.press(3_000)
        assertFalse(gate.isOpen(3_099))
        assertTrue(gate.isOpen(3_100))
    }

    @Test
    fun `a tap shorter than the margins never opens the microphone`() {
        val gate = TalkGate(openLate = 100, closeEarly = 50)
        gate.press(1_000)
        gate.release(1_120)
        for (index in 900L..1_300L) assertFalse(gate.isOpen(index))
    }

    @Test
    fun `talks follow each other in order`() {
        val gate = TalkGate(openLate = 10, closeEarly = 10)
        gate.press(100)
        gate.release(200)
        gate.press(300)
        assertFalse(gate.isOpen(50))
        assertTrue(gate.isOpen(150))
        assertFalse(gate.isOpen(250))
        assertTrue(gate.isOpen(400))
    }
}
