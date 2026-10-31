package dev.steinhorst.backbuttonhero

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.IBinder
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Root-only window lifetime owner. Its independent peer restores panels if this process dies. */
@SuppressLint("PrivateApi") // IWindow visibility is intentionally not exposed to ordinary apps.
object PanelWindowHost {
    private data class Held(val window: PanelWindow, val handle: String, val token: IBinder, var hidden: Boolean = false)

    @JvmStatic
    fun main(args: Array<String>) {
        if (android.os.Process.myUid() != 0) return
        if (Looper.myLooper() == null) Looper.prepareMainLooper()
        if (args.firstOrNull() == "recover") {
            recover(args[1])
            return
        }
        val held = linkedMapOf<Int, Held>()
        var recovery: LocalSocket? = null
        var client: LocalSocket? = null
        var listener: LocalServerSocket? = null
        var peer: Process? = null
        try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = activityThread.getMethod("systemMain").invoke(null)
            val context = activityThread.getMethod("getSystemContext").invoke(thread) as Context
            val app = context.packageManager.getApplicationInfo("dev.steinhorst.backbuttonhero", 0)
            val connection = connect(args.single())
            client = connection
            require(connection.peerCredentials.uid == app.uid)
            connection.soTimeout = 15000
            val recoveryName = "bbh.panel.recovery.${UUID.randomUUID()}"
            val server = LocalServerSocket(recoveryName)
            listener = server
            peer = ProcessBuilder("/system/bin/app_process", "/system/bin", "${app.packageName}.PanelWindowHost",
                "recover", recoveryName).redirectOutput(File("/dev/null")).redirectError(File("/dev/null")).start()
            // A failed peer launch must not leave accept blocked or hide any windows.
            val acceptTimeout = Thread {
                try {
                    Thread.sleep(5000)
                    android.system.Os.shutdown(server.fileDescriptor, android.system.OsConstants.SHUT_RDWR)
                } catch (_: Exception) { /* The accepted server is already closed. */ }
            }.apply { isDaemon = true; start() }
            val guard = server.accept()
            acceptTimeout.interrupt()
            server.close()
            listener = null
            recovery = guard
            require(guard.peerCredentials.uid == 0)
            guard.soTimeout = 3000
            val guardReader = guard.inputStream.bufferedReader()
            val guardWriter = guard.outputStream.bufferedWriter()
            val guardLock = Any()
            fun guardCommand(command: String) = synchronized(guardLock) {
                guardWriter.write(command); guardWriter.newLine(); guardWriter.flush()
                check(guardReader.readLine() == "OK") { "panel recovery unavailable" }
            }
            guardCommand("PING")
            val monitor = Thread {
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        Thread.sleep(1000)
                        guardCommand("PING")
                    }
                } catch (_: Exception) { close(connection) }
            }.apply { isDaemon = true; start() }
            val reader = connection.inputStream.bufferedReader()
            val writer = connection.outputStream.bufferedWriter()
            writer.write("READY\n"); writer.flush()
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    val reply = runCatching {
                        val request = JSONObject(line)
                        when (request.getString("op")) {
                            "list" -> {
                                val display = request.getInt("display")
                                require(display > 0)
                                val width = request.getInt("width")
                                val height = request.getInt("height")
                                require(width > 0 && height > 0)
                                val windows = PanelWindows.windows(dump("window", "windows"))
                                val ids = PanelWindows.accessibilityIds(dump("accessibility"))
                                held.values.toList().forEach { entry ->
                                    val current = windows.firstOrNull { it.handle == entry.handle }
                                    if (current == null || !current.isPanel(display, width, height)) {
                                        if (entry.hidden) runCatching { visibility(entry.token, true) }
                                        guardCommand("FORGET ${entry.window.id}")
                                        held.remove(entry.window.id)
                                    } else if (entry.hidden && current.visible) {
                                        // A frontend can re-show its existing window after resuming.
                                        // Report that fact so suppression captures and hides it again.
                                        entry.hidden = false
                                    } else if (!entry.hidden && !current.visible) {
                                        guardCommand("FORGET ${entry.window.id}")
                                        held.remove(entry.window.id)
                                    }
                                }
                                windows.filter { it.visible && it.isPanel(display, width, height) }.forEach { window ->
                                    val info = runCatching { context.packageManager.getApplicationInfo(window.packageName, 0) }.getOrNull()
                                    if (info == null || window.packageName == app.packageName) return@forEach
                                    if (!window.isAynPanel &&
                                        info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) return@forEach
                                    val id = ids[window.client] ?: return@forEach
                                    if (id !in held) {
                                        val token = windowToken(id) ?: return@forEach
                                        held[id] = Held(PanelWindow(id, window.packageName, display, false, window.isAynPanel),
                                            window.handle, token)
                                    }
                                }
                                val ordered = held.values.sortedBy { entry -> windows.indexOfFirst { it.handle == entry.handle } }
                                held.clear()
                                ordered.forEach { held[it.window.id] = it }
                            }
                            "hide" -> {
                                val entry = requireNotNull(held[request.getInt("id")])
                                if (!entry.hidden) {
                                    // Both processes retain the actual binder before visibility removes
                                    // the window from accessibility's retrievable window list.
                                    guardCommand("TRACK ${entry.window.id}")
                                    entry.hidden = true
                                    visibility(entry.token, false)
                                }
                            }
                            "show" -> {
                                val entry = requireNotNull(held[request.getInt("id")])
                                visibility(entry.token, true)
                                entry.hidden = false
                                guardCommand("FORGET ${entry.window.id}")
                            }
                            "restore" -> {
                                held.values.forEach { entry ->
                                    if (entry.hidden) visibility(entry.token, true)
                                    guardCommand("FORGET ${entry.window.id}")
                                }
                                held.clear()
                            }
                            "ping" -> Unit
                            else -> error("unknown panel command")
                        }
                        val panels = JSONArray()
                        held.values.forEach { panels.put(it.window.copy(hidden = it.hidden).json()) }
                        JSONObject().put("ok", true).put("panels", panels)
                    }.getOrElse {
                        android.util.Log.w("BottomPanels", "panel command failed", it)
                        JSONObject().put("ok", false).put("error", it.javaClass.simpleName)
                    }
                    writer.write(reply.toString()); writer.newLine(); writer.flush()
                }
            } finally { monitor.interrupt() }
        } catch (_: Exception) {
            // EOF, app death and an unavailable recovery peer all take the same restore path.
        } finally {
            held.values.filter { it.hidden }.forEach { runCatching { visibility(it.token, true) } }
            recovery?.let(::close)
            client?.let(::close)
            runCatching { listener?.close() }
            // Do not kill the peer: it owns the independent restoration path on socket EOF.
            runCatching { peer?.outputStream?.close() }
        }
        kotlin.system.exitProcess(0)
    }

    private fun recover(socketName: String) {
        val held = mutableMapOf<Int, IBinder>()
        var socket: LocalSocket? = null
        try {
            val connection = connect(socketName)
            socket = connection
            require(connection.peerCredentials.uid == 0)
            connection.soTimeout = 10000
            val reader = connection.inputStream.bufferedReader()
            val writer = connection.outputStream.bufferedWriter()
            while (true) {
                val line = reader.readLine() ?: break
                val parts = line.split(' ')
                when (parts[0]) {
                    "TRACK" -> {
                        val id = parts[1].toInt()
                        held[id] = requireNotNull(windowToken(id))
                    }
                    "FORGET" -> held.remove(parts[1].toInt())
                    "PING" -> Unit
                    else -> error("unknown recovery command")
                }
                writer.write("OK\n"); writer.flush()
            }
        } catch (_: Exception) {
            // The controlling helper can be SIGKILLed; recovery must not depend on its finally.
        } finally {
            held.values.forEach { runCatching { visibility(it, true) } }
            socket?.let(::close)
        }
        kotlin.system.exitProcess(0)
    }

    private fun connect(name: String) = LocalSocket().apply {
        connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
    }

    private fun windowToken(id: Int): IBinder? {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "accessibility") as IBinder
        val manager = Class.forName("android.view.accessibility.IAccessibilityManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
        val user = ActivityManager::class.java.getMethod("getCurrentUser").invoke(null) as Int
        return manager.javaClass.getMethod("getWindowToken", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(manager, id, user) as? IBinder
    }

    private fun visibility(token: IBinder, visible: Boolean) {
        val window = Class.forName("android.view.IWindow\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, token)!!
        window.javaClass.getMethod("dispatchAppVisibility", Boolean::class.javaPrimitiveType).invoke(window, visible)
    }

    private fun dump(service: String, vararg args: String): String {
        val process = ProcessBuilder(listOf("/system/bin/dumpsys", service) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "window discovery failed" }
        return output
    }

    private fun close(socket: LocalSocket) {
        runCatching { socket.shutdownOutput() }
        runCatching { socket.shutdownInput() }
        runCatching { socket.close() }
    }
}
