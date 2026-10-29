package dev.steinhorst.backbuttonhero

/** The system's task-to-front event must identify one of the tasks present when Recents opened. */
object RecentsSelection {
    /** AYN can retain an older card order after a display move. Never use column positions as
     * system task indices; only a unique semantic label bound to a known identity is safe. */
    fun fromCard(before: List<RecentsTaskState>, current: List<RecentsTaskState>, description: String): RecentsTaskState? {
        if (description.isBlank()) return null
        return current.filter { task ->
            task.taskId >= 0 && Shell.isPkg(task.packageName) && before.any { old ->
                old.taskId == task.taskId && old.packageName == task.packageName &&
                    (task.label == description || old.label == description)
            }
        }.singleOrNull()
    }

    fun movedTask(before: List<RecentsTaskState>, after: List<RecentsTaskState>, targetDisplay: Int): RecentsTaskState? {
        val task = after.singleOrNull() ?: return null
        val previous = before.firstOrNull { it.taskId == task.taskId && it.packageName == task.packageName } ?: return null
        return task.takeIf {
            it.taskId >= 0 && it.displayId != targetDisplay && previous.displayId == it.displayId &&
                Shell.isPkg(it.packageName)
        }
    }
}
