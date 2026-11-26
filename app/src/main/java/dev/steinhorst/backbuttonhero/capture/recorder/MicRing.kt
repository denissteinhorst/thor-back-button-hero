package dev.steinhorst.backbuttonhero.capture.recorder

/**
 * The microphone's samples between its reader thread and the mix: a ring of [size] samples that
 * drops the oldest when full. The mix takes samples only once more than [delay] are buffered (the
 * delay that lets push to talk cut the button clicks out), and the two clocks' drift is kept from
 * piling up more than [maxBacklog] samples. Every sample written is counted, so push-to-talk edges
 * can be placed on a sample position.
 */
internal class MicRing(size: Int, private val delay: Int, private val maxBacklog: Int) {
    private val buffer = ShortArray(size)
    private var read = 0
    private var write = 0
    private var count = 0
    private var written = 0L

    /** Position of the first sample the last [pull] handed out. */
    var pulledFrom = 0L
        private set

    fun write(samples: ShortArray, n: Int) = synchronized(this) {
        for (i in 0 until n) {
            buffer[write] = samples[i]
            write = (write + 1) % buffer.size
            if (count == buffer.size) read = (read + 1) % buffer.size else count++
        }
        written += n
    }

    /** Microphone samples written so far. */
    fun written(): Long = synchronized(this) { written }

    /** Copies up to [frames] samples into [mono] and returns how many. */
    fun pull(frames: Int, mono: ShortArray): Int = synchronized(this) {
        // The two clocks drift apart; keep the microphone from lagging more than a few chunks behind.
        while (count > maxBacklog) {
            read = (read + 1) % buffer.size
            count--
        }
        pulledFrom = written - count
        val n = minOf(frames, maxOf(0, count - delay))
        for (i in 0 until n) {
            mono[i] = buffer[read]
            read = (read + 1) % buffer.size
        }
        count -= n
        n
    }
}
