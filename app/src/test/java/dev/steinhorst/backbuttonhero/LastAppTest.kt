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
}
