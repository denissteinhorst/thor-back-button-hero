package dev.steinhorst.backbuttonhero

/** The small, stable subset read from AYN Launcher3's dump while system Overview is active. */
data class TopRecentsState(
    val overview: Boolean,
    val cardBounds: CardBounds?,
    val cardFocused: Boolean = false,
) {
    data class CardBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val height get() = bottom - top
    }

    companion object {
        private val taskBounds = Regex("TaskView\\{.*? (-?\\d+),(\\d+)-(-?\\d+),(\\d+)")

        fun parse(dump: String?): TopRecentsState {
            if (dump == null) return TopRecentsState(overview = false, cardBounds = null)
            val match = dump.lineSequence().mapNotNull(taskBounds::find).firstOrNull()
            val bounds = match?.groupValues?.let { groups ->
                val left = groups[1].toIntOrNull() ?: return@let null
                val top = groups[2].toIntOrNull() ?: return@let null
                val right = groups[3].toIntOrNull() ?: return@let null
                val bottom = groups[4].toIntOrNull() ?: return@let null
                CardBounds(left, top, right, bottom).takeIf { right > left && bottom > top }
            }
            val focused = dump.lineSequence().any {
                it.contains("TaskView{") && it.contains(" .F")
            }
            return TopRecentsState(dump.contains("mState:Overview"), bounds, focused)
        }
    }
}
