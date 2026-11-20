package dev.steinhorst.backbuttonhero

/**
 * The "switch to last app" hotkey: the app used before the one in front, on the screen the
 * controller acts on. Bringing it forward makes it the newest entry, so pressing again returns to
 * the other one: the hotkey flips between the last two apps.
 *
 * Each screen keeps its own pair. An app that moved to the other screen (swap, a launch there) still
 * sits in this screen's history, but it belongs to the other screen now and is never pulled back.
 */
object LastApp {
    /** [history] is the screen's apps, most recent first; [current] the app in front, if any. */
    fun previous(history: List<String>, current: String?, ownPackage: String): String? =
        candidates(history, current, ownPackage).firstOrNull()

    /** Every app the hotkey may try on this screen, best first; the root launcher skips moved ones. */
    fun candidates(history: List<String>, current: String?, ownPackage: String): List<String> =
        history.filter { it != current && it != ownPackage }

    /**
     * The task to bring forward on [display] from the app's [tasks] (task id to display id, recents
     * order): one already there, or one no screen holds. Null when all of them live on another screen.
     */
    fun taskOn(tasks: List<Pair<Int, Int>>, display: Int): Int? =
        tasks.firstOrNull { it.second == display || it.second < 0 }?.first

    /** What [RecentsTaskLauncher] answers when the app's task no longer exists. */
    const val TASK_GONE = "ERR task unavailable"

    /** Asks [RecentsTaskLauncher] for a task on the requested screen only, never one moved from the other. */
    const val SAME_DISPLAY = "same-display"

    /** What [RecentsTaskLauncher] answers in [SAME_DISPLAY] mode when the app lives on the other screen. */
    const val OTHER_DISPLAY = "ERR other display"
}
