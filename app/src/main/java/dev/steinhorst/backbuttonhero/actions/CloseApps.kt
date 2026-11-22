package dev.steinhorst.backbuttonhero.actions

import dev.steinhorst.backbuttonhero.prefs.Prefs

object CloseApps {
    /** The displays a close-apps [target] ([Prefs.TARGET_BOTH], top or bottom) covers. */
    fun displays(target: String, topId: Int, bottomId: Int?): List<Int> = when (target) {
        Prefs.DISPLAY_TOP -> listOf(topId)
        Prefs.DISPLAY_BOTTOM -> listOfNotNull(bottomId)
        else -> listOfNotNull(topId, bottomId)
    }

    /**
     * The packages known to run on [displays]. With [keepForeground], every app currently in front
     * stays open, on any screen: an app moved to the other screen is still in its old screen's
     * history, and closing it there would end what the user is looking at.
     */
    fun packages(
        displays: List<Int>,
        allDisplays: List<Int>,
        keepForeground: Boolean,
        appsOn: (Int) -> List<String>,
        frontOn: (Int) -> String?,
    ): List<String> {
        val front = if (keepForeground) allDisplays.mapNotNull(frontOn).toSet() else emptySet()
        return displays.flatMap(appsOn).distinct().filterNot { it in front }
    }
}
