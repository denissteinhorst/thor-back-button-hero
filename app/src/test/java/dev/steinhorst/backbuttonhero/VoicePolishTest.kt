package dev.steinhorst.backbuttonhero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class VoicePolishTest {
    private val rate = 48_000
    private val delay = rate * VoicePolish.LOOKAHEAD_MS / 1000

    private fun sine(hz: Int, amplitude: Float, samples: Int) =
        FloatArray(samples) { i -> (amplitude * sin(2 * PI * hz * i / rate)).toFloat() }

    private fun FloatArray.rms(from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from until to) sum += this[i].toDouble() * this[i]
        return sqrt(sum / (to - from))
    }

    private fun FloatArray.peak(from: Int, to: Int): Float {
        var peak = 0f
        for (i in from until to) peak = maxOf(peak, abs(this[i]))
        return peak
    }

    /** Runs [input] through in chunks of 20 ms, as the recorder does. */
    private fun VoicePolish.run(input: FloatArray): FloatArray {
        val out = input.copyOf()
        val chunk = FloatArray(960)
        var at = 0
        while (at < out.size) {
            val count = minOf(960, out.size - at)
            out.copyInto(chunk, 0, at, at + count)
            process(chunk, count)
            chunk.copyInto(out, at, 0, count)
            at += count
        }
        return out
    }

    @Test
    fun `a voice below the threshold comes back later and louder, otherwise unchanged`() {
        val input = sine(300, 3_000f, rate)
        val out = VoicePolish(rate).run(input)
        val from = rate / 2
        for (i in from until from + 2_000) assertEquals(input[i - delay] * VoicePolish.MAKEUP, out[i], 2f)
    }

    @Test
    fun `loud passages are lowered and never reach full scale`() {
        // A leveled voice's peaks: 2.5 dB over full scale before the polish.
        val input = sine(300, 43_700f, rate)
        val out = VoicePolish(rate).run(input)
        assertTrue(out.peak(rate / 2, rate) in 25_000f..31_500f)
        // A sudden peak after a soft passage is caught as well, because the gain looks ahead.
        val sudden = sine(300, 3_000f, rate) + sine(300, 43_700f, rate / 2)
        assertTrue(VoicePolish(rate).run(sudden).peak(rate, sudden.size) < 32_767f)
    }

    @Test
    fun `a pause goes down after the hold and a word opens before it starts`() {
        val word = sine(300, 3_000f, rate / 2)
        val pause = sine(300, 100f, rate)
        val out = VoicePolish(rate).run(word + pause + word)
        // 100 ms into the pause it still passes; by the end it is down by the pause gain.
        val early = word.size + delay + rate / 10
        assertEquals(100.0 * VoicePolish.MAKEUP / sqrt(2.0), out.rms(early - 960, early + 960), 8.0)
        val late = word.size + rate - 4_800
        assertEquals(100.0 * VoicePolish.MAKEUP * VoicePolish.PAUSE_GAIN / sqrt(2.0), out.rms(late, late + 4_800), 3.0)
        // The second word is at full level from its first cycle.
        val second = word.size + pause.size + delay
        assertEquals(3_000.0 * VoicePolish.MAKEUP / sqrt(2.0), out.rms(second, second + 960), 150.0)
    }

    @Test
    fun `sound above the voice's own band does not count as a word`() {
        // What the lowered speaker leaves at the microphone: all of it above 2 kHz.
        val speaker = sine(5_000, 2_000f, rate)
        val out = VoicePolish(rate).run(speaker)
        assertEquals(2_000.0 * VoicePolish.MAKEUP * VoicePolish.PAUSE_GAIN / sqrt(2.0), out.rms(rate / 2, rate), 10.0)
    }

    @Test
    fun `nothing heard changes nothing`() {
        val polish = VoicePolish(rate)
        val samples = floatArrayOf(1f, 2f, 3f)
        polish.process(samples, 0)
        assertEquals(1f, samples[0], 0f)
    }
}
