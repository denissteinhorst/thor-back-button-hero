package dev.steinhorst.backbuttonhero

/** A background task move must leave the source foreground alone. Recents' temporary Home
 * placement must not hide the previous app when the actual foreground task leaves. */
object TaskMovePolicy {
    fun sourceFallback(selectedTask: Int, sourceForegroundRoot: Int?, appTasksInOrder: List<Int>): Int? =
        if (sourceForegroundRoot != selectedTask) sourceForegroundRoot?.takeIf { it in appTasksInOrder }
        else appTasksInOrder.firstOrNull { it != selectedTask }
}
