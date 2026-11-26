package dev.steinhorst.backbuttonhero

import dev.steinhorst.backbuttonhero.capture.GameBed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameBedTest {
    private val samples = 1_920

    private fun tone(amplitude: Int) = ShortArray(samples) { i -> (if (i % 2 == 0) amplitude else -amplitude).toShort() }

    private fun GameBed.play(amplitude: Int, chunks: Int) = repeat(chunks) { hear(tone(amplitude), samples) }

    @Test
    fun `a game never heard is lowered by the usual step`() {
        assertEquals(GameBed.MAX_GAIN, GameBed().gain(), 0f)
        val silent = GameBed().apply { play(0, 50) }
        assertEquals(GameBed.MAX_GAIN, silent.gain(), 0f)
    }

    @Test
    fun `a loud game is brought well under the voice`() {
        // About -8 dBFS, music mastered to full scale: 20 dB down puts it at -28 dBFS.
        val bed = GameBed().apply { play(13_000, 100) }
        assertEquals(0.1f, bed.gain(), 0.002f)
    }

    @Test
    fun `the depth stays between the usual step and 24 dB`() {
        val quiet = GameBed().apply { play(1_000, 100) }
        assertEquals(GameBed.MAX_GAIN, quiet.gain(), 0f)
        val hot = GameBed().apply { play(32_000, 100) }
        assertEquals(GameBed.MIN_GAIN, hot.gain(), 0f)
    }

    @Test
    fun `loud sound counts within a few chunks and a short break does not undo it`() {
        val bed = GameBed().apply { play(13_000, 15) }
        assertEquals(0.1f, bed.gain(), 0.005f)
        // A quarter of a second of silence right before the talk.
        bed.play(0, 12)
        assertTrue(bed.gain() < 0.115f)
    }

    @Test
    fun `nothing heard changes nothing`() {
        val bed = GameBed().apply { play(13_000, 100) }
        val before = bed.gain()
        bed.hear(ShortArray(0), 0)
        assertEquals(before, bed.gain(), 0f)
    }
}
