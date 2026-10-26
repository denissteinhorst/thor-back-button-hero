package dev.steinhorst.backbuttonhero

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Starts and talks to the root input helper (src/main/cpp/padhelper.c, docs/HELPER.md). While the
 * helper holds the pad ([active]), Back + D-pad arrives in [onPress] and the game never sees it nor
 * loses focus. Without it - no pservice, or the user pulled the emergency release - [DpadOverlay] is
 * the fallback.
 *
 * The helper is launched through [PService] and connects back over an abstract socket. Abstract
 * sockets have no file permissions, so every peer that is not root is turned away.
 */
class PadHelper(
    private val context: Context,
    private val onPress: (DpadDirection) -> Unit,
    private val onRelease: (horizontal: Boolean) -> Unit,
) {

    /** True while the helper has taken over a pad and gates its D-pad. */
    @Volatile var active = false
        private set

    @Volatile private var wanted = false
    // Home + Back held 5 s in the helper: the user wants the pad left alone until the service restarts.
    @Volatile private var released = false
    @Volatile private var client: LocalSocket? = null
    private var listening = false
    private var failedLaunches = 0
    private val main = Handler(Looper.getMainLooper())
    private val socketName = "${context.packageName}.padhelper"

    fun start() {
        if (released) return
        wanted = true
        if (!listening) {
            listening = true
            Thread(::serve, "PadHelper").apply { isDaemon = true }.start()
        } else if (client == null) {
            launchSoon(0)
        }
    }

    /** Closing our end makes the helper hand the pad back to Android and exit. */
    fun stop() {
        wanted = false
        active = false
        runCatching { client?.close() }
    }

    private fun serve() {
        val server = runCatching { LocalServerSocket(socketName) }.getOrElse {
            Log.w(TAG, "socket: ${it.javaClass.simpleName}")
            return
        }
        launchSoon(0)
        while (true) {
            val socket = runCatching { server.accept() }.getOrNull() ?: continue
            if (runCatching { socket.peerCredentials.uid }.getOrDefault(-1) != 0 || !wanted) {
                runCatching { socket.close() }
                continue
            }
            client = socket
            runCatching { socket.inputStream.bufferedReader().forEachLine(::handle) }
            client = null
            active = false
            runCatching { socket.close() }
            if (wanted && !released) launchSoon(RELAUNCH_DELAY_MS)
        }
    }

    private fun handle(line: String) {
        val parts = line.split(' ')
        when (parts[0]) {
            "PAD" -> { active = true; failedLaunches = 0 }
            "PAD_GONE" -> active = false
            "RELEASED" -> { released = true; wanted = false; active = false }
            "HAT" -> if (parts.size == 3) {
                val horizontal = parts[1] == "X"
                val direction = DpadDirection.fromHat(horizontal, parts[2].toFloatOrNull() ?: 0f)
                // A release (value 0) only matters to an action that commits on release.
                if (direction != null) main.post { onPress(direction) } else main.post { onRelease(horizontal) }
            }
        }
    }

    private fun launchSoon(delayMs: Long) {
        // A helper that keeps dying without ever taking a pad over is not worth restarting forever.
        if (failedLaunches >= MAX_FAILED_LAUNCHES) return
        failedLaunches++
        Thread({
            SystemClock.sleep(delayMs)
            if (wanted && client == null && PService.isAvailable()) launch()
        }, "PadHelperLaunch").apply { isDaemon = true }.start()
    }

    private fun launch() {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libpadhelper.so").absolutePath
        // pservice reaps its shell at once, so '&' alone kills the child before it detaches; a script
        // that backgrounds it and then sleeps briefly lets it survive (see docs/HELPER.md).
        val script = File(context.cacheDir, "padhelper_start.sh")
        runCatching {
            script.writeText("${Shell.q(binary)} ${Shell.q(socketName)} >/dev/null 2>&1 &\nsleep 1\n")
            PService.exec("sh ${Shell.q(script.absolutePath)}")
        }.onFailure { Log.w(TAG, "launch: ${it.javaClass.simpleName}") }
        script.delete()
    }

    companion object {
        private const val TAG = "PadHelper"
        private const val RELAUNCH_DELAY_MS = 3000L
        private const val MAX_FAILED_LAUNCHES = 5
    }
}
