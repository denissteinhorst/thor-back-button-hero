package dev.steinhorst.backbuttonhero

import android.media.AudioRecord
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock

/**
 * The recorder's audio mix, bit for bit: the game's stereo playback and the microphone, through the
 * voice chain, the push-to-talk gate, the fades and both duck modes, for fixed input signals. Every
 * five 20 ms chunks of output are reduced to one CRC, so any changed sample shows.
 * Frozen in `src/test/resources/commands/recordermix.txt`, recorded once on the pre-R1 baseline;
 * UPDATE_COMMAND_FIXTURES=1 re-records, only for an approved change.
 */
@RunWith(RobolectricTestRunner::class)
class RecorderMixTest {
    private val frames = 48_000 / 50

    private fun field(target: Any, name: String): java.lang.reflect.Field =
        generateSequence<Class<*>>(target.javaClass) { it.superclass }.firstNotNullOf { c ->
            runCatching { c.getDeclaredField(name) }.getOrNull()
        }.apply { isAccessible = true }

    private fun method(target: Any, name: String, vararg types: Class<*>) =
        target.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }

    /** An [AudioRecord] that is never started: the mix only asks whether a microphone exists. */
    private fun silentRecord(): AudioRecord {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, AudioRecord::class.java) as AudioRecord
    }

    /** Hands [count] microphone samples to the audio as its reader thread would. */
    private fun feedMic(audio: Any, samples: ShortArray, count: Int) {
        val ring = runCatching { field(audio, "micRing").get(audio) }.getOrNull()
        if (ring != null) {
            method(ring, "write", ShortArray::class.java, Int::class.javaPrimitiveType!!).invoke(ring, samples, count)
            return
        }
        val buffer = field(audio, "micBuffer").get(audio) as ShortArray
        val write = field(audio, "micWrite"); val read = field(audio, "micRead")
        val countField = field(audio, "micCount"); val written = field(audio, "micWritten")
        for (i in 0 until count) {
            buffer[write.getInt(audio)] = samples[i]
            write.setInt(audio, (write.getInt(audio) + 1) % buffer.size)
            if (countField.getInt(audio) == buffer.size) read.setInt(audio, (read.getInt(audio) + 1) % buffer.size)
            else countField.setInt(audio, countField.getInt(audio) + 1)
        }
        written.setLong(audio, written.getLong(audio) + count)
    }

    private fun scenario(out: StringBuilder, label: String, microphone: Boolean, pushToTalk: Boolean, duck: String,
                         talks: Map<Int, Boolean>, chunks: Int = 240, silent: IntRange = IntRange.EMPTY, micExtra: Int = 0) {
        val type = Class.forName("dev.steinhorst.backbuttonhero.ScreenRecorder\$PlaybackAudio")
        val audio = type.getDeclaredConstructor(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java)
            .apply { isAccessible = true }.newInstance(microphone, pushToTalk, duck)
        if (microphone) field(audio, "mic").set(audio, silentRecord())
        val mix = method(audio, "mix", ShortArray::class.java, Int::class.javaPrimitiveType!!, ShortArray::class.java)
        val talk = method(audio, "talk", Boolean::class.javaPrimitiveType!!)
        out.append("## ").append(label).append('\n')
        var seed = 12345L
        fun noise(): Int { seed = (seed * 1103515245L + 12345L) and 0x7fffffffL; return ((seed shr 16) % 401L).toInt() - 200 }
        val crc = CRC32()
        val bytes = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (c in 0 until chunks) {
            talks[c]?.let {
                talk.invoke(audio, it)
                // The release restores the speaker on its own thread after a short delay; let it finish.
                if (!it) Thread.sleep(400)
            }
            ShadowSystemClock.advanceBy(Duration.ofMillis(20))
            val chunk = ShortArray(frames * 2)
            val mic = ShortArray(frames + micExtra)
            for (i in 0 until frames + micExtra) {
                val t = (c * frames + i) / 48_000.0
                val speaking = if (c in silent) 0.0 else 3000.0
                // Fully silent for a moment, so only the decay keeps the earlier level.
                val hiss = noise().takeIf { c !in silent } ?: 0
                mic[i] = ((speaking * sin(2 * PI * 220 * t) * (0.5 + 0.5 * sin(2 * PI * 3 * t))).toInt() + hiss).toShort()
                if (i >= frames) continue
                // A game that gets quiet in the middle, so the duck depth has something to learn.
                val level = if (c in 90..110) 800.0 else 6000.0
                chunk[i * 2] = (level * sin(2 * PI * 440 * t)).toInt().toShort()
                chunk[i * 2 + 1] = (level * sin(2 * PI * 660 * t)).toInt().toShort()
            }
            if (microphone) feedMic(audio, mic, frames + micExtra)
            mix.invoke(audio, chunk, frames, ShortArray(frames))
            bytes.clear()
            bytes.asShortBuffer().put(chunk)
            crc.update(bytes.array())
            if (c % 5 == 4) {
                out.append(c - 4).append('-').append(c).append(' ').append(java.lang.Long.toHexString(crc.value)).append('\n')
                crc.reset()
            }
        }
    }

    @Test
    fun `recorder audio mix keeps every sample`() {
        val out = StringBuilder()
        val talk = mapOf(60 to true, 140 to false, 170 to true, 175 to false)
        scenario(out, "microphone always open", microphone = true, pushToTalk = false, duck = "off", talks = emptyMap())
        scenario(out, "push to talk, no duck", microphone = true, pushToTalk = true, duck = "off", talks = talk)
        scenario(out, "push to talk, duck video", microphone = true, pushToTalk = true, duck = "video", talks = talk)
        scenario(out, "push to talk, duck device", microphone = true, pushToTalk = true, duck = "device", talks = talk)
        // Silence that reaches the mix (150 ms behind the microphone) just before the press: what the closed microphone heard decays.
        scenario(out, "push to talk after a quiet moment", microphone = true, pushToTalk = true, duck = "video",
            talks = mapOf(60 to true, 140 to false), chunks = 160, silent = 46..52)
        // The microphone's clock runs ahead of the playback's; the buffer is kept from lagging further.
        scenario(out, "microphone clock ahead", microphone = true, pushToTalk = false, duck = "off", talks = emptyMap(),
            chunks = 120, micExtra = 120)
        scenario(out, "no microphone", microphone = false, pushToTalk = false, duck = "off", talks = emptyMap(), chunks = 20)
        scenario(out, "no microphone, push to talk, duck video", microphone = false, pushToTalk = true, duck = "video", talks = talk)
        val file = File("src/test/resources/commands/recordermix.txt")
        if (System.getenv("UPDATE_COMMAND_FIXTURES") == "1") {
            file.parentFile?.mkdirs()
            file.writeText(out.toString())
            return
        }
        if (!file.exists()) fail("Fixture $file is missing; it is frozen evidence and is never regenerated to pass.")
        assertEquals("recorder mix differs from the frozen fixture", file.readText(), out.toString())
    }
}
