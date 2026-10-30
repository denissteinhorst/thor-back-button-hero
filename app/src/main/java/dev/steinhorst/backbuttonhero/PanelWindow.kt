package dev.steinhorst.backbuttonhero

import org.json.JSONObject

/** A panel is a window, not the owner's activity task. Never use its package as its card identity. */
data class PanelWindow(val id: Int, val packageName: String, val displayId: Int, val hidden: Boolean) {
    fun json(): JSONObject = JSONObject().put("id", id).put("package", packageName)
        .put("display", displayId).put("hidden", hidden)

    companion object {
        fun parse(item: JSONObject): PanelWindow {
            val id = item.getInt("id")
            val pkg = item.getString("package")
            val display = item.getInt("display")
            require(id >= 0 && Shell.isPkg(pkg) && display > 0)
            return PanelWindow(id, pkg, display, item.getBoolean("hidden"))
        }
    }
}

/** Firmware dumps identify the owner/type even when a Presentation has no accessibility root. */
object PanelWindows {
    data class Window(val handle: String, val client: String, val packageName: String, val uid: Int,
        val displayId: Int, val type: String, val visible: Boolean,
        val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun isPanel(display: Int, width: Int, height: Int): Boolean {
            if (display <= 0 || displayId != display || uid % 100000 < 10000 || !Shell.isPkg(packageName)) return false
            if (type !in setOf("PRESENTATION", "PRIVATE_PRESENTATION", "APPLICATION_OVERLAY")) return false
            val area = (minOf(right, width) - maxOf(left, 0)).coerceAtLeast(0).toLong() *
                (minOf(bottom, height) - maxOf(top, 0)).coerceAtLeast(0)
            return width > 0 && height > 0 && area >= width.toLong() * height * 9 / 10
        }
    }

    fun windows(dump: String): List<Window> {
        require(dump.startsWith("WINDOW MANAGER WINDOWS")) { "window snapshot unavailable" }
        val headers = Regex("(?m)^  Window #\\d+ Window\\{([0-9a-f]+) u\\d+ .*\\}:").findAll(dump).toList()
        return headers.mapIndexedNotNull { index, header ->
            val block = dump.substring(header.range.last + 1, headers.getOrNull(index + 1)?.range?.first ?: dump.length)
            fun match(pattern: String) = Regex(pattern).find(block)?.groupValues
            val display = match("mDisplayId=(\\d+)")?.get(1)?.toIntOrNull() ?: return@mapIndexedNotNull null
            val client = match("mClient=android.os.BinderProxy@([0-9a-f]+)")?.get(1) ?: return@mapIndexedNotNull null
            val owner = match("mOwnerUid=(\\d+).*?package=([A-Za-z0-9_.]+)") ?: return@mapIndexedNotNull null
            val type = match("\\bty=([A-Z_]+)")?.get(1) ?: return@mapIndexedNotNull null
            val frame = match("\\bframe=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]") ?: return@mapIndexedNotNull null
            Window(header.groupValues[1], client, owner[2], owner[1].toInt(), display, type,
                match("(?m)^    isVisible=(true|false)")?.get(1) == "true",
                frame[1].toInt(), frame[2].toInt(), frame[3].toInt(), frame[4].toInt())
        }
    }

    fun accessibilityIds(dump: String): Map<String, Int> {
        require(dump.startsWith("ACCESSIBILITY MANAGER")) { "accessibility snapshot unavailable" }
        var id: Int? = null
        val result = mutableMapOf<String, Int>()
        dump.lineSequence().forEach { line ->
            if (line.startsWith("A11yWindow[")) id = Regex("\\bid=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
            if (line.startsWith("WindowInfo[")) {
                val client = Regex("token=android.os.BinderProxy@([0-9a-f]+)").find(line)?.groupValues?.get(1)
                if (client != null && id != null) result[client] = requireNotNull(id)
                id = null
            }
        }
        return result
    }
}
