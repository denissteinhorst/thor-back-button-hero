package dev.steinhorst.backbuttonhero

import android.annotation.SuppressLint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.VolumeShaper
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.StatFs
import android.os.SystemClock
import android.view.Surface
import dev.steinhorst.backbuttonhero.capture.EchoTrim
import dev.steinhorst.backbuttonhero.capture.GameBed
import dev.steinhorst.backbuttonhero.capture.TalkGate
import dev.steinhorst.backbuttonhero.capture.VoiceBand
import dev.steinhorst.backbuttonhero.capture.VoiceLeveler
import dev.steinhorst.backbuttonhero.capture.VoicePolish
import dev.steinhorst.backbuttonhero.prefs.Prefs
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Root side of the video recording, run through pservice in its own app_process VM. A normal app
 * can neither mirror the bottom screen nor record other apps' sound without a consent dialog per
 * recording; as root, every screen's layer stack can be mirrored into an encoder and an audio policy
 * can copy the playback into a recorder while it keeps playing on the speaker.
 *
 * The process registers a binder service and waits for [TRANSACTION_START]. It records until
 * [TRANSACTION_STOP], until the client that started it dies, or until storage runs low, and exits
 * afterwards. Everything goes through the platform's own encoders; nothing here bundles a codec.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi")
object ScreenRecorder {
    const val SERVICE = "backbuttonhero_recorder"
    const val DESCRIPTOR = "dev.steinhorst.backbuttonhero.IScreenRecorder"
    const val TRANSACTION_START = IBinder.FIRST_CALL_TRANSACTION
    const val TRANSACTION_STOP = IBinder.FIRST_CALL_TRANSACTION + 1
    /** One-way: an int, non-zero while the push-to-talk button is held. */
    const val TRANSACTION_TALK = IBinder.FIRST_CALL_TRANSACTION + 2
    const val MODE_TOP = Prefs.DISPLAY_TOP
    const val MODE_BOTTOM = Prefs.DISPLAY_BOTTOM
    const val MODE_BOTH = Prefs.TARGET_BOTH

    private const val START_TIMEOUT_MS = 15_000L
    private const val STORAGE_CHECK_MS = 5_000L
    private const val MIN_FREE_BYTES = 300L * 1024 * 1024

