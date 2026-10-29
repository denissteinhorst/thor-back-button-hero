package dev.steinhorst.backbuttonhero

/** The small, stable subset read from AYN Launcher3's dump while system Overview is active. */
data class TopRecentsState(
    val overview: Boolean,
    val cardBounds: CardBounds?,
    val cardFocused: Boolean = false,
    val actionsFocused: Boolean = false,
    val windowFocused: Boolean = false,
) {
    data class CardBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val height get() = bottom - top
    }

    companion object {
        private val taskBounds = Regex("TaskView\\{.*? (-?\\d+),(\\d+)-(-?\\d+),(\\d+)")

        fun parse(dump: String?): TopRecentsState {
            if (dump == null) return TopRecentsState(overview = false, cardBounds = null)
            // AYN dumps both launcher activities. The secondary drawer can retain its own focus;
            // neither that focus nor a stopped Quickstep's cached Overview state is live Recents.
            val scoped = if (dump.contains("ACTIVITY ")) {
                var top = false
                dump.lineSequence().filter { line ->
                    if (line.trimStart().startsWith("ACTIVITY ")) top = line.contains(".uioverrides.QuickstepLauncher")
                    top
                }.joinToString("\n")
            } else dump
            val records = scoped.split('\n', '|')
            val match = records.asSequence().mapNotNull(taskBounds::find).firstOrNull()
            val bounds = match?.groupValues?.let { groups ->
                val left = groups[1].toIntOrNull() ?: return@let null
                val top = groups[2].toIntOrNull() ?: return@let null
                val right = groups[3].toIntOrNull() ?: return@let null
                val bottom = groups[4].toIntOrNull() ?: return@let null
                CardBounds(left, top, right, bottom).takeIf { right > left && bottom > top }
            }
            val focused = records.any {
                it.contains("TaskView{") && it.contains(" .F")
            }
            val actions = records.any {
                it.contains(" .F") && (it.contains("app:id/action_screenshot") || it.contains("app:id/clear_all_button"))
            }
            val windowFocused = scoped.contains("mResumed=true") && scoped.contains("mHasWindowFocus=true")
            return TopRecentsState(scoped.contains("mState:Overview") && !scoped.contains("mStopped=true"), bounds, focused, actions, windowFocused)
        }
    }
}
