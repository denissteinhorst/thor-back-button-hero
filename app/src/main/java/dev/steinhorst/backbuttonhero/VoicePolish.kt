package dev.steinhorst.backbuttonhero

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.exp
import kotlin.math.pow

/**
 * The last step for a leveled voice, [delay] samples late so it can act before a sound instead of after it.
 *
 * Pauses go down: between words the microphone only delivers the room and what is left of the speaker.
 * A pause is told by the band below [DETECTOR_HZ] alone, because the Thor's speaker does not reach
 * down there while every voiced sound does; the level as a whole cannot tell music from a quiet
 * consonant. Opening ahead of a word and holding after it keeps those consonants.
 *
 * Peaks go down: speech peaks 18-20 dB over its average, so a voice loud enough next to a game would
 * clip. Lowering what rises above [THRESHOLD] to a third and making that up brings the average up
 * without touching the timbre.
 */
class VoicePolish(sampleRate: Int) {
    val delay = sampleRate * LOOKAHEAD_MS / 1000
    private val line = FloatArray(delay)
    private var at = 0
    private val detector = VoiceBand.Biquad(sampleRate, DETECTOR_HZ, highPass = false)
    private val hold = sampleRate * HOLD_MS / 1000 + delay
    private val openStep = 1f / (sampleRate * OPEN_MS / 1000)
    private val closeStep = (1f - PAUSE_GAIN) / (sampleRate * CLOSE_MS / 1000)
    private val peakAhead = sampleRate * PEAK_AHEAD_MS / 1000
    private val release = exp(-1000.0 / (sampleRate * RELEASE_MS)).toFloat()
    private val smooth = exp(-1000.0 / (sampleRate * SMOOTH_MS)).toFloat()
    private var quiet = hold
    private var pause = PAUSE_GAIN
    private var envelope = 0f
    private var squeeze = 1f

    /** Replaces the first [count] [samples] with the polished samples from [delay] samples earlier. */
    fun process(samples: FloatArray, count: Int) {
        for (i in 0 until count) {
            val sample = samples[i]
            if (abs(detector.next(sample)) > VOICED) quiet = 0 else if (quiet < hold) quiet++
            pause = if (quiet < hold) minOf(1f, pause + openStep) else maxOf(PAUSE_GAIN, pause - closeStep)
            // The gain is already down when a peak leaves the line, and gets there without a step.
            val ahead = abs(line[(at + peakAhead) % delay]) * pause
            envelope = if (ahead > envelope) ahead else envelope * release
            val wanted = if (envelope > THRESHOLD) cbrt(THRESHOLD / envelope).let { it * it } else 1f
            squeeze = smooth * squeeze + (1f - smooth) * wanted
            samples[i] = line[at] * pause * squeeze * MAKEUP
            line[at] = sample
            at = (at + 1) % delay
        }
    }

    companion object {
        private const val FULL_SCALE = 32_767f
        const val LOOKAHEAD_MS = 80
        const val DETECTOR_HZ = 1_200
        /** About -30 dBFS below [DETECTOR_HZ]: 12 dB under a leveled voice, far over anything else there. */
        private const val VOICED = FULL_SCALE * 0.03f
        private const val HOLD_MS = 200
        private const val OPEN_MS = 10
        private const val CLOSE_MS = 150
        /** -24 dB: a pause stays a pause of the same room instead of a hole. */
        const val PAUSE_GAIN = 0.063f
        /** About -18 dBFS; above it three dB in become one dB out. */
        private const val THRESHOLD = FULL_SCALE * 0.126f
        private const val PEAK_AHEAD_MS = 5
        private const val RELEASE_MS = 50
        private const val SMOOTH_MS = 1.5
        /** +9.5 dB: a leveled voice's highest peaks end just under full scale. */
        val MAKEUP = 10.0.pow(9.5 / 20).toFloat()
    }
}
