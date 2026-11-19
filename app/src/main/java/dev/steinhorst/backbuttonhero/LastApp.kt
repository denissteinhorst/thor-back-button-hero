package dev.steinhorst.backbuttonhero

/**
 * The "switch to last app" hotkey: the app used before the one in front, on the screen the
 * controller acts on. Bringing it forward makes it the newest entry, so pressing again returns to
 * the other one: the hotkey flips between the last two apps.
 */
object LastApp {
    /** [history] is the screen's apps, most recent first; [current] the app in front, if any. */
    fun previous(history: List<String>, current: String?, ownPackage: String): String? =
        history.firstOrNull { it != current && it != ownPackage }

    /** What [RecentsTaskLauncher] answers when the app's task no longer exists. */
    const val TASK_GONE = "ERR task unavailable"
}
