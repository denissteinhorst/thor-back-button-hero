package dev.steinhorst.backbuttonhero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskMovePolicyTest {
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
