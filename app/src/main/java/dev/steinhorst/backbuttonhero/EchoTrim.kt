package dev.steinhorst.backbuttonhero

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Takes what the speaker plays out of the microphone, with the captured playback as the reference.
 *
 * Subtracting the playback itself does not work on the Thor: what the microphone hears of the speaker
 * is too distorted (measured: the best linear filter removes 6-11 dB). How loud it is in a band at a
 * moment, however, follows the playback within a few dB. So this works on loudness: per band between
 * [LOW_HZ] and [HIGH_HZ], where nearly all of the speaker's sound at the microphone lies, it learns how
 * much of the playback arrives, and lowers a band whenever the microphone holds no more there than the
 * speaker explains. A voice louder than that passes untouched, and so does everything below [LOW_HZ].
 *
 * A band's gain that jumps from frame to frame is heard as a gurgling voice. Two things keep it calm:
 * the gain follows how far the voice stood above the speaker a moment ago rather than in this frame
 * alone, and while somebody speaks a band is never lowered by more than half; the full depth is for the
 * pauses, where there is no voice to spoil and the speaker stands alone.
 *
 * Both the delay between playback and microphone and the share that arrives are learned while [process]
 * is told that nobody talks and the speaker plays at its normal level; until then nothing is changed.
 * Samples come back [delay] samples late.
 */
class EchoTrim(sampleRate: Int) {
    val delay = SIZE
    private val cosine = FloatArray(SIZE / 2) { cos(2 * PI * it / SIZE).toFloat() }
    private val sine = FloatArray(SIZE / 2) { sin(2 * PI * it / SIZE).toFloat() }
    private val reversed = IntArray(SIZE) { Integer.reverse(it) ushr (32 - BITS) }
    /** Its square adds up to one across two half-overlapping frames, so an untouched frame comes back exactly. */
    private val window = FloatArray(SIZE) { sin(PI * it / SIZE).toFloat() }
    private val edges = IntArray(BANDS + 1) { band ->
        (LOW_HZ * (HIGH_HZ.toDouble() / LOW_HZ).pow(band.toDouble() / BANDS) * SIZE / sampleRate).roundToInt()
    }
    private val re = FloatArray(SIZE)
    private val im = FloatArray(SIZE)

    private val micFrame = FloatArray(SIZE)
    private val micOut = FloatArray(HOP)
    private val overlap = FloatArray(SIZE)
    private var micAt = 0
    private val micBands = FloatArray(BANDS)
    private val gains = FloatArray(BANDS) { 1f }
    /** What of each band counted as voice in the frame before. */
    private val voice = FloatArray(BANDS)
    private val voicedBins = VOICED_LOW_HZ * SIZE / sampleRate until VOICED_HIGH_HZ * SIZE / sampleRate
    private var voicedFor = 0
    /** Per bin between the first and last band: the band whose center lies below it, and how far toward the next. */
    private val binBand = IntArray(SIZE / 2)
    private val binBlend = FloatArray(SIZE / 2)
    private val micLevel = FloatArray(WINDOW)
    private val micCenter = LongArray(WINDOW)
    private var micFrames = 0L
    private var learnedInRow = 0

    init {
        for (bin in edges[0] until edges[BANDS]) {
            var band = 0
            while (band < BANDS - 1 && center(band + 1) <= bin) band++
            binBand[bin] = band
            binBlend[bin] = if (band == BANDS - 1) 0f else ((bin - center(band)) / (center(band + 1) - center(band))).coerceIn(0f, 1f)
        }
    }

    private fun center(band: Int) = (edges[band] + edges[band + 1] - 1) / 2f

    private val refFrame = Array(2) { FloatArray(SIZE) }
    private var refAt = 0
    /** Reference frames so far; frame h is centered on reference sample h * [HOP]. */
    private var refFrames = 0L
    private val refBands = Array(RING) { FloatArray(BANDS) }
    private val refLevel = FloatArray(RING)

    /** Microphone position minus reference position of the same sound, or -1 while unknown. */
    var lag = -1
        private set
    private val share = FloatArray(BANDS)
    private val shareCount = IntArray(BANDS)

    /** The playback, [frames] stereo frames that directly follow those of the call before. */
    fun reference(stereo: ShortArray, frames: Int) {
        for (i in 0 until frames) {
            refFrame[0][HOP + refAt] = stereo[2 * i].toFloat()
            refFrame[1][HOP + refAt] = stereo[2 * i + 1].toFloat()
            if (++refAt < HOP) continue
            refAt = 0
            val bands = refBands[(refFrames % RING).toInt()]
            bands.fill(0f)
            for (channel in refFrame) {
                transform(channel)
                addBands(bands, 0.5f)
                channel.copyInto(channel, 0, HOP, SIZE)
            }
            var total = 0f
            for (band in bands) total += band
            refLevel[(refFrames % RING).toInt()] = ln(total + QUIET)
            refFrames++
        }
    }

