package dev.steinhorst.backbuttonhero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class EchoTrimTest {
    private val rate = 48_000
    private val chunk = 960
    /** The microphone runs this far ahead of the playback and hears the speaker this much later. */
    private val lead = 2_880
    private val latency = 700

    /** Music-like playback: tones between 2 and 6 kHz whose level changes every 50 ms. */
    private fun music(samples: Int, seed: Long = 1): FloatArray {
        val random = Random(seed)
        val tones = intArrayOf(2_200, 3_100, 4_300, 5_200)
        var level = 0f
        return FloatArray(samples) { i ->
            if (i % 2_400 == 0) level = 200f + random.nextInt(8) * 400f
            var sum = 0.0
            for (hz in tones) sum += sin(2 * PI * hz * i / rate)
            (sum * level).toFloat()
        }
    }

    private fun sine(hz: Int, amplitude: Float, samples: Int) =
        FloatArray(samples) { i -> (amplitude * sin(2 * PI * hz * i / rate)).toFloat() }

    private fun rms(samples: FloatArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from until to) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / (to - from))
    }

    /** What the microphone records at position [at]: [played] through the speaker at [volume], plus [voice]. */
    private fun heard(played: FloatArray, at: Int, volume: Float, voice: FloatArray?): Float {
        val source = at - lead - latency
        val speaker = if (source in played.indices) played[source] * 0.4f * volume else 0f
        return speaker + (voice?.getOrNull(at) ?: 0f)
    }

    /**
     * Runs a recording: [quietFrom] on, the speaker plays at a tenth and the trim is told so, as in a
     * talk; before that it learns. Returns what went in and what came out, both by microphone position.
     */
    private fun record(played: FloatArray, quietFrom: Int, voice: FloatArray? = null, echo: Boolean = true): Pair<FloatArray, FloatArray> {
        val trim = EchoTrim(rate)
        val input = FloatArray(played.size)
        val output = FloatArray(played.size)
        val stereo = ShortArray(chunk * 2)
        val samples = FloatArray(chunk)
        var at = 0
        while (at + chunk <= played.size - lead) {
            for (i in 0 until chunk) {
                stereo[2 * i] = played[at + i].toInt().toShort()
                stereo[2 * i + 1] = stereo[2 * i]
            }
            trim.reference(stereo, chunk)
            val talking = at + lead >= quietFrom
            for (i in 0 until chunk) {
                val position = at + lead + i
                // The speaker is down a little before the talk opens, as on the device.
                val volume = if (position >= quietFrom - 4_800) 0.1f else 1f
                samples[i] = if (echo) heard(played, position, volume, voice) else voice?.getOrNull(position) ?: 0f
                input[position] = samples[i]
            }
            trim.process(samples, chunk, (at + lead).toLong(), if (talking) 0.1f else 1f, learn = at + lead < quietFrom - 9_600)
            for (i in 0 until chunk) {
                val position = at + lead + i - trim.delay
                if (position >= 0) output[position] = samples[i]
            }
            at += chunk
        }
        return input to output
    }

    @Test
    fun `without anything learned the samples come back unchanged`() {
        val trim = EchoTrim(rate)
        val input = sine(3_000, 5_000f, rate) .also { tone -> for (i in tone.indices) tone[i] += 2_000f * sin(i * 0.01).toFloat() }
        val samples = input.copyOf()
        var at = 0
        while (at < samples.size) {
            val part = samples.copyOfRange(at, at + chunk)
            trim.process(part, chunk, at.toLong(), 1f, learn = false)
            part.copyInto(samples, at)
            at += chunk
        }
        for (i in 2_000 until rate - trim.delay) assertEquals(input[i], samples[i + trim.delay], 1.5f)
    }

    @Test
    fun `the speaker's sound is found and lowered once it has been heard alone`() {
        val played = music(rate * 5)
        val quiet = rate * 3
        val (input, output) = record(played, quiet)
        val before = rms(input, quiet + rate / 2, quiet + rate * 3 / 2)
        val after = rms(output, quiet + rate / 2, quiet + rate * 3 / 2)
        assertTrue("lowered by ${before / after}", after < before / 5)
    }

    @Test
    fun `a voice louder than the speaker passes, in the speaker's band and below it`() {
        val played = music(rate * 5)
        val quiet = rate * 3
        // The speaker arrives at up to about 300 while talking; the voice is well above it.
        val voice = sine(400, 3_000f, played.size).also { low -> sine(3_000, 2_500f, played.size).forEachIndexed { i, high -> low[i] += high } }
        for (i in 0 until quiet) voice[i] = 0f
        val (_, output) = record(played, quiet, voice)
        val expected = sqrt((3_000.0 * 3_000 + 2_500.0 * 2_500) / 2)
        assertEquals(expected, rms(output, quiet + rate / 2, quiet + rate * 3 / 2), expected * 0.06)
    }

    @Test
    fun `while somebody speaks the speaker's band is lowered by half at most`() {
        val played = music(rate * 5)
        val quiet = rate * 3
        // A voiced sound far below the speaker's band: nothing of the voice is in the band itself.
        val voice = sine(400, 3_000f, played.size)
        for (i in 0 until quiet) voice[i] = 0f
        val (input, output) = record(played, quiet, voice)
        val high = arrayOf(VoiceBand.Biquad(rate, 1_500, highPass = true), VoiceBand.Biquad(rate, 1_500, highPass = true))
        fun highs(samples: FloatArray): Double {
            val filtered = FloatArray(samples.size) { high[1].next(high[0].next(samples[it])) }
            return rms(filtered, quiet + rate / 2, quiet + rate * 3 / 2)
        }
        val before = highs(input)
        val after = highs(output)
        assertTrue("lowered by ${before / after}", after in before * 0.45..before * 0.75)
    }

    @Test
    fun `a microphone that does not hear the speaker is left alone`() {
        // Headphones: the playback runs, the microphone only holds the room and later a voice.
        val played = music(rate * 5)
        val quiet = rate * 3
        val voice = music(played.size, seed = 7).also { for (i in it.indices) it[i] *= 0.05f }
        val (input, output) = record(played, quiet, voice, echo = false)
        assertEquals(rms(input, quiet + rate / 2, quiet + rate * 3 / 2), rms(output, quiet + rate / 2, quiet + rate * 3 / 2), 0.5)
    }
}
