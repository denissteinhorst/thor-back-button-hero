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
 * helper holds the pad ([active]), Back + D-pad and the open switcher's D-pad arrive in [onPress]
 * and the game never sees them nor loses focus. Without it - no pservice, or the user pulled the
 * emergency release - [DpadOverlay] is the fallback.
 *
 * The helper is launched through [PService] and connects back over an abstract socket. Abstract
 * sockets have no file permissions, so every peer that is not root is turned away.
 */
class PadHelper(
    private val context: Context,
    private val onPress: (DpadDirection) -> Unit,
    private val onRelease: (horizontal: Boolean) -> Unit,
    private val onActiveChanged: (Boolean) -> Unit,
) {

    /** True while the helper has taken over a pad and gates its D-pad. */
    @Volatile var active = false
        private set

    @Volatile private var wanted = false
    // Home + Back held 5 s in the helper: the user wants the pad left alone until the service restarts.
    @Volatile private var released = false
    @Volatile private var client: LocalSocket? = null
    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var closed = false
    @Volatile private var navigationMode = PadNavigationMode.OFF
    @Volatile private var suspended = false
    private var listening = false
    private var failedLaunches = 0
    private var launchPending = false
    private val connectionLock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val socketName = "${context.packageName}.padhelper"

    fun start() {
        if (released || closed) return
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
        val socket = synchronized(connectionLock) {
            wanted = false
            client
        }
        setActive(false)
        socket?.let(::closeSocket)
    }

    /** A service instance must release its abstract socket before a replacement can bind it. */
    fun dispose() {
        closed = true
        stop()
        server?.let { listener ->
            // Wake accept as well: closing an fd on another thread need not interrupt that call.
            runCatching { android.system.Os.shutdown(listener.fileDescriptor, android.system.OsConstants.SHUT_RDWR) }
            runCatching { listener.close() }
        }
    }

    private fun closeSocket(socket: LocalSocket) {
        // close alone can leave a blocking read holding the fd, so root never sees EOF.
        runCatching { socket.shutdownOutput() }
        runCatching { socket.shutdownInput() }
        runCatching { socket.close() }
    }

    /** Selects how bare D-pad input is handled while one of the two switchers is open. */
    fun setSwitcherNavigation(mode: PadNavigationMode) {
        navigationMode = mode
        val command = "SWITCHER ${if (suspended) PadNavigationMode.PASSTHROUGH.wireValue else mode.wireValue}\n"
        runCatching { client?.outputStream?.write(command.toByteArray()) }
    }

    fun setSuspended(value: Boolean) {
        suspended = value
        setSwitcherNavigation(navigationMode)
    }

    /** Linux key codes, emitted by the existing clone without another root-shell round trip. */
    fun navigateTop(direction: DpadDirection) = sendNavigation(when (direction) {
        DpadDirection.UP -> 103
        DpadDirection.DOWN -> 108
        DpadDirection.LEFT -> 105
        DpadDirection.RIGHT -> 106
    })

    private fun sendNavigation(key: Int) {
        runCatching { client?.outputStream?.write("NAV $key\n".toByteArray()) }
    }

    private fun serve() {
        val listener = runCatching { LocalServerSocket(socketName) }.getOrElse {
            Log.w(TAG, "socket: ${it.javaClass.simpleName}")
            return
        }
        server = listener
        if (closed) { runCatching { listener.close() }; return }
        launchSoon(0)
        while (!closed) {
            val socket = runCatching { listener.accept() }.getOrNull() ?: break
            val root = runCatching { socket.peerCredentials.uid }.getOrDefault(-1) == 0
            val accepted = synchronized(connectionLock) {
                if (!root || !wanted || closed) false else { client = socket; true }
            }
            if (!accepted) {
                closeSocket(socket)
                continue
            }
            runCatching { socket.inputStream.bufferedReader().forEachLine(::handle) }
            client = null
            setActive(false)
            closeSocket(socket)
            if (wanted && !released) launchSoon(RELAUNCH_DELAY_MS)
        }
        runCatching { listener.close() }
    }

    private fun handle(line: String) {
        val parts = line.split(' ')
        when (parts[0]) {
            "PAD" -> if (wanted && !closed) {
                setActive(true)
                failedLaunches = 0
                setSwitcherNavigation(navigationMode)
            }
            "PAD_GONE" -> setActive(false)
            "RELEASED" -> { released = true; wanted = false; setActive(false) }
            "MODE" -> Unit
            "HAT" -> if (parts.size == 3) {
                val horizontal = parts[1] == "X"
                val direction = DpadDirection.fromHat(horizontal, parts[2].toFloatOrNull() ?: 0f)
                // A release (value 0) only matters to an action that commits on release.
                main.post {
                    if (wanted && !closed) {
                        if (direction != null) onPress(direction) else onRelease(horizontal)
                    }
                }
            }
        }
    }

    private fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        main.post { if (!closed) onActiveChanged(value) }
    }

    @Synchronized
    private fun launchSoon(delayMs: Long) {
        // A helper that keeps dying without ever taking a pad over is not worth restarting forever.
        if (closed || launchPending || failedLaunches >= MAX_FAILED_LAUNCHES) return
        launchPending = true
        failedLaunches++
        Thread({
            try {
                SystemClock.sleep(delayMs)
                if (!closed && wanted && client == null && PService.isAvailable()) launch()
            } finally {
                synchronized(this) { launchPending = false }
                // A helper can disconnect while pservice's startup script is still sleeping.
                // Its retry request was coalesced above; retain the bounded retry afterward.
                if (!closed && wanted && client == null && !released) launchSoon(RELAUNCH_DELAY_MS)
            }
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

/** TOP captures directions until Overview is ready; NAV replays them through the clone. */
enum class PadNavigationMode(val wireValue: Int) {
    OFF(0),
    CAPTURE(1),
    TOP(2),
    PASSTHROUGH(3),
}