    /**
     * Replaces the first [count] [samples] with the trimmed samples from [delay] samples earlier.
     * [index] is the microphone position of the first sample, [speaker] the speaker's level against
     * normal while they were recorded, and [learn] whether they hold the speaker alone at that level.
     */
    fun process(samples: FloatArray, count: Int, index: Long, speaker: Float, learn: Boolean) {
        for (i in 0 until count) {
            micFrame[HOP + micAt] = samples[i]
            samples[i] = micOut[micAt]
            if (++micAt < HOP) continue
            micAt = 0
            frame(index + i + 1 - HOP, speaker, learn)
        }
    }

    /** One microphone frame centered on microphone position [center]. */
    private fun frame(center: Long, speaker: Float, learn: Boolean) {
        transform(micFrame)
        micBands.fill(0f)
        addBands(micBands, 1f)
        var total = 0f
        for (band in micBands) total += band
        val slot = (micFrames % WINDOW).toInt()
        micLevel[slot] = ln(total + QUIET)
        micCenter[slot] = center
        micFrames++
        learnedInRow = if (learn) learnedInRow + 1 else 0
        if (learnedInRow >= WINDOW && learnedInRow % SEARCH_EVERY == 0) findLag()

        var low = 0f
        for (bin in voicedBins) low += re[bin] * re[bin] + im[bin] * im[bin]
        voicedFor = if (low > VOICED) VOICED_HOLD else maxOf(0, voicedFor - 1)
        val floor = if (voicedFor > 0) VOICED_MIN_GAIN else MIN_GAIN

        val at = if (lag < 0) -1L else Math.floorDiv(center - lag + HOP / 2, HOP.toLong())
        val known = at >= 1 && at + 1 < refFrames && at - 1 > refFrames - RING
        for (band in 0 until BANDS) {
            val heard = micBands[band]
            var gain = 1f
            if (known) {
                val before = refBands[((at - 1) % RING).toInt()][band]
                val here = refBands[(at % RING).toInt()][band]
                val after = refBands[((at + 1) % RING).toInt()][band]
                if (learn) learnShare(band, (before + here + after) / 3)
                // The loudest of the three frames, so a beat that arrives a frame early or late is covered.
                val explained = STRENGTH * exp(share[band]) * speaker * speaker * maxOf(before, here, after)
                if (shareCount[band] >= SHARE_READY && explained > 0f) {
                    val above = STEADY * voice[band] / explained + (1 - STEADY) * maxOf(heard / explained - 1f, 0f)
                    gain = maxOf(floor, sqrt(above / (1f + above)))
                }
            }
            gains[band] = gain
            voice[band] = gain * gain * heard
        }
        // Blended between the bands' centers: a step in the spectrum would ring.
        for (bin in edges[0] until edges[BANDS]) {
            val band = binBand[bin]
            val blend = binBlend[bin]
            val gain = if (blend == 0f) gains[band] else gains[band] * (1 - blend) + gains[band + 1] * blend
            re[bin] *= gain
            im[bin] *= gain
            re[SIZE - bin] *= gain
            im[SIZE - bin] *= gain
        }
        fft(inverse = true)
        for (i in 0 until SIZE) overlap[i] += re[i] * window[i]
        overlap.copyInto(micOut, 0, 0, HOP)
        overlap.copyInto(overlap, 0, HOP, SIZE)
        overlap.fill(0f, HOP, SIZE)
        micFrame.copyInto(micFrame, 0, HOP, SIZE)
    }

    private fun learnShare(band: Int, played: Float) {
        if (played < REFERENCE_FLOOR) return
        val arrived = ln((micBands[band] + QUIET) / played)
        val count = shareCount[band]
        share[band] += (arrived - share[band]) * maxOf(1f / (count + 1), SHARE_RATE)
        if (count < SHARE_READY) shareCount[band] = count + 1
    }

