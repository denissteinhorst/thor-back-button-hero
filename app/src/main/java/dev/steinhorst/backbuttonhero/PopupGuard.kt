package dev.steinhorst.backbuttonhero

import android.net.LocalSocket
import android.net.LocalSocketAddress
import dev.steinhorst.backbuttonhero.actions.HidePopups
import dev.steinhorst.backbuttonhero.actions.PopupGuardLink
import dev.steinhorst.backbuttonhero.actions.PopupRestore
import java.io.File

/**
 * Root-only restore guard of "Hide pop-ups", started by [PopupGuardLink] in a bare app_process VM. The
 * heads-up setting and the toast app-op it guards persist across reboots, so this process holds a socket to
 * the app and restores them when that socket ends, unless the app said it restored them itself. EOF, app
 * death, an uninstall and a failed connection all take the same restore path, like the panel host's.
 *
 * Usage: `PopupGuard <socket> <app uid> <heads-up value> <toast mode>`.
 */
object PopupGuard {
    @JvmStatic
    fun main(args: Array<String>) {
        if (android.os.Process.myUid() != 0 || args.size != 4) return
        val uid = args[1].toIntOrNull() ?: return
        val restore = PopupRestore.of(args[2], args[3]) ?: return
        var socket: LocalSocket? = null
        var restored = false
        try {
            val connection = LocalSocket().apply { connect(LocalSocketAddress(args[0], LocalSocketAddress.Namespace.ABSTRACT)) }
            socket = connection
            require(connection.peerCredentials.uid == uid)
            connection.outputStream.apply { write("${PopupGuardLink.READY}\n".toByteArray()); flush() }
            restored = connection.inputStream.bufferedReader().readLine() == PopupGuardLink.RESTORED
        } catch (_: Exception) {
            // Whatever ended the link, the values are put back below.
        } finally {
            if (!restored) {
                runCatching {
                    ProcessBuilder("/system/bin/sh", "-c", HidePopups.restoreCommand(restore))
                        .redirectOutput(File("/dev/null")).redirectError(File("/dev/null")).start().waitFor()
                }
            }
            socket?.let { runCatching { it.close() } }
        }
        Runtime.getRuntime().halt(0)
    }
}
