package dev.steinhorst.backbuttonhero.capture

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Keeps the microphone to the band a voice lives in. The Thor's microphone is as loud above 8 kHz as in
 * the speech band, so leveling it raises a hiss that makes the voice sound tinny; below 100 Hz it picks
 * up handling noise and the speaker through the case.
 */
class VoiceBand(sampleRate: Int) {
    private val stages = arrayOf(
        Biquad(sampleRate, LOW_CUT_HZ, highPass = true),
        Biquad(sampleRate, HIGH_CUT_HZ, highPass = false),
        Biquad(sampleRate, HIGH_CUT_HZ, highPass = false),
    )

    /** Writes [count] filtered samples of [input] to [out]. */
    fun process(input: ShortArray, count: Int, out: FloatArray) {
        for (i in 0 until count) {
            var sample = input[i].toFloat()
            for (stage in stages) sample = stage.next(sample)
            out[i] = sample
        }
    }

    /** A second-order Butterworth section from the bilinear transform. */
    class Biquad(sampleRate: Int, corner: Int, highPass: Boolean) {
        private val b0: Float
        private val b1: Float
        private val b2: Float
        private val a1: Float
        private val a2: Float
        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f

        init {
            val w = 2 * PI * corner / sampleRate
            val alpha = sin(w) / (2 * BUTTERWORTH_Q)
            val c = cos(w)
            val a0 = 1 + alpha
            val edge = if (highPass) (1 + c) / 2 else (1 - c) / 2
            b0 = (edge / a0).toFloat()
            b1 = ((if (highPass) -2 * edge else 2 * edge) / a0).toFloat()
            b2 = b0
            a1 = (-2 * c / a0).toFloat()
            a2 = ((1 - alpha) / a0).toFloat()
        }

        fun next(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }
    }

    companion object {
        const val LOW_CUT_HZ = 100
        const val HIGH_CUT_HZ = 7_000
        private const val BUTTERWORTH_Q = 0.7071
    }
}

/**
 * Decides per microphone sample whether a push-to-talk talk is open. The button sits next to the
 * microphone, so its press and release clicks reach the recording; the microphone opens [openLate]
 * samples after the press and closes [closeEarly] samples before the release, which the recorder can
 * honour because it plays the microphone with a delay longer than that.
 */
class TalkGate(private val openLate: Int, private val closeEarly: Int) {
    private val edges = ArrayDeque<Pair<Long, Boolean>>()
    private var open = false

    /** [late] replaces [openLate] for a talk that has to wait longer, e.g. for the speaker to fall silent. */
    fun press(at: Long, late: Int = openLate) = synchronized(edges) { edges.addLast(at + late to true) }

    fun release(at: Long) = synchronized(edges) { edges.addLast(at - closeEarly to false) }

    /** Whether the sample at [index] belongs to a talk; [index] must not go backwards between calls. */
    fun isOpen(index: Long): Boolean = synchronized(edges) {
        // A release closing before its own press opened cancels that talk at once.
        while (edges.isNotEmpty() && edges.first().first <= index) open = edges.removeFirst().second
        open
    }
}
