package dev.steinhorst.backbuttonhero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentsSelectionTest {
    private val top = RecentsTaskState("dev.example.top", 0, false, 10, 100)
    private val bottom = RecentsTaskState("dev.example.bottom", 17, false, 20, 90)
    private val background = RecentsTaskState("dev.example.background", 17, false, 30, 80)
    private val before = listOf(top, bottom, background)

    @Test
    fun `foreground bottom selection uses exact task-to-front event even when resume times tie`() {
        val selected = bottom.copy(lastActiveTime = top.lastActiveTime)
        assertEquals(selected, RecentsSelection.movedTask(before, listOf(selected), 0))
    }

    @Test
    fun `background selection retains exact task identity`() {
        val selected = background.copy(lastActiveTime = 200)
        assertEquals(selected, RecentsSelection.movedTask(before, listOf(selected), 0))
    }

    @Test
    fun `same display and already moved selections need no move`() {
        assertNull(RecentsSelection.movedTask(before, listOf(top.copy(lastActiveTime = 200), bottom), 0))
        assertNull(RecentsSelection.movedTask(before, listOf(background.copy(displayId = 0, lastActiveTime = 200)), 0))
    }

    @Test
    fun `cancellation and ambiguous snapshots never become a selection`() {
        assertNull(RecentsSelection.movedTask(before, before, 0))
        assertNull(RecentsSelection.movedTask(before, listOf(top.copy(lastActiveTime = 200), bottom.copy(lastActiveTime = 200)), 0))
        assertNull(RecentsSelection.movedTask(before, emptyList(), 0))
    }

    @Test
    fun `new or replaced tasks and invalid identities cannot redirect an unrelated launch`() {
        assertNull(RecentsSelection.movedTask(before, listOf(bottom.copy(taskId = 99, lastActiveTime = 200)), 0))
        assertNull(RecentsSelection.movedTask(before, listOf(bottom.copy(packageName = "dev.example.other", lastActiveTime = 200)), 0))
        val invalid = bottom.copy(taskId = -1)
        assertNull(RecentsSelection.movedTask(listOf(invalid), listOf(invalid.copy(lastActiveTime = 200)), 0))
    }
}
