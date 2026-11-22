package dev.steinhorst.backbuttonhero.support

import android.os.Binder
import android.os.Parcel
import dev.steinhorst.backbuttonhero.core.PService
import java.io.File

/**
 * Stands in for AYN's root daemon inside the test process. Every command PService sends is recorded
 * exactly; for `sh <script>` the script's content is recorded too, read during the transaction, since
 * PService deletes the file right afterwards. [answer] answers each command (default "OK").
 */
class FakePService(private val answer: (String) -> String = { "OK" }) : Binder() {
    private val recorded = mutableListOf<String>()
    val commands: List<String> get() = synchronized(recorded) { recorded.toList() }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        val args = data.createStringArray().orEmpty()
        val command = args.firstOrNull().orEmpty()
        val entry = StringBuilder("code=$code args=${args.drop(1)} $command")
        SCRIPT.find(command)?.let { m -> File(m.groupValues[1].trim('\'')).takeIf { it.exists() }?.let { entry.append("\n  script: ").append(it.readText().trimEnd().replace("\n", "\n  script: ")) } }
        synchronized(recorded) { recorded += entry.toString() }
        reply?.writeByteArray(answer(command).toByteArray())
        return true
    }

    /** Installs this fake as the reachable, available daemon; [uninstall] puts the unknown state back. */
    fun install(): FakePService = apply {
        field("cached").set(PService, this)
        field("checked").setBoolean(PService, true)
    }

    fun uninstall() {
        field("cached").set(PService, null)
        field("checked").setBoolean(PService, false)
    }

    private fun field(name: String) = PService::class.java.getDeclaredField(name).apply { isAccessible = true }

    private companion object {
        val SCRIPT = Regex("^sh ('[^']+'|\\S+)$")
    }
}
