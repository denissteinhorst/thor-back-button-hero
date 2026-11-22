package dev.steinhorst.backbuttonhero.capture

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Brings the microphone up to speech level for a recording. The Thor's built-in microphone delivers
 * speech far below what a game plays, so a fixed mix leaves the voice inaudible. The gain follows the
 * voice: it drops fast when speech gets loud and rises slowly, and it holds during pauses so the
 * background noise is never pulled up to speech level.
 *
 * The background is not always quiet: a room has its own sound, and players the recorder cannot
 * silence still reach the microphone. The leveler follows that floor, counts only what stands clearly above it as voice and
 * never lifts the floor above [NOISE_CEILING]; otherwise the gain would creep up in every pause and the
 * next word would crackle against the limiter.
 */
class VoiceLeveler(gain: Float = START_GAIN, sampleRate: Int = 48_000) {
    /** The level is read below [VoicePolish.DETECTOR_HZ] only: the Thor's speaker does not reach down
     *  there, so music it plays is neither taken for a voice nor leveled up like one. */
    private val detector = VoiceBand.Biquad(sampleRate, VoicePolish.DETECTOR_HZ, highPass = false)
    var gain = gain
        private set
    var floor = GATE
        private set

    /** A new push-to-talk talk starts no louder than [START_GAIN], whatever the last one ended on. */
    fun restart() {
        gain = minOf(gain, START_GAIN)
    }

    /** Levels the first [count] [samples] in place; the gain glides across the chunk, so it never clicks. */
    fun process(samples: FloatArray, count: Int) {
        if (count <= 0) return
        var sum = 0.0
        for (i in 0 until count) {
            val low = detector.next(samples[i])
            sum += low.toDouble() * low
        }
        val rms = sqrt(sum / count).toFloat()
        floor = if (rms < floor) maxOf(rms, MIN_FLOOR) else floor * FLOOR_RISE_PER_CHUNK
        val ceiling = (NOISE_CEILING / floor).coerceIn(MIN_GAIN, MAX_GAIN)
        val voice = rms >= GATE && rms >= floor * VOICE_ABOVE_FLOOR
        val wanted = minOf(if (voice) (TARGET / rms).coerceAtLeast(MIN_GAIN) else gain, ceiling)
        val next = if (wanted < gain) maxOf(wanted, gain * FALL_PER_CHUNK) else minOf(wanted, gain * RISE_PER_CHUNK)
        for (i in 0 until count) samples[i] *= gain + (next - gain) * (i + 1) / count
        gain = next
    }

    companion object {
        private const val FULL_SCALE = 32_767f
        /** Speech at about -14 dBFS RMS stays above a loud game lowered while talking; the limiter takes its peaks. */
        private const val TARGET = FULL_SCALE * 0.2f
        /** Below about -54 dBFS it is the microphone's own noise (about -60 dBFS on the Thor), not a voice. */
        private const val GATE = FULL_SCALE * 0.002f
        private const val MIN_GAIN = 1f
        /** +40 dB at most: what a voice held at arm's length needs, while push to talk keeps the noise out. */
        private const val MAX_GAIN = 100f
        /** Push-to-talk bursts are short, so the first word starts near the usual level: +24 dB. */
        const val START_GAIN = 15.8f
        /** The background never comes out louder than about -30 dBFS; [VoicePolish] lowers it further in
         *  the pauses. A stricter limit kept a voice over the lowered speaker from reaching speech level. */
        private const val NOISE_CEILING = FULL_SCALE * 0.0316f
        /** A voice stands at least 10 dB above the background. */
        private const val VOICE_ABOVE_FLOOR = 3.16f
        /** The floor drops at once to anything quieter and rises by 3 dB a second, so speech never becomes the floor. */
        private const val FLOOR_RISE_PER_CHUNK = 1.0069f
        private const val MIN_FLOOR = 1f
        /** Per 20 ms chunk: down by up to 12 dB, up by at most 20 dB a second. */
        private const val FALL_PER_CHUNK = 0.25f
        private const val RISE_PER_CHUNK = 1.047f
        /** Above this the mix is rounded off instead of clipped hard. */
        private const val KNEE = FULL_SCALE * 0.85f

        /** Rounds a mixed sample off near full scale, so voice and game together never crackle. */
        fun limit(sample: Float): Short {
            val level = abs(sample)
            if (level <= KNEE) return sample.toInt().toShort()
            val room = FULL_SCALE - KNEE
            return (sign(sample) * (KNEE + room * tanh((level - KNEE) / room))).toInt().toShort()
        }
    }
}

/**
 * How far the game sound goes down under a voice in the video. A fixed step is wrong for most games:
 * one mastered to full scale stays as loud as the voice, a quiet one disappears. The game is instead
 * brought to [BED], well under the leveled voice, within [MIN_GAIN]..[MAX_GAIN].
 */
class GameBed {
    private var meanSquare = 0f

    /** Follows the level of the first [count] [samples]: up within a few chunks, down over seconds, so a
     *  short break in the music right before a talk does not leave the game too loud under it. */
    fun hear(samples: ShortArray, count: Int) {
        if (count <= 0) return
        var sum = 0.0
        for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
        val heard = (sum / count).toFloat()
        meanSquare += (heard - meanSquare) * if (heard > meanSquare) RISE else FALL
    }

    fun gain(): Float = if (meanSquare <= 0f) MAX_GAIN else (BED / sqrt(meanSquare)).coerceIn(MIN_GAIN, MAX_GAIN)

    companion object {
        /** About -28 dBFS RMS, 14 dB under the leveled voice: clearly behind it and still there. */
        private const val BED = 1_304f
        /** -24 dB at most, and never less than -10 dB, so talking is audible in the game sound too. */
        const val MIN_GAIN = 0.063f
        const val MAX_GAIN = 0.316f
        private const val RISE = 0.2f
        private const val FALL = 0.01f
    }
}
