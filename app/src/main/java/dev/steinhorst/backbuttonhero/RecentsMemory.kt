package dev.steinhorst.backbuttonhero

import android.app.ActivityManager
import android.content.Context
import java.io.File

/** Matches OEM MeminfoHelper: kernel MemAvailable, scaled to rounded advertised total memory. */
data class RecentsMemory(val available: Long, val total: Long) {
    companion object {
        fun read(context: Context): RecentsMemory {
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
            return calculate(runCatching { File("/proc/meminfo").readText() }.getOrNull(), memory.totalMem, memory.availMem)
        }

        fun calculate(proc: String?, totalBytes: Long, systemAvailable: Long): RecentsMemory {
            var value = totalBytes.toFloat()
            var multiplier = 1L
            repeat(4) { if (value > 900f) { value /= 1000f; multiplier *= 1000 } }
            val total = Math.round(value).toLong() * multiplier
            val fields = proc.orEmpty().lineSequence().mapNotNull {
                val parts = it.split(':', limit = 2)
                if (parts.size != 2) null else parts[1].trim().removeSuffix("kB").trim().toLongOrNull()?.let { kb -> parts[0] to kb }
            }.toMap()
            // OEM converts proc kB to decimal bytes. Both conversions cancel in the ratio,
            // but preserve its Float arithmetic so formatting lands on the same boundaries.
            val realTotal = fields["MemTotal"]?.takeIf { it > 0 }?.times(1000) ?: totalBytes
            val realAvailable = fields["MemAvailable"]?.takeIf { it >= 0 }?.times(1000) ?: systemAvailable
            val available = if (realTotal > 0) (realAvailable.toFloat() * (total.toFloat() / realTotal.toFloat())).toLong() else realAvailable
            return RecentsMemory(available, total)
        }
    }
}
