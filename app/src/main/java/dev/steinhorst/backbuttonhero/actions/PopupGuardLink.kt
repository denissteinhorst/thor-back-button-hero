package dev.steinhorst.backbuttonhero.actions

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import dev.steinhorst.backbuttonhero.PopupGuard
import dev.steinhorst.backbuttonhero.core.PService
import dev.steinhorst.backbuttonhero.core.Shell
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * The link to the root [PopupGuard], started through pservice the way the panel host is: the guard connects
 * to a random abstract socket of this process and proves uid 0, this process proves its own uid to the guard.
 * Nothing flows but the guard's READY and, after a successful restore, our RESTORED. The socket's end without
 * RESTORED (this process crashing, killed or uninstalled, or a failed restore) makes the guard restore.
 */
internal class PopupGuardLink private constructor(private val socket: LocalSocket) : HidePopups.Guard {
    @Volatile private var closed = false

    override fun close(restored: Boolean) {
        if (closed) return
        closed = true
        if (restored) runCatching { socket.outputStream.apply { write("$RESTORED\n".toByteArray()); flush() } }
        closeSocket(socket)
    }

    companion object {
        private const val TAG = "HidePopups"
        const val READY = "READY"
        const val RESTORED = "RESTORED"
        private const val ACCEPT_TIMEOUT_MS = 8_000L

        /** Blocking: starts the guard and waits for it; null when it did not report in. */
        fun start(context: Context, restore: PopupRestore, onLost: (HidePopups.Guard) -> Unit): HidePopups.Guard? {
            val name = "${context.packageName}.popups.${UUID.randomUUID()}"
            val listener = runCatching { LocalServerSocket(name) }.getOrElse {
                Log.w(TAG, "guard socket: ${it.javaClass.simpleName}")
                return null
            }
            // A guard that never connects must not leave accept blocked.
            val timeout = Thread {
                try { Thread.sleep(ACCEPT_TIMEOUT_MS); closeServer(listener) } catch (_: InterruptedException) { /* Connected. */ }
            }.apply { isDaemon = true; start() }
            var script: File? = null
            var connection: LocalSocket? = null
            try {
                script = File.createTempFile("popups_start_", ".sh", context.cacheDir).apply {
                    writeText(launchScript(context.applicationInfo.sourceDir, context.packageName, name, android.os.Process.myUid(), restore))
                }
                checkNotNull(PService.exec("sh ${Shell.q(script.absolutePath)}")) { "pservice unavailable" }
                val accepted = listener.accept()
                connection = accepted
                check(accepted.peerCredentials.uid == 0) { "unexpected guard peer" }
                accepted.soTimeout = 6_000
                check(readLine(accepted.inputStream) == READY) { "guard unavailable" }
                accepted.soTimeout = 0
                val link = PopupGuardLink(accepted)
                Thread({
                    // The guard never sends more; the read returns only when its end goes away.
                    runCatching { while (accepted.inputStream.read() >= 0) Unit }
                    if (!link.closed) {
                        link.closed = true
                        closeSocket(accepted)
                        onLost(link)
                    }
                }, "PopupGuardLink").apply { isDaemon = true }.start()
                return link
            } catch (e: Exception) {
                Log.w(TAG, "guard: ${e.javaClass.simpleName}")
                connection?.let(::closeSocket)
                return null
            } finally {
                timeout.interrupt()
                closeServer(listener)
                script?.delete()
            }
        }

        /** The pservice script: the guard detaches from it with setsid, so it outlives the script and this app. */
        fun launchScript(apk: String, packageName: String, socket: String, uid: Int, restore: PopupRestore): String =
            "CLASSPATH=${Shell.q(apk)} setsid app_process /system/bin $packageName.PopupGuard ${Shell.q(socket)} $uid " +
                "${Shell.q(restore.headsUp)} ${Shell.q(restore.toastMode)} >/dev/null 2>&1 &\nsleep 1\n"

        private fun readLine(input: InputStream): String? {
            val line = StringBuilder()
            while (line.length < 32) {
                val byte = input.read()
                if (byte < 0) return null
                if (byte == '\n'.code) return line.toString()
                line.append(byte.toChar())
            }
            return null
        }

        private fun closeSocket(socket: LocalSocket) {
            runCatching { socket.shutdownOutput() }
            runCatching { socket.shutdownInput() }
            runCatching { socket.close() }
        }

        private fun closeServer(listener: LocalServerSocket) {
            runCatching { android.system.Os.shutdown(listener.fileDescriptor, android.system.OsConstants.SHUT_RDWR) }
            runCatching { listener.close() }
        }
    }
}
