package dev.steinhorst.backbuttonhero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastAppTest {
    private val own = "dev.steinhorst.backbuttonhero"

    @Test
    fun `the app before the one in front is the last app`() {
        assertEquals("game", LastApp.previous(listOf("chrome", "game", "music"), "chrome", own))
    }

    @Test
    fun `switching makes the other one the last app, so presses flip between two`() {
        assertEquals("chrome", LastApp.previous(listOf("game", "chrome", "music"), "game", own))
    }

    @Test
    fun `with only a launcher in front the newest app is the last app`() {
        assertEquals("chrome", LastApp.previous(listOf("chrome", "game"), null, own))
    }

    @Test
    fun `our own settings are never the target`() {
        assertEquals("game", LastApp.previous(listOf("chrome", own, "game"), "chrome", own))
    }

    @Test
    fun `nothing else on the screen means no last app`() {
        assertNull(LastApp.previous(listOf("chrome"), "chrome", own))
        assertNull(LastApp.previous(emptyList(), null, own))
    }

    @Test
    fun `candidates keep the screen's order without the app in front and our own settings`() {
        assertEquals(listOf("game", "music"), LastApp.candidates(listOf("chrome", own, "game", "music"), "chrome", own))
    }

    @Test
    fun `a task already on the screen is chosen`() {
        assertEquals(7, LastApp.taskOn(listOf(7 to 4), 4))
    }

    @Test
    fun `a task that moved to the other screen is never pulled back`() {
        assertNull(LastApp.taskOn(listOf(7 to 0), 4))
    }

    @Test
    fun `with tasks on both screens the one on this screen is chosen`() {
        assertEquals(9, LastApp.taskOn(listOf(7 to 0, 9 to 4), 4))
    }

    @Test
    fun `a task no screen holds may come back on this one`() {
        assertEquals(7, LastApp.taskOn(listOf(7 to -1), 0))
    }
}
