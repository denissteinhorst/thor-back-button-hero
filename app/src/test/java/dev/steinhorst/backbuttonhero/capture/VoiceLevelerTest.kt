package dev.steinhorst.backbuttonhero.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class VoiceLevelerTest {
    private val frames = 960

    /** A 300 Hz sine with an RMS of [amplitude]; a chunk holds six whole cycles. */
    private fun tone(amplitude: Int) = FloatArray(frames) { i -> (amplitude * sqrt(2.0) * sin(2 * PI * 300 * i / 48_000)).toFloat() }

    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    /** Speech-like: four chunks of voice, then a short pause at the microphone's own noise. */
    private fun VoiceLeveler.speak(voice: Int, seconds: Int, pause: Int = 30): Double {
        var lastVoice = 0.0
        repeat(seconds * 50 / 6) {
            repeat(4) { lastVoice = rms(tone(voice).also { chunk -> process(chunk, frames) }) }
            repeat(2) { process(tone(pause), frames) }
        }
        return lastVoice
    }

    @Test
    fun `a quiet voice is raised to speech level within a few seconds`() {
        val leveler = VoiceLeveler()
        // About -46 dBFS, a voice held at arm's length from the Thor's microphone, in a quiet room.
        assertEquals(6553.0, leveler.speak(160, 5, pause = 10), 15.0)
    }

    @Test
    fun `the gain never rises above 40 dB`() {
        val leveler = VoiceLeveler()
        leveler.speak(66, 20, pause = 1)
        assertEquals(99.3f, leveler.gain, 0.5f)
        // A noisier room caps the gain lower, so its noise stays at about -30 dBFS.
        val noisy = VoiceLeveler()
        noisy.speak(160, 5, pause = 30)
        assertEquals(34f, noisy.gain, 1.6f)
        leveler.speak(40, 20, pause = 1)
        assertTrue(leveler.gain <= 100f)
    }

    @Test
    fun `a loud voice is brought down within a few chunks and never boosted`() {
        val leveler = VoiceLeveler()
        repeat(3) { leveler.process(tone(12_000), frames) }
        assertEquals(1f, leveler.gain, 0.001f)
    }

    @Test
    fun `pauses at the noise floor keep the gain`() {
        val leveler = VoiceLeveler()
        leveler.speak(1_000, 3)
        val speaking = leveler.gain
        repeat(200) { leveler.process(tone(30), frames) }
        assertEquals(speaking, leveler.gain, 0.0001f)
    }

    @Test
    fun `background above the gate is never lifted above -30 dBFS`() {
        // A steady sound in the voice's band, louder than the microphone's own noise.
        val leveler = VoiceLeveler()
        repeat(500) { leveler.process(tone(150), frames) }
        assertTrue(leveler.gain * 150 <= 1_040f)
        assertTrue(rms(tone(150).also { leveler.process(it, frames) }) <= 1_040.0)
    }

    @Test
    fun `speech over a background comes as close to speech level as the background allows`() {
        val leveler = VoiceLeveler()
        val voice = leveler.speak(1_500, 5, pause = 150)
        assertTrue(voice in 4_500.0..6_600.0)
    }

    @Test
    fun `a new talk never starts louder than the start gain`() {
        val leveler = VoiceLeveler()
        leveler.speak(66, 20, pause = 1)
        leveler.restart()
        assertEquals(VoiceLeveler.START_GAIN, leveler.gain, 0f)
        val quiet = VoiceLeveler(2f)
        quiet.restart()
        assertEquals(2f, quiet.gain, 0f)
    }

    @Test
    fun `the gain glides across a chunk instead of jumping`() {
        val leveler = VoiceLeveler()
        val input = tone(12_000)
        val out = input.copyOf()
        leveler.process(out, frames)
        // The first samples still carry almost the old gain, the last one the new.
        val next = VoiceLeveler.START_GAIN * 0.25f
        assertEquals(input[40] * (VoiceLeveler.START_GAIN + (next - VoiceLeveler.START_GAIN) * 41 / frames), out[40], 2f)
        assertEquals(input[frames - 41] * (VoiceLeveler.START_GAIN + (next - VoiceLeveler.START_GAIN) * (frames - 40) / frames), out[frames - 41], 2f)
    }

    @Test
    fun `sound above the voice's band is neither a voice nor leveled like one`() {
        // What the lowered speaker leaves at the microphone lies above 2 kHz.
        val leveler = VoiceLeveler()
        val speaker = FloatArray(frames) { i -> (600 * sin(2 * PI * 5_000 * i / 48_000)).toFloat() }
        repeat(250) { leveler.process(speaker.copyOf(), frames) }
        assertEquals(VoiceLeveler.START_GAIN, leveler.gain, 0f)
    }

    @Test
    fun `nothing heard changes nothing`() {
        val leveler = VoiceLeveler()
        leveler.process(FloatArray(0), 0)
        assertEquals(VoiceLeveler.START_GAIN, leveler.gain, 0f)
    }

    @Test
    fun `the limiter passes normal levels and rounds off peaks below full scale`() {
        assertEquals(1_000.toShort(), VoiceLeveler.limit(1_000f))
        assertEquals((-20_000).toShort(), VoiceLeveler.limit(-20_000f))
        val peak = VoiceLeveler.limit(90_000f)
        assertTrue(peak in 30_000..32_767)
        assertEquals(-peak, VoiceLeveler.limit(-90_000f).toInt())
        assertTrue(VoiceLeveler.limit(30_000f) < 30_000)
        assertTrue(abs(VoiceLeveler.limit(27_852f) - 27_852) <= 1)
    }
}
