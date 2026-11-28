package dev.steinhorst.backbuttonhero.recents

import dev.steinhorst.backbuttonhero.core.Shell
import dev.steinhorst.backbuttonhero.prefs.AppException
import dev.steinhorst.backbuttonhero.prefs.ExceptionControl

/** A background task move must leave the source foreground alone. Recents' temporary Home
 * placement must not hide the previous app when the actual foreground task leaves. */
object TaskMovePolicy {
    const val PROTECTED_TASK = "ERR protected task"

    data class Move(val packageName: String, val targetDisplay: Int, val taskId: Int?)

    /** Hold exclusions protect either swap partner, independently of controller focus. */
    fun swapParticipant(packageName: String?, exceptions: List<AppException>): String? =
        packageName?.takeUnless { pkg ->
            exceptions.any { it.packageName == pkg && !it.allows(ExceptionControl.HOLD) }
        }

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

    /**
     * Moved tasks their app took back: the task is gone or off the screen it was moved to, and the app is in
     * front on another screen ([front], display to foreground package). An app closed right after the swap
     * is in front nowhere, so closing it never counts.
     */
    fun takenBack(moves: List<Move>, after: List<RecentsTaskState>, front: Map<Int, String?>): List<Move> =
        moves.filter { move ->
            move.taskId != null && after.none { it.taskId == move.taskId && it.displayId == move.targetDisplay } &&
                front.any { (display, pkg) -> display != move.targetDisplay && pkg == move.packageName }
        }

    fun mayRelaunch(result: String?, packageCanRelaunch: Boolean): Boolean =
        packageCanRelaunch && result != PROTECTED_TASK

    fun sourceFallback(selectedTask: Int, sourceForegroundRoot: Int?, appTasksInOrder: List<Int>): Int? =
        if (sourceForegroundRoot != selectedTask) sourceForegroundRoot?.takeIf { it in appTasksInOrder }
        else appTasksInOrder.firstOrNull { it != selectedTask }
}