    private lateinit var main: Handler
    @Volatile private var recording: Recording? = null
    private val lock = Any()

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            Looper.prepareMainLooper()
            main = Handler(Looper.getMainLooper())
            val service = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == INTERFACE_TRANSACTION) {
                        reply?.writeString(DESCRIPTOR)
                        return true
                    }
                    if (code != TRANSACTION_START && code != TRANSACTION_STOP && code != TRANSACTION_TALK) {
                        return super.onTransact(code, data, reply, flags)
                    }
                    data.enforceInterface(DESCRIPTOR)
                    if (code == TRANSACTION_TALK) {
                        val talking = data.readInt() != 0
                        // Lowering other apps' players needs this process's own privileges.
                        val identity = clearCallingIdentity()
                        try { recording?.talk(talking) } finally { restoreCallingIdentity(identity) }
                        return true
                    }
                    val starting = code == TRANSACTION_START
                    val client = if (starting) data.readStrongBinder() else null
                    val args = if (starting) data.createStringArray() else null
                    // Audio records created inside a transaction are attributed to its caller, the app,
                    // which may not record the microphone; this process records as itself.
                    val identity = clearCallingIdentity()
                    try {
                        reply?.writeString(if (starting) start(client, args) else stop())
                    } finally {
                        restoreCallingIdentity(identity)
                    }
                    return true
                }
            }
            Class.forName("android.os.ServiceManager").methods
                .single { it.name == "addService" && it.parameterTypes.size == 4 }
                .invoke(null, SERVICE, service, false, 0)
            println("OK")
            main.postDelayed({ if (recording == null) exit() }, START_TIMEOUT_MS)
            Looper.loop()
        } catch (t: Throwable) {
            println("ERR ${t.javaClass.simpleName}: ${t.cause?.message ?: t.message}")
            exit()
        }
    }

    /** args: path, mode, top display id, bottom display id, bit rate, microphone ("1"/"0"),
     *  push to talk ("1"/"0"), where talking lowers the game sound ([Prefs.DUCK_OFF] etc.). */
    private fun start(client: IBinder?, args: Array<String>?): String = synchronized(lock) {
        if (recording != null) return "ERR busy"
        if (client == null || args == null || args.size != 8) return "ERR arguments"
        return try {
            val config = Config(
                File(args[0]), args[1], args[2].toInt(), args[3].toInt(), args[4].toInt(), args[5] == "1",
                args[6] == "1", args[7],
            )
            val started = Recording(config) { reason -> main.post { finish("self: $reason") } }
            started.start()
            recording = started
            client.linkToDeath({ main.post { finish("client died") } }, 0)
            main.postDelayed(object : Runnable {
                override fun run() {
                    if (recording == null) return
                    val free = runCatching { StatFs(config.file.parent).availableBytes }.getOrDefault(Long.MAX_VALUE)
                    if (free < MIN_FREE_BYTES) finish("storage") else main.postDelayed(this, STORAGE_CHECK_MS)
                }
            }, STORAGE_CHECK_MS)
            "OK"
        } catch (t: Throwable) {
            exitSoon()
            "ERR ${t.javaClass.simpleName}: ${t.cause?.message ?: t.message}"
        }
    }

    private fun stop(): String = finish("stop")

    private fun finish(reason: String): String {
        val active = synchronized(lock) { recording.also { recording = null } } ?: return "ERR idle"
        val files = active.stop()
        println("stopped: $reason")
        Thread {
            files.forEach { file ->
                runCatching {
                    Runtime.getRuntime().exec(arrayOf(
                        "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file://${file.absolutePath}",
                    )).waitFor(5, TimeUnit.SECONDS)
                }
            }
            exit()
        }.start()
        return if (files.isEmpty()) "ERR empty" else "OK ${files.size}"
    }

    private fun exitSoon() {
        main.postDelayed(::exit, 500)
    }

    private fun exit(): Nothing {
        System.out.flush()
        Runtime.getRuntime().halt(0)
        throw AssertionError()
    }

    private class Config(
        val file: File,
        val mode: String,
        val topId: Int,
        val bottomId: Int,
        val bitRate: Int,
        val microphone: Boolean,
        val pushToTalk: Boolean,
        val duck: String,
    )

    /** One display's size in its current rotation and the layer stack SurfaceFlinger composes it from. */
    private class Screen(val width: Int, val height: Int, val layerStack: Int)

    private fun screen(displayId: Int): Screen {
        val global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null)
        val info = global.javaClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType).invoke(global, displayId)
            ?: error("display $displayId missing")
        val type = info.javaClass
        return Screen(type.getField("logicalWidth").getInt(info), type.getField("logicalHeight").getInt(info),
            type.getField("layerStack").getInt(info))
    }

    private class Recording(private val config: Config, private val onFailure: (String) -> Unit) {
        @Volatile private var stopping = false
        private val muxLock = Object()
        private var muxer: MuxerSegment? = null
        private val finished = mutableListOf<File>()
        private var videoFormat: MediaFormat? = null
        private var audioFormat: MediaFormat? = null
        private var segment = 1
        /** Asked for when a file nears the 32-bit MP4 limit; the next key frame opens a new file. */
        @Volatile private var rollPending = false
        private lateinit var video: MediaCodec
        private lateinit var compositor: Compositor
        private val displays = mutableListOf<IBinder>()
        private val audio = PlaybackAudio(config.microphone, config.pushToTalk, config.duck)
        private val threads = mutableListOf<Thread>()

        fun start() {
            val top = screen(config.topId)
            val bottom = if (config.mode == MODE_TOP) null else screen(config.bottomId)
            val (width, height) = when (config.mode) {
                MODE_TOP -> top.width to top.height
                MODE_BOTTOM -> checkNotNull(bottom).width to bottom.height
                else -> maxOf(top.width, checkNotNull(bottom).width) to top.height + bottom.height
            }
            config.file.parentFile?.mkdirs()
            video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            video.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // B-frames would shift the video track's start against the audio in the MP4.
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val input = video.createInputSurface()
            video.start()
            compositor = Compositor(input, width, height)
            when (config.mode) {
                MODE_TOP -> displays += mirror(compositor.addSource(0, 0, top.width, top.height), top)
                MODE_BOTTOM -> displays += mirror(compositor.addSource(0, 0, checkNotNull(bottom).width, bottom.height), bottom)
                else -> {
                    // Like the device: the top screen above, the narrower bottom screen centered below it.
                    checkNotNull(bottom)
                    displays += mirror(compositor.addSource((width - top.width) / 2, bottom.height, top.width, top.height), top)
                    displays += mirror(compositor.addSource((width - bottom.width) / 2, 0, bottom.width, bottom.height), bottom)
                }
            }
            compositor.start()
            val encoder = audio.start()
            threads += Thread({ drain(video, isVideo = true) }, "RecorderVideo")
            threads += Thread({ drain(encoder, isVideo = false) }, "RecorderAudioOut")
            threads += Thread({ audio.feed { stopping } }, "RecorderAudioIn")
            threads.forEach(Thread::start)
        }

        fun talk(talking: Boolean) = audio.talk(talking)

        /** Ends the recording and returns every file it wrote. Safe to call once. */
        fun stop(): List<File> {
            stopping = true
            compositor.stop()
            displays.forEach { runCatching { destroyDisplay(it) } }
            runCatching { video.signalEndOfInputStream() }
            threads.forEach { it.join(JOIN_MS) }
            audio.release()
            compositor.release()
            synchronized(muxLock) { closeSegment() }
            return finished.toList()
        }

        private fun drain(codec: MediaCodec, isVideo: Boolean) {
            val info = MediaCodec.BufferInfo()
            try {
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, DEQUEUE_US)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        synchronized(muxLock) {
                            if (isVideo) videoFormat = codec.outputFormat else audioFormat = codec.outputFormat
                            if (muxer == null && videoFormat != null && audioFormat != null) openSegment()
                        }
                    } else if (index >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            write(codec.getOutputBuffer(index)!!, info, isVideo)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    } else if (stopping && isVideo && index == MediaCodec.INFO_TRY_AGAIN_LATER && compositor.idleAfterStop()) {
                        // An encoder that never reports the end of the stream must not hold the file open.
                        break
                    }
                }
            } catch (t: Throwable) {
                if (!stopping) onFailure("${if (isVideo) "video" else "audio"}: ${t.javaClass.simpleName}")
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
        }

        private fun write(buffer: ByteBuffer, info: MediaCodec.BufferInfo, isVideo: Boolean) = synchronized(muxLock) {
            if (isVideo && rollPending && info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) {
                rollPending = false
                closeSegment()
                segment++
                openSegment()
            }
            val target = muxer ?: return
            if (target.basePts < 0) {
                // Each file starts on a video frame, which the encoder always makes a key frame.
                if (!isVideo) return
                target.basePts = info.presentationTimeUs
            }
            if (info.presentationTimeUs < target.basePts) return
            val pts = info.presentationTimeUs
            info.presentationTimeUs = pts - target.basePts
            target.muxer.writeSampleData(if (isVideo) target.videoTrack else target.audioTrack, buffer, info)
            info.presentationTimeUs = pts
            target.bytes += info.size
            if (!rollPending && target.bytes > SEGMENT_BYTES) {
                rollPending = true
                video.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
            }
        }

        private fun openSegment() {
            val file = if (segment == 1) config.file
            else File(config.file.parentFile, "${config.file.nameWithoutExtension}_$segment.${config.file.extension}")
            val created = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val videoTrack = created.addTrack(checkNotNull(videoFormat))
            val audioTrack = created.addTrack(checkNotNull(audioFormat))
            created.start()
            muxer = MuxerSegment(file, created, videoTrack, audioTrack)
        }

        private fun closeSegment() {
            val open = muxer ?: return
            muxer = null
            val ok = open.basePts >= 0 && runCatching { open.muxer.stop() }.isSuccess
            runCatching { open.muxer.release() }
            if (ok) finished += open.file else open.file.delete()
        }

        private fun mirror(target: Surface, screen: Screen): IBinder {
            val control = Class.forName("android.view.SurfaceControl")
            // Not a secure display: content an app marks secure stays black, as in the system recorder.
            val token = control.getMethod("createDisplay", String::class.java, Boolean::class.javaPrimitiveType)
                .invoke(null, "BackButtonHero recording", false) as IBinder
            control.getMethod("openTransaction").invoke(null)
            try {
                control.getMethod("setDisplaySurface", IBinder::class.java, Surface::class.java).invoke(null, token, target)
                val rect = Rect(0, 0, screen.width, screen.height)
                control.getMethod("setDisplayProjection", IBinder::class.java, Int::class.javaPrimitiveType, Rect::class.java, Rect::class.java)
                    .invoke(null, token, 0, rect, rect)
                control.getMethod("setDisplayLayerStack", IBinder::class.java, Int::class.javaPrimitiveType)
                    .invoke(null, token, screen.layerStack)
            } finally {
                control.getMethod("closeTransaction").invoke(null)
            }
            return token
        }

        private fun destroyDisplay(token: IBinder) {
            Class.forName("android.view.SurfaceControl").getMethod("destroyDisplay", IBinder::class.java).invoke(null, token)
        }
    }

    private class MuxerSegment(val file: File, val muxer: MediaMuxer, val videoTrack: Int, val audioTrack: Int) {
        var basePts = -1L
        var bytes = 0L
    }

    /**
     * Copies the media and game playback into a recorder while it keeps playing (route flags loop back
     * and render), optionally mixed with the microphone. Apps that forbid capture stay silent; the
     * privileged path around that is limited to 16 kHz by Android and not used.
     *
     * With push to talk the microphone fades in only while the button is held, and the game sound can
     * fade down under the voice in the video. On the device the players go far down meanwhile, through
     * the same volume shapers Android itself uses to duck players for a notification: the microphone sits
     * next to the speaker and hears it louder than the voice. What it hears is too distorted to subtract
     * with the captured playback (measured: 6-11 dB at best) and lies almost entirely above 2 kHz, so the
     * speaker has to be quiet enough for the voice to stand above it; [EchoTrim] then lowers the bands
     * the playback explains, and [VoicePolish] the pauses.
     */
    private class PlaybackAudio(private val microphone: Boolean, private val pushToTalk: Boolean, private val duck: String) {
        @Volatile private var talking = !pushToTalk
        private var micGain = if (pushToTalk) 0f else 1f
        private var gameGain = 1f
        private val ducking = pushToTalk && duck != Prefs.DUCK_OFF
        private val bed = GameBed()
        private var bedGain = GameBed.MAX_GAIN
        private var lowered = false
        /** What the closed microphone last heard, mostly the speaker; decides how late a talk opens. */
        @Volatile private var ambient = 0f
        @Volatile private var deviceDucked = false
        private val duckLock = Object()
        private var policy: Any? = null
        private var playback: AudioRecord? = null
        private var mic: AudioRecord? = null
        private lateinit var encoder: MediaCodec
        private val micBuffer = ShortArray(SAMPLE_RATE)
        private var micRead = 0
        private var micWrite = 0
        private var micCount = 0
        /** Microphone samples written so far; push-to-talk edges are placed on this position. */
        private var micWritten = 0L
        /** Position of the first sample the last [pullMic] handed out. */
        private var micPulledFrom = 0L
        private val micLock = Object()
        private val band = VoiceBand(SAMPLE_RATE)
        private val leveler = VoiceLeveler()
        private var leveling = !pushToTalk
        private val polish = VoicePolish(SAMPLE_RATE)
        /** Only with push to talk: without it the speaker stays loud and there is no pause to learn in. */
        private val trim = if (pushToTalk) EchoTrim(SAMPLE_RATE) else null
        @Volatile private var speakerChangedMs = 0L
        /** The talk gate's decisions, as late as [trim] and [polish] hand the samples they belong to back. */
        private val openLine = BooleanArray(polish.delay + (trim?.delay ?: 0)) { !pushToTalk }
        private var openAt = 0
        private val gate = TalkGate(TALK_OPEN_LATE_FRAMES, TALK_CLOSE_EARLY_FRAMES)
        private val voice = FloatArray(CHUNK_FRAMES)
        private val open = BooleanArray(CHUNK_FRAMES)
        private var speakerRestore = 0

        @SuppressLint("MissingPermission") // runs as uid 0, which the audio server trusts
        fun start(): MediaCodec {
            val format = AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
            val addRule = ruleBuilderClass.getMethod("addRule", AudioAttributes::class.java, Int::class.javaPrimitiveType)
            listOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN).forEach { usage ->
                addRule.invoke(ruleBuilder, AudioAttributes.Builder().setUsage(usage).build(), RULE_MATCH_ATTRIBUTE_USAGE)
            }
            val rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)
            val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val mixBuilder = mixBuilderClass.getConstructor(rule.javaClass).newInstance(rule)
            mixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
            mixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixBuilder, ROUTE_FLAG_LOOP_BACK or ROUTE_FLAG_RENDER)
            val mix = mixBuilderClass.getMethod("build").invoke(mixBuilder)
            val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            val policyBuilder = policyBuilderClass.getConstructor(android.content.Context::class.java).newInstance(null)
            policyBuilderClass.getMethod("addMix", mix.javaClass).invoke(policyBuilder, mix)
            val built = policyBuilderClass.getMethod("build").invoke(policyBuilder)
            val register = Class.forName("android.media.AudioManager").getDeclaredMethod("registerAudioPolicyStatic", built.javaClass)
            register.isAccessible = true
            check(register.invoke(null, built) == 0) { "audio policy refused" }
            policy = built
            playback = (built.javaClass.getMethod("createAudioRecordSink", mix.javaClass).invoke(built, mix) as AudioRecord)
                .also { check(it.state == AudioRecord.STATE_INITIALIZED) { "playback capture unavailable" } }
            if (microphone) {
                mic = runCatching {
                    // Measured on the Thor: MIC on the bottom microphone carries a voice best; the speech
                    // sources and the back microphone deliver it 4-8 dB quieter.
                    AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, SAMPLE_RATE / 5 * 2)
                }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_FRAMES * 4)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            return encoder
        }

        /** Reads the playback in real time and queues it, mixed, into the encoder until [stopped]. */
        fun feed(stopped: () -> Boolean) {
            val record = checkNotNull(playback)
            mic?.let { source ->
                source.startRecording()
                Thread({ readMic(source, stopped) }, "RecorderMic").start()
            }
            record.startRecording()
            val startUs = System.nanoTime() / 1000
            var frames = 0L
            val chunk = ShortArray(CHUNK_FRAMES * 2)
            val micChunk = ShortArray(CHUNK_FRAMES)
            val bytes = ByteBuffer.allocate(chunk.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            try {
                while (!stopped()) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read <= 0) break
                    trim?.reference(chunk, read / 2)
                    mix(chunk, read / 2, micChunk)
                    bytes.clear()
                    bytes.asShortBuffer().put(chunk, 0, read)
                    queue(bytes.array(), read * 2, startUs + frames * 1_000_000L / SAMPLE_RATE, 0)
                    frames += read / 2
                }
            } finally {
                queue(ByteArray(0), 0, startUs + frames * 1_000_000L / SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        }

        private fun queue(data: ByteArray, size: Int, ptsUs: Long, flags: Int) {
            var offset = 0
            do {
                val index = encoder.dequeueInputBuffer(DEQUEUE_US)
                if (index < 0) continue
                val buffer = checkNotNull(encoder.getInputBuffer(index))
                val length = minOf(buffer.remaining(), size - offset)
                buffer.put(data, offset, length)
                encoder.queueInputBuffer(index, 0, length, ptsUs + offset / 4 * 1_000_000L / SAMPLE_RATE,
                    if (offset + length >= size) flags else 0)
                offset += length
            } while (offset < size)
        }

        private fun readMic(source: AudioRecord, stopped: () -> Boolean) {
            val buffer = ShortArray(CHUNK_FRAMES)
            while (!stopped()) {
                val read = source.read(buffer, 0, buffer.size)
                if (read <= 0) break
                synchronized(micLock) {
                    for (i in 0 until read) {
                        micBuffer[micWrite] = buffer[i]
                        micWrite = (micWrite + 1) % micBuffer.size
                        if (micCount == micBuffer.size) micRead = (micRead + 1) % micBuffer.size else micCount++
                    }
                    micWritten += read
                }
            }
        }

        fun talk(on: Boolean) {
            if (!pushToTalk) return
            talking = on
            val at = synchronized(micLock) { micWritten }
            // A speaker that was audible at the microphone needs its fade and the audio path's latency
            // before it is gone; opened earlier, the leveled microphone would start with a tinny burst.
            val echo = !deviceDucked && ambient > ECHO_LEVEL
            if (on) gate.press(at, if (echo) TALK_OPEN_AFTER_ECHO_FRAMES else TALK_OPEN_LATE_FRAMES) else gate.release(at)
            // The speaker always goes far down while talking; the video follows the duck setting. It comes
            // back only once the delayed microphone has closed.
            val restore = synchronized(duckLock) { ++speakerRestore }
            if (on) {
                duckDevice(true)
            } else {
                Thread({
                    SystemClock.sleep(SPEAKER_RESTORE_DELAY_MS)
                    if (!talking && restore == synchronized(duckLock) { speakerRestore }) duckDevice(false)
                }, "RecorderSpeaker").apply { isDaemon = true }.start()
            }
        }

        /**
         * Adds the microphone to [chunk]'s [frames] stereo frames (a short supply is padded with silence)
         * and moves both levels toward their targets by at most one fade step per frame, so no switch clicks.
         */
        private fun mix(chunk: ShortArray, frames: Int, mono: ShortArray) {
            // The device-side shaper only reaches the speaker, never the captured copy, so the video fades
            // here, and stays down until the delayed voice has faded out.
            val lower = ducking && (talking || micGain > 0f)
            if (ducking && !lower) bed.hear(chunk, frames * 2)
            // One depth per talk, from how loud the game was before it: a level that followed the game
            // while talking would pump.
            if (lower && !lowered) bedGain = bed.gain()
            lowered = lower
            val gameTarget = if (lower) bedGain else 1f
            if (mic == null && gameGain == 1f && gameTarget == 1f) return
            val available = if (mic == null) 0 else pullMic(frames, mono)
            if (available == 0 && gameGain == 1f && gameTarget == 1f) return
            var anyOpen = !pushToTalk
            for (i in 0 until available) {
                open[i] = !pushToTalk || gate.isOpen(micPulledFrom + i)
                if (open[i]) anyOpen = true
            }
            band.process(mono, available, voice)
            // A closed push-to-talk microphone must not teach the leveler the room's noise.
            val closed = micGain == 0f && !anyOpen
            if (closed) ambient = maxOf(rms(voice, available), ambient * AMBIENT_DECAY_PER_CHUNK)
            // While a talk is open the speaker is down; what it plays at its normal level between talks
            // teaches the trim, once the fades and the audio path's latency are over.
            trim?.process(voice, available, micPulledFrom, if (anyOpen) SPEAKER_GAIN else 1f,
                closed && !deviceDucked && SystemClock.uptimeMillis() - speakerChangedMs > SPEAKER_SETTLE_MS)
            if (anyOpen && !leveling) leveler.restart()
            leveling = anyOpen
            if (leveling) leveler.process(voice, available)
            polish.process(voice, available)
            val micStep = 1f / TALK_FADE_FRAMES
            for (i in 0 until frames) {
                if (i < available) {
                    val wasOpen = openLine[openAt]
                    openLine[openAt] = open[i]
                    openAt = (openAt + 1) % openLine.size
                    micGain = toward(micGain, if (wasOpen) 1f else 0f, micStep)
                }
                // The same number of dB per frame: an even fade to the ear, however deep it goes.
                gameGain = if (gameGain > gameTarget) maxOf(gameTarget, gameGain * GAME_FADE_PER_FRAME)
                else minOf(gameTarget, gameGain / GAME_FADE_PER_FRAME)
                val spoken = if (i < available) voice[i] * micGain else 0f
                for (channel in 0..1) {
                    val at = i * 2 + channel
                    chunk[at] = VoiceLeveler.limit(chunk[at] * gameGain + spoken)
                }
            }
        }

        private fun rms(samples: FloatArray, count: Int): Float {
            if (count <= 0) return 0f
            var sum = 0.0
            for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
            return sqrt(sum / count).toFloat()
        }

        private fun toward(value: Float, target: Float, step: Float) = when {
            value < target -> minOf(target, value + step)
            value > target -> maxOf(target, value - step)
            else -> value
        }

        /** Lowers or restores every other app's player on the device, with a short fade. */
        private fun duckDevice(on: Boolean): Unit = synchronized(duckLock) {
            if (on == deviceDucked) return
            deviceDucked = on
            speakerChangedMs = SystemClock.uptimeMillis()
            runCatching {
                val audioBinder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                    .invoke(null, "audio") as IBinder
                val service = Class.forName("android.media.IAudioService\$Stub").getMethod("asInterface", IBinder::class.java)
                    .invoke(null, audioBinder)
                @Suppress("UNCHECKED_CAST")
                val players = service.javaClass.getMethod("getActivePlaybackConfigurations").invoke(service) as List<Any>
                val builder = VolumeShaper.Configuration.Builder()
                    .setCurve(floatArrayOf(0f, 1f), floatArrayOf(1f, SPEAKER_GAIN))
                    .setInterpolatorType(VolumeShaper.Configuration.INTERPOLATOR_TYPE_CUBIC)
                    .setDuration(SPEAKER_FADE_MS)
                // A fixed id lets the reverse find this shaper again instead of stacking a new one.
                VolumeShaper.Configuration.Builder::class.java.getDeclaredMethod("setId", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(builder, DUCK_SHAPER_ID)
                val shape = builder.build()
                val operation = if (on) VolumeShaper.Operation.PLAY else VolumeShaper.Operation.REVERSE
                players.forEach { player ->
                    val usage = (player as AudioPlaybackConfiguration).audioAttributes.usage
                    if (usage !in DUCKED_USAGES) return@forEach
                    runCatching {
                        val proxy = player.javaClass.getMethod("getPlayerProxy").invoke(player) ?: return@runCatching
                        proxy.javaClass.getMethod("applyVolumeShaper", VolumeShaper.Configuration::class.java, VolumeShaper.Operation::class.java)
                            .invoke(proxy, shape, operation)
                    }
                }
            }
        }

        private fun pullMic(frames: Int, mono: ShortArray): Int =
            synchronized(micLock) {
                // The two clocks drift apart; keep the microphone from lagging more than a few chunks
                // behind the delay that lets push-to-talk cut the button clicks out.
                while (micCount > MIC_DELAY_FRAMES + CHUNK_FRAMES * MAX_MIC_LAG_CHUNKS) {
                    micRead = (micRead + 1) % micBuffer.size
                    micCount--
                }
                micPulledFrom = micWritten - micCount
                val n = minOf(frames, maxOf(0, micCount - if (pushToTalk) MIC_DELAY_FRAMES else 0))
                for (i in 0 until n) {
                    mono[i] = micBuffer[micRead]
                    micRead = (micRead + 1) % micBuffer.size
                }
                micCount -= n
                n
            }

        fun release() {
            // Players must never stay lowered once the recording is over.
            if (pushToTalk) duckDevice(false)
            runCatching { playback?.stop() }
            runCatching { playback?.release() }
            runCatching { mic?.stop() }
            runCatching { mic?.release() }
            policy?.let { built ->
                runCatching {
                    val unregister = Class.forName("android.media.AudioManager")
                        .getDeclaredMethod("unregisterAudioPolicyAsyncStatic", built.javaClass)
                    unregister.isAccessible = true
                    unregister.invoke(null, built)
                }
            }
        }
    }

    /**
     * Draws the mirrored screens into the encoder's input surface. A static screen delivers no frames,
     * so it also redraws on a timer; that keeps the video track running for as long as the audio.
     */
    private class Compositor(private val output: Surface, private val width: Int, private val height: Int) {
        private val thread = HandlerThread("RecorderGl").apply { start() }
        private val handler = Handler(thread.looper)
        private lateinit var display: EGLDisplay
        private lateinit var context: EGLContext
        private lateinit var surface: EGLSurface
        private var program = 0
        private val sources = mutableListOf<Source>()
        private var drawPending = false
        private var lastDrawNs = 0L
        @Volatile private var stopped = false
        @Volatile private var lastDrawAfterStopNs = 0L

        private class Source(val texture: SurfaceTexture, val textureId: Int, val x: Int, val y: Int, val width: Int, val height: Int, val surface: Surface)

        init {
            sync { setUp() }
        }

        private fun sync(block: () -> Unit) {
            val done = CountDownLatch(1)
            var failure: Throwable? = null
            handler.post {
                try { block() } catch (t: Throwable) { failure = t } finally { done.countDown() }
            }
            check(done.await(JOIN_MS, TimeUnit.MILLISECONDS)) { "GL thread stuck" }
            failure?.let { throw it }
        }

        private fun setUp() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGLExt.EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "eglChooseConfig" }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            surface = EGL14.eglCreateWindowSurface(display, configs[0], output, intArrayOf(EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent" }
            program = link(VERTEX_SHADER, FRAGMENT_SHADER)
        }

        /** A surface for one mirrored screen, drawn at ([x], [y]) in GL coordinates (origin bottom left). */
        fun addSource(x: Int, y: Int, sourceWidth: Int, sourceHeight: Int): Surface {
            lateinit var created: Surface
            sync {
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                val texture = SurfaceTexture(ids[0]).apply {
                    setDefaultBufferSize(sourceWidth, sourceHeight)
                    setOnFrameAvailableListener({ schedule() }, handler)
                }
                created = Surface(texture)
                sources += Source(texture, ids[0], x, y, sourceWidth, sourceHeight, created)
            }
            return created
        }

        fun start() {
            handler.post(object : Runnable {
                override fun run() {
                    if (stopped) return
                    if (System.nanoTime() - lastDrawNs > IDLE_REDRAW_NS) draw()
                    handler.postDelayed(this, IDLE_CHECK_MS)
                }
            })
        }

        /** Both screens report frames on their own; one composed frame per 1/60 s covers them. */
        private fun schedule() {
            if (drawPending || stopped) return
            drawPending = true
            val wait = (lastDrawNs + FRAME_NS - System.nanoTime()).coerceAtLeast(0) / 1_000_000
            handler.postDelayed({ drawPending = false; draw() }, wait)
        }

        private fun draw() {
            if (stopped) return
            lastDrawNs = System.nanoTime()
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            val matrix = FloatArray(16)
            sources.forEach { source ->
                source.texture.updateTexImage()
                source.texture.getTransformMatrix(matrix)
                GLES20.glViewport(source.x, source.y, source.width, source.height)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, source.textureId)
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexture"), 1, false, matrix, 0)
                quad()
            }
            EGLExt.eglPresentationTimeANDROID(display, surface, lastDrawNs)
            EGL14.eglSwapBuffers(display, surface)
        }

        private fun quad() {
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            val coordinate = GLES20.glGetAttribLocation(program, "aCoordinate")
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, QUAD)
            GLES20.glEnableVertexAttribArray(coordinate)
            GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 0, COORDINATES)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun stop() {
            runCatching { sync { stopped = true; lastDrawAfterStopNs = System.nanoTime() } }
        }

        /** True a moment after [stop]: no frame can still be on its way into the encoder. */
        fun idleAfterStop() = stopped && System.nanoTime() - lastDrawAfterStopNs > STOP_GRACE_NS

        fun release() {
            runCatching {
                sync {
                    sources.forEach { it.surface.release(); it.texture.release() }
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroySurface(display, surface)
                    EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            }
            thread.quitSafely()
        }

        private fun link(vertex: String, fragment: String): Int {
            val linked = GLES20.glCreateProgram()
            listOf(GLES20.GL_VERTEX_SHADER to vertex, GLES20.GL_FRAGMENT_SHADER to fragment).forEach { (type, source) ->
                val shader = GLES20.glCreateShader(type)
                GLES20.glShaderSource(shader, source)
                GLES20.glCompileShader(shader)
                val status = IntArray(1)
                GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
                check(status[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
                GLES20.glAttachShader(linked, shader)
            }
            GLES20.glLinkProgram(linked)
            return linked
        }

        companion object {
            private const val FRAME_NS = 1_000_000_000L / FRAME_RATE
            private const val IDLE_REDRAW_NS = 100_000_000L
            private const val IDLE_CHECK_MS = 50L
            private const val STOP_GRACE_NS = 500_000_000L
            private const val VERTEX_SHADER = "uniform mat4 uTexture; attribute vec4 aPosition; attribute vec4 aCoordinate;" +
                " varying vec2 vCoordinate; void main() { gl_Position = aPosition; vCoordinate = (uTexture * aCoordinate).xy; }"
            private const val FRAGMENT_SHADER = "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float; uniform samplerExternalOES sTexture; varying vec2 vCoordinate;" +
                " void main() { gl_FragColor = texture2D(sTexture, vCoordinate); }"
            private val QUAD = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            private val COORDINATES = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

            private fun floats(vararg values: Float): FloatBuffer =
                ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                    put(values)
                    position(0)
                }
        }
    }

    private const val FRAME_RATE = 60
    private const val SAMPLE_RATE = 48_000
    private const val AUDIO_BIT_RATE = 192_000
    private const val CHUNK_FRAMES = SAMPLE_RATE / 50
    private const val MAX_MIC_LAG_CHUNKS = 5
    private const val DEQUEUE_US = 100_000L
    private const val JOIN_MS = 3_000L
    /** MediaMuxer writes 32-bit MP4 offsets and silently stops at 4 GB; start a new file well before. */
    private const val SEGMENT_BYTES = 3_500L * 1024 * 1024
    private const val RULE_MATCH_ATTRIBUTE_USAGE = 1
    /** 120 dB a second: the deepest duck takes 200 ms, the usual one less. */
    private val GAME_FADE_PER_FRAME = 10.0.pow(-120.0 / 20 / SAMPLE_RATE).toFloat()
    /** Push to talk hears the microphone this late, so a release is known before its click plays. */
    private const val MIC_DELAY_FRAMES = SAMPLE_RATE * 150 / 1000
    /** The press click (plus the key's way here) is over by then. */
    private const val TALK_OPEN_LATE_FRAMES = SAMPLE_RATE * 100 / 1000
    /** Measured on the Thor: the microphone hears the speaker's fade until about 300 ms after the press. */
    private const val TALK_OPEN_AFTER_ECHO_FRAMES = SAMPLE_RATE * 300 / 1000
    /** About -48 dBFS: a speaker quieter than this at the microphone stays under the game in the video. */
    private const val ECHO_LEVEL = 130f
    /** The closed microphone's level is held and falls by about 45 dB a second. */
    private const val AMBIENT_DECAY_PER_CHUNK = 0.9f
    /** The release click can start before the key event arrives. */
    private const val TALK_CLOSE_EARLY_FRAMES = SAMPLE_RATE * 80 / 1000
    private const val TALK_FADE_FRAMES = SAMPLE_RATE * 20 / 1000
    private const val SPEAKER_RESTORE_DELAY_MS = 250L
    private const val SPEAKER_FADE_MS = 60L
    /** The fade, the latency to the speaker and back, and the microphone's own delay, with room to spare. */
    private const val SPEAKER_SETTLE_MS = 700L
    /** Measured on the Thor: the microphone hears the speaker 23 dB quieter, 17 dB under a voice instead
     *  of 6 dB over it, and the game stays audible to the player. */
    private const val SPEAKER_GAIN = 0.1f
    private const val DUCK_SHAPER_ID = 0x4242
    private val DUCKED_USAGES = setOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN)
    private const val ROUTE_FLAG_RENDER = 1
    private const val ROUTE_FLAG_LOOP_BACK = 2
}
