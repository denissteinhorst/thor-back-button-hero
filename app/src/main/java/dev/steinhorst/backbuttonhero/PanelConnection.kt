package dev.steinhorst.backbuttonhero

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One authenticated, ordered channel; losing it makes the root owner restore every hidden panel. */
interface PanelTransport {
    fun request(command: JSONObject, then: (List<PanelWindow>?) -> Unit)
    fun close()
}

class PanelConnection(private val context: Context, private val onLost: () -> Unit) : PanelTransport {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var closed = false
    @Volatile private var socket: LocalSocket? = null
    @Volatile private var server: LocalServerSocket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    init {
        worker.scheduleWithFixedDelay({
            if (socket != null) runCatching { exchange(JSONObject().put("op", "ping")) }.onFailure { disconnect() }
        }, 3, 3, TimeUnit.SECONDS)
    }

    override fun request(command: JSONObject, then: (List<PanelWindow>?) -> Unit) {
        if (closed) return
        worker.execute {
            val result = runCatching {
                if (socket == null) connect()
                val array = exchange(command).getJSONArray("panels")
                (0 until array.length()).map { PanelWindow.parse(array.getJSONObject(it)) }
            }.getOrElse {
                Log.w("BottomPanels", "panel request: ${it.javaClass.simpleName}")
                disconnect()
                null
            }
            main.post { if (!closed) then(result) }
        }
    }

    override fun close() {
        closed = true
        socket?.let(::closeSocket)
        server?.let(::closeServer)
        worker.shutdownNow()
    }

    private fun connect() {
        check(!closed && PService.isAvailable()) { "panel bridge unavailable" }
        val name = "${context.packageName}.panels.${UUID.randomUUID()}"
        val listener = LocalServerSocket(name)
        server = listener
        val timeout = Thread {
            try { Thread.sleep(8000); closeServer(listener) } catch (_: InterruptedException) { /* Connected. */ }
        }.apply { isDaemon = true; start() }
        val script = File.createTempFile("panel_start_", ".sh", context.cacheDir)
        try {
            script.writeText("CLASSPATH=${Shell.q(context.applicationInfo.sourceDir)} setsid app_process /system/bin " +
                "${context.packageName}.PanelWindowHost ${Shell.q(name)} >/dev/null 2>&1 &\nsleep 1\n")
            PService.exec("sh ${Shell.q(script.absolutePath)}")
            val connection = listener.accept()
            if (closed || connection.peerCredentials.uid != 0) {
                closeSocket(connection)
                error("unexpected panel peer")
            }
            socket = connection
            connection.soTimeout = 6000
            reader = connection.inputStream.bufferedReader()
            writer = connection.outputStream.bufferedWriter()
            check(reader?.readLine() == "READY") { "panel owner unavailable" }
        } finally {
            timeout.interrupt()
            closeServer(listener)
            server = null
            script.delete()
        }
    }

    private fun exchange(command: JSONObject): JSONObject {
        val output = requireNotNull(writer)
        output.write(command.toString()); output.newLine(); output.flush()
        val reply = JSONObject(requireNotNull(reader?.readLine()))
        check(reply.getBoolean("ok")) { "panel command failed" }
        return reply
    }

    private fun disconnect() {
        socket?.let(::closeSocket)
        socket = null
        reader = null
        writer = null
        main.post { if (!closed) onLost() }
    }

    private fun closeSocket(connection: LocalSocket) {
        runCatching { connection.shutdownOutput() }
        runCatching { connection.shutdownInput() }
        runCatching { connection.close() }
    }

    private fun closeServer(listener: LocalServerSocket) {
        runCatching { android.system.Os.shutdown(listener.fileDescriptor, android.system.OsConstants.SHUT_RDWR) }
        runCatching { listener.close() }
    }
}
