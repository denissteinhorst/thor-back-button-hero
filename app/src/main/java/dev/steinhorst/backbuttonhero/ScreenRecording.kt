package dev.steinhorst.backbuttonhero

import android.annotation.SuppressLint
import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import dev.steinhorst.backbuttonhero.core.PService
import dev.steinhorst.backbuttonhero.core.Shell
import dev.steinhorst.backbuttonhero.prefs.Prefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App side of the video recording: starts [ScreenRecorder] as root through pservice, hands it the
 * settings and later stops it. One recording at a time; a press while one starts or stops is ignored.
 */
@SuppressLint("PrivateApi") // ServiceManager is the only way to the recorder's binder
class ScreenRecording(private val context: Context, private val onChanged: (Event) -> Unit) {
    enum class State { IDLE, STARTING, RECORDING, STOPPING }
    enum class Event { STARTED, SAVED, STOPPED, FAILED, NEEDS_ROOT }

    class Request(
        val target: String,
        val topId: Int,
        val bottomId: Int?,
        val bitRate: Int,
        val microphone: Boolean,
        val pushToTalk: Boolean = false,
        val duck: String = Prefs.DUCK_OFF,
    )

    private val main = Handler(Looper.getMainLooper())
    /** Kept alive for the recorder: when this process dies, the recorder finishes the file itself. */
    private val clientToken = Binder()
    private var recorder: IBinder? = null
    private var session = 0
    var state = State.IDLE
        private set

    val isRecording: Boolean get() = state == State.RECORDING

    /** Starts a recording, or stops the running one. */
    fun toggle(request: () -> Request?) {
        when (state) {
            State.IDLE -> request()?.let(::start)
            State.RECORDING -> stop()
            State.STARTING, State.STOPPING -> Unit
        }
    }

    private fun start(request: Request) {
        if (request.target != ScreenRecorder.MODE_TOP && request.bottomId == null) {
            onChanged(Event.FAILED)
            return
        }
        state = State.STARTING
        val current = ++session
        Thread({
            val result = if (!PService.isAvailable()) null else launch(request)
            main.post {
                if (current != session) {
                    result?.let { binder -> Thread { transact(binder, ScreenRecorder.TRANSACTION_STOP) }.start() }
                    return@post
                }
                when {
                    result == null && !PService.cachedAvailable() -> { state = State.IDLE; onChanged(Event.NEEDS_ROOT) }
                    result == null -> { state = State.IDLE; onChanged(Event.FAILED) }
                    else -> {
                        recorder = result
                        state = State.RECORDING
                        runCatching {
                            result.linkToDeath({ main.post { if (current == session) ended(Event.STOPPED) } }, 0)
                        }.onFailure { ended(Event.STOPPED); return@post }
                        onChanged(Event.STARTED)
                    }
                }
            }
        }, "RecordingStart").apply { isDaemon = true }.start()
    }

    fun stop() {
        val binder = recorder ?: return
        if (state != State.RECORDING) return
        state = State.STOPPING
        val current = session
        Thread({
            val reply = transact(binder, ScreenRecorder.TRANSACTION_STOP)
            main.post { if (current == session) ended(if (reply?.startsWith("OK") == true) Event.SAVED else Event.FAILED) }
        }, "RecordingStop").apply { isDaemon = true }.start()
    }

    /** Opens or closes the microphone of a push-to-talk recording. One-way, so it never waits. */
    fun talk(on: Boolean) {
        val binder = recorder?.takeIf { state == State.RECORDING } ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(ScreenRecorder.DESCRIPTOR)
            data.writeInt(if (on) 1 else 0)
            binder.transact(ScreenRecorder.TRANSACTION_TALK, data, null, IBinder.FLAG_ONEWAY)
        } catch (e: Exception) {
            Log.w(TAG, "talk: ${e.javaClass.simpleName}")
        } finally {
            data.recycle()
        }
    }

    /** Ends a recording without waiting for the result, e.g. when the service goes away. */
    fun release() {
        val binder = recorder
        session++
        recorder = null
        state = State.IDLE
        if (binder != null) Thread { transact(binder, ScreenRecorder.TRANSACTION_STOP) }.apply { isDaemon = true }.start()
    }

    private fun ended(event: Event) {
        session++
        recorder = null
        state = State.IDLE
        onChanged(event)
    }

    /** Launches the root process, waits for its service and starts the recording; null on any failure. */
    private fun launch(request: Request): IBinder? {
        // A recorder left from an earlier service instance must not answer for this one.
        service()?.let { stale -> transact(stale, ScreenRecorder.TRANSACTION_STOP) }
        val script = File(context.cacheDir, "recorder_start.sh")
        runCatching {
            // pservice reaps its shell at once; backgrounding plus a short sleep lets the VM detach.
            script.writeText(
                "CLASSPATH=${Shell.q(context.applicationInfo.sourceDir)} app_process /system/bin " +
                    "${context.packageName}.ScreenRecorder >/dev/null 2>&1 &\nsleep 1\n",
            )
            PService.exec("sh ${Shell.q(script.absolutePath)}")
        }.onFailure { Log.w(TAG, "launch: ${it.javaClass.simpleName}") }
        script.delete()
        val deadline = SystemClock.uptimeMillis() + SERVICE_WAIT_MS
        var binder = service()
        while (binder == null && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100)
            binder = service()
        }
        binder ?: return null
        val args = arrayOf(
            outputFile(request.target).absolutePath, request.target, request.topId.toString(),
            (request.bottomId ?: -1).toString(), request.bitRate.toString(), if (request.microphone) "1" else "0",
            if (request.pushToTalk) "1" else "0", request.duck,
        )
        val reply = transact(binder, ScreenRecorder.TRANSACTION_START) { data ->
            data.writeStrongBinder(clientToken)
            data.writeStringArray(args)
        }
        if (reply != "OK") Log.w(TAG, "start: $reply")
        return binder.takeIf { reply == "OK" }
    }

    private fun service(): IBinder? = runCatching {
        Class.forName("android.os.ServiceManager").getMethod("checkService", String::class.java)
            .invoke(null, ScreenRecorder.SERVICE) as? IBinder
    }.getOrNull()?.takeIf { it.isBinderAlive }

    private fun transact(binder: IBinder, code: Int, write: (Parcel) -> Unit = {}): String? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ScreenRecorder.DESCRIPTOR)
            write(data)
            if (binder.transact(code, data, reply, 0)) reply.readString() else null
        } catch (e: Exception) {
            Log.w(TAG, "transact: ${e.javaClass.simpleName}")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    companion object {
        private const val TAG = "ScreenRecording"
        private const val SERVICE_WAIT_MS = 5_000L
        /** Shared storage as root sees it; the file app shows it as Videos/BBH-Records. */
        const val DIRECTORY = "/storage/emulated/0/Videos/BBH-Records"

        fun bitRate(quality: String): Int = when (quality) {
            Prefs.RECORD_SMALL -> 4_000_000
            Prefs.RECORD_LARGE -> 20_000_000
            else -> 10_000_000
        }

        fun fileName(target: String, now: Date = Date()): String =
            "Recording_${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)}_$target.mp4"

        private fun outputFile(target: String) = File(DIRECTORY, fileName(target))
    }
}
