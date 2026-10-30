package dev.steinhorst.backbuttonhero

import org.json.JSONArray
import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/** Most-recent task first, matching the system Recents query. No status is inferred from history. */
data class RecentsTaskState(val packageName: String, val displayId: Int, val locked: Boolean, val taskId: Int = -1, val lastActiveTime: Long = 0,
    val label: String? = null, val movable: Boolean = true) {
    companion object {
        fun parse(output: String?): List<RecentsTaskState>? {
            if (output == null || !output.startsWith("OK ")) return null
            return runCatching {
                val array = JSONArray(output.removePrefix("OK "))
                (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    val pkg = item.getString("package")
                    require(Shell.isPkg(pkg))
                    RecentsTaskState(pkg, item.getInt("display"), item.getBoolean("locked"), item.optInt("task", -1), item.optLong("active", 0),
                        item.optString("label").takeIf { it.isNotEmpty() }, item.optBoolean("movable", true))
                }
            }.getOrNull()
        }

        /** Parse the shared store explicitly: malformed XML must never become an empty lock set. */
        fun lockKeys(xml: String): Set<String> {
            val parser = Xml.newPullParser().apply { setInput(xml.reader()) }
            require(parser.nextTag() == XmlPullParser.START_TAG && parser.name == "map")
            val keys = mutableSetOf<String>()
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.depth == 2) {
                    parser.getAttributeValue(null, "name")?.let(keys::add)
                }
            }
            return keys
        }

    }
}
