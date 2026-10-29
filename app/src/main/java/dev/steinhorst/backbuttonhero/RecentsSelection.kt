package dev.steinhorst.backbuttonhero

/** The system's task-to-front event must identify one of the tasks present when Recents opened. */
object RecentsSelection {
    fun movedTask(before: List<RecentsTaskState>, after: List<RecentsTaskState>, targetDisplay: Int): RecentsTaskState? {
        val task = after.singleOrNull() ?: return null
        val previous = before.firstOrNull { it.taskId == task.taskId && it.packageName == task.packageName } ?: return null
        return task.takeIf {
            it.taskId >= 0 && it.displayId != targetDisplay && previous.displayId == it.displayId &&
                Shell.isPkg(it.packageName)
        }
    }
}
