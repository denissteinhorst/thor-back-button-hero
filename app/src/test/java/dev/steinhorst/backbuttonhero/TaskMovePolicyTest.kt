package dev.steinhorst.backbuttonhero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskMovePolicyTest {
    @Test
    fun `hold exclusion leaves either partner in place while the other task moves over it`() {
        val top = RecentsTaskState("dev.example.game", 0, false, 55)
        val bottom = RecentsTaskState("dev.example.launcher", 17, false, 56)
        for (excluded in listOf(top, bottom)) {
            val exceptions = listOf(AppException(excluded.packageName, "Excluded", ExceptionControl.ALL xor ExceptionControl.HOLD.bit))
            val topPackage = TaskMovePolicy.swapParticipant(top.packageName, exceptions)
            val bottomPackage = TaskMovePolicy.swapParticipant(bottom.packageName, exceptions)
            // A protected or unrelaunchable excluded task must not cancel the remaining move.
            val tasks = listOf(top, bottom).map { if (it == excluded) it.copy(movable = false) else it }
            val other = if (excluded == top) bottom else top
            assertEquals(listOf(TaskMovePolicy.Move(other.packageName, excluded.displayId, other.taskId)),
                TaskMovePolicy.planSwap(topPackage, bottomPackage, 0, 17, tasks) { error("No relaunch expected") })
        }
    }

    @Test
    fun `fully blocked partners produce no swap and unrelated background exclusions do not block it`() {
        val exceptions = listOf(AppException("dev.example.fixed", "Fixed", 0))
        assertNull(TaskMovePolicy.swapParticipant("dev.example.fixed", exceptions))
        assertNull(TaskMovePolicy.swapParticipant(null, exceptions))
        assertEquals("dev.example.game", TaskMovePolicy.swapParticipant("dev.example.game", exceptions))
        assertEquals(emptyList<TaskMovePolicy.Move>(), TaskMovePolicy.planSwap(
            TaskMovePolicy.swapParticipant("dev.example.fixed", exceptions),
            TaskMovePolicy.swapParticipant("dev.example.fixed", exceptions), 0, 17, null,
        ) { error("Excluded packages must not be relaunched") })
    }

    @Test
    fun `inactive exceptions and exclusions of other gestures still allow a swap`() {
        val pkg = "dev.example.game"
        val rules = listOf(
            emptyList(),
            listOf(AppException(pkg, "Game")),
            listOf(AppException(pkg, "Game", 0, active = false)),
            listOf(AppException(pkg, "Game", ExceptionControl.HOLD.bit)),
        )
        rules.forEach { assertEquals(pkg, TaskMovePolicy.swapParticipant(pkg, it)) }
    }

    @Test
    fun `standard app tasks are movable while Home secondary Home and other task types stay protected`() {
        assertTrue(TaskMovePolicy.isMovable(1, false, false))
        assertFalse(TaskMovePolicy.isMovable(1, true, false))
        assertFalse(TaskMovePolicy.isMovable(1, false, true))
        for (type in listOf(0, 2, 3, 4, 5)) assertFalse(TaskMovePolicy.isMovable(type, false, false))
    }

    @Test
    fun `two ordinary tasks can swap even when both packages also advertise Home`() {
        val top = RecentsTaskState("dev.example.frontend", 0, false, 55)
        val bottom = RecentsTaskState("dev.example.companion", 17, false, 56)
        val moves = TaskMovePolicy.planSwap(top.packageName, bottom.packageName, 0, 17, listOf(top, bottom)) {
            error("A package Home filter must not override the actual task classification")
        }
        assertEquals(listOf(TaskMovePolicy.Move(top.packageName, 17, 55), TaskMovePolicy.Move(bottom.packageName, 0, 56)), moves)
        assertEquals(listOf(TaskMovePolicy.Move(bottom.packageName, 17, 56), TaskMovePolicy.Move(top.packageName, 0, 55)),
            TaskMovePolicy.planSwap(bottom.packageName, top.packageName, 0, 17,
                listOf(top.copy(displayId = 17), bottom.copy(displayId = 0))) { false })
    }

    @Test
    fun `either protected task prevents the whole swap before either move is attempted`() {
        val top = RecentsTaskState("dev.example.home", 0, false, 55)
        val bottom = RecentsTaskState("dev.example.game", 17, false, 56)
        assertNull(TaskMovePolicy.planSwap(top.packageName, bottom.packageName, 0, 17,
            listOf(top.copy(movable = false), bottom)) { true })
        assertNull(TaskMovePolicy.planSwap(top.packageName, bottom.packageName, 0, 17,
            listOf(top, bottom.copy(movable = false))) { true })
    }

    @Test
    fun `unknown launcher tasks fail closed while ordinary apps retain their existing unprivileged fallback`() {
        assertNull(TaskMovePolicy.planSwap("dev.example.home", "dev.example.game", 0, 17, null) { it != "dev.example.home" })
        assertEquals(listOf(TaskMovePolicy.Move("dev.example.game", 17, null)),
            TaskMovePolicy.planSwap("dev.example.game", null, 0, 17, null) { true })
        assertEquals(emptyList<TaskMovePolicy.Move>(), TaskMovePolicy.planSwap(null, null, 0, 17, emptyList()) { true })
        assertNull(TaskMovePolicy.planSwap("invalid;command", null, 0, 17, null) { true })
    }

    @Test
    fun `a protected root task never falls back to a package relaunch`() {
        assertFalse(TaskMovePolicy.mayRelaunch(TaskMovePolicy.PROTECTED_TASK, true))
        assertFalse(TaskMovePolicy.mayRelaunch("ERR unavailable", false))
        assertFalse(TaskMovePolicy.mayRelaunch(null, false))
        assertTrue(TaskMovePolicy.mayRelaunch("ERR unavailable", true))
        assertTrue(TaskMovePolicy.mayRelaunch(null, true))
    }

    @Test
    fun `moving foreground app restores previous app even when Recents put Home between them`() {
        assertEquals(20, TaskMovePolicy.sourceFallback(10, 10, listOf(10, 20, 30)))
    }

    @Test
    fun `moving a background app never replaces another foreground app or Home`() {
        assertEquals(10, TaskMovePolicy.sourceFallback(20, 10, listOf(10, 20, 30)))
        assertNull(TaskMovePolicy.sourceFallback(20, 1, listOf(20, 30)))
    }

    @Test
    fun `background move retains the actual foreground rather than a newer background task`() {
        assertEquals(30, TaskMovePolicy.sourceFallback(20, 30, listOf(10, 20, 30)))
        assertNull(TaskMovePolicy.sourceFallback(20, null, listOf(10, 20, 30)))
    }

    @Test
    fun `last foreground app has no app fallback and uses its display own home`() {
        assertNull(TaskMovePolicy.sourceFallback(10, 10, listOf(10)))
        assertNull(TaskMovePolicy.sourceFallback(10, null, emptyList()))
    }
}
