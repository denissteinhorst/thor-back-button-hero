package dev.steinhorst.backbuttonhero

/** A background task move must leave the source foreground alone. Recents' temporary Home
 * placement must not hide the previous app when the actual foreground task leaves. */
object TaskMovePolicy {
    const val PROTECTED_TASK = "ERR protected task"

    data class Move(val packageName: String, val targetDisplay: Int, val taskId: Int?)

    fun isMovable(activityType: Int, homeIntent: Boolean, secondaryHomeIntent: Boolean): Boolean =
        activityType == 1 && !homeIntent && !secondaryHomeIntent

    /** Reject either protected task before moving the other; a Home intent filter alone is not a task type. */
    fun planSwap(topPackage: String?, bottomPackage: String?, topDisplay: Int, bottomDisplay: Int,
        tasks: List<RecentsTaskState>?, canRelaunch: (String) -> Boolean): List<Move>? {
        val requests = listOfNotNull(
            topPackage?.let { Triple(it, topDisplay, bottomDisplay) },
            bottomPackage?.let { Triple(it, bottomDisplay, topDisplay) },
        )
        return requests.map { (pkg, source, target) ->
            if (!Shell.isPkg(pkg)) return null
            val task = tasks?.firstOrNull { it.packageName == pkg && it.displayId == source }
            if (task?.movable == false || (task == null && !canRelaunch(pkg))) return null
            Move(pkg, target, task?.taskId)
        }
    }

    fun mayRelaunch(result: String?, packageCanRelaunch: Boolean): Boolean =
        packageCanRelaunch && result != PROTECTED_TASK

    fun sourceFallback(selectedTask: Int, sourceForegroundRoot: Int?, appTasksInOrder: List<Int>): Int? =
        if (sourceForegroundRoot != selectedTask) sourceForegroundRoot?.takeIf { it in appTasksInOrder }
        else appTasksInOrder.firstOrNull { it != selectedTask }
}