    /**
     * Finds the delay at which the microphone's level over the last [WINDOW] frames follows the
     * playback's best. Music rises and falls all the time, so the right delay stands out clearly; a
     * microphone that does not hear the speaker (headphones) matches nowhere and leaves [lag] alone.
     */
    private fun findLag() {
        var best = -1
        var bestMatch = MIN_MATCH
        for (step in 0 until LAG_STEPS) {
            val candidate = step * HOP
            var sumMic = 0.0
            var sumRef = 0.0
            var sumMicMic = 0.0
            var sumRefRef = 0.0
            var sumMicRef = 0.0
            var complete = true
            for (slot in 0 until WINDOW) {
                val at = Math.floorDiv(micCenter[slot] - candidate + HOP / 2, HOP.toLong())
                if (at < 0 || at >= refFrames || at <= refFrames - RING) { complete = false; break }
                val mic = micLevel[slot].toDouble()
                val ref = refLevel[(at % RING).toInt()].toDouble()
                sumMic += mic
                sumRef += ref
                sumMicMic += mic * mic
                sumRefRef += ref * ref
                sumMicRef += mic * ref
            }
            if (!complete) continue
            val spread = (sumMicMic - sumMic * sumMic / WINDOW) * (sumRefRef - sumRef * sumRef / WINDOW)
            if (spread <= 0.0) continue
            val match = (sumMicRef - sumMic * sumRef / WINDOW) / sqrt(spread)
            if (match > bestMatch) { bestMatch = match; best = candidate }
        }
        if (best >= 0) lag = best
    }

    /** Windows [frame] and leaves its spectrum in [re] and [im]. */
    private fun transform(frame: FloatArray) {
        for (i in 0 until SIZE) {
            re[i] = frame[i] * window[i]
            im[i] = 0f
        }
        fft(inverse = false)
    }

    private fun addBands(bands: FloatArray, weight: Float) {
        for (band in 0 until BANDS) {
            var sum = 0f
            for (bin in edges[band] until edges[band + 1]) sum += re[bin] * re[bin] + im[bin] * im[bin]
            bands[band] += sum * weight
        }
    }

    private fun fft(inverse: Boolean) {
        for (i in 0 until SIZE) {
            val j = reversed[i]
            if (j > i) {
                val r = re[i]; re[i] = re[j]; re[j] = r
                val m = im[i]; im[i] = im[j]; im[j] = m
            }
        }
        var length = 2
        while (length <= SIZE) {
            val half = length / 2
            val stride = SIZE / length
            var start = 0
            while (start < SIZE) {
                for (k in 0 until half) {
                    val wr = cosine[k * stride]
                    val wi = if (inverse) sine[k * stride] else -sine[k * stride]
                    val a = start + k
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                start += length
            }
            length *= 2
        }
        if (inverse) for (i in 0 until SIZE) re[i] /= SIZE
    }

    companion object {
        private const val BITS = 9
        /** 10.7 ms at 48 kHz: short enough to follow a beat, long enough for bands 100 Hz apart. */
        private const val SIZE = 1 shl BITS
        private const val HOP = SIZE / 2
        const val LOW_HZ = 1_500
        const val HIGH_HZ = 8_000
        private const val BANDS = 10
        /** Reference frames kept: the window searched, the longest delay and a late microphone on top. */
        private const val RING = 512
        /** One second of frames decides the delay, looked at again every half second. */
        private const val WINDOW = 188
        private const val SEARCH_EVERY = 94
        /** Up to 250 ms; the Thor needs 60-75 ms. */
        private const val LAG_STEPS = 48
        /** Measured on the Thor: 0.99 with the speaker audible; noise alone stays far below. */
        private const val MIN_MATCH = 0.9
        /** Half a second of playback in a band before it is trusted, then it follows over about a second. */
        private const val SHARE_READY = 100
        private const val SHARE_RATE = 0.005f
        /** Band energy of about -60 dBFS: quieter playback teaches nothing about the speaker. */
        private const val REFERENCE_FLOOR = 2e7f
        private const val QUIET = 1f
        /** The speaker counts for twice what was learned: its level in a frame is known to about 3.5 dB only. */
        private const val STRENGTH = 2f
        /** How much of a band's gain comes from the frame before. */
        private const val STEADY = 0.9f
        /** -20 dB at most, so a band never switches off audibly. */
        private const val MIN_GAIN = 0.1f
        /** -6 dB at most while somebody speaks. Measured on recordings: the voice's highs then change by
         *  a third of what the full depth does to them, at half the speaker removed. */
        private const val VOICED_MIN_GAIN = 0.5f
        /** Every voiced sound has energy here; the Thor's lowered speaker has none, and a hand on the case stays below. */
        private const val VOICED_LOW_HZ = 300
        private const val VOICED_HIGH_HZ = 1_200
        /** Energy of about -54 dBFS in that range, the level [VoiceLeveler] takes for a voice. */
        private const val VOICED = 2.8e8f
        /** About 250 ms: consonants at the end of a word still count as speech. */
        private const val VOICED_HOLD = 47
    }
}
