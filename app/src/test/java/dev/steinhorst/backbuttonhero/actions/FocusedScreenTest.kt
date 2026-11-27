package dev.steinhorst.backbuttonhero.actions

import dev.steinhorst.backbuttonhero.prefs.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusedScreenTest {
    private val bottom = 4
    private var reads = 0

    private fun input(output: String?): () -> String? = {
        reads++
        output
    }

    @Test
    fun `a fixed route names its screen without a root read`() {
        assertEquals(Prefs.DISPLAY_TOP, FocusedScreen.resolve(Prefs.DISPLAY_TOP, bottom, input("  FocusedDisplayId: 4")))
        assertEquals(Prefs.DISPLAY_BOTTOM, FocusedScreen.resolve(Prefs.DISPLAY_BOTTOM, bottom, input("  FocusedDisplayId: 0")))
        assertEquals(0, reads)
        assertFalse(FocusedScreen.needsInputDisplay(Prefs.DISPLAY_TOP))
        assertFalse(FocusedScreen.needsInputDisplay(Prefs.DISPLAY_BOTTOM))
    }

    @Test
    fun `auto-lock reads Android's focused input display`() {
        assertTrue(FocusedScreen.needsInputDisplay(ControllerFocus.AUTO_TARGET))
        assertEquals(Prefs.DISPLAY_BOTTOM, FocusedScreen.resolve(ControllerFocus.AUTO_TARGET, bottom, input("  FocusedDisplayId: 4\n")))
        assertEquals(Prefs.DISPLAY_TOP, FocusedScreen.resolve(ControllerFocus.AUTO_TARGET, bottom, input("  FocusedDisplayId: 0\n")))
        assertEquals(2, reads)
    }

    @Test
    fun `an unreadable or unknown input display falls back to the top screen`() {
        for (output in listOf(null, "", "garbage", "  FocusedDisplayId: -1", "  FocusedDisplayId: 7")) {
            assertEquals(output.toString(), Prefs.DISPLAY_TOP, FocusedScreen.resolve(ControllerFocus.AUTO_TARGET, bottom, input(output)))
        }
        // Without a bottom screen there is nothing else the controller could drive.
        assertEquals(Prefs.DISPLAY_TOP, FocusedScreen.resolve(ControllerFocus.AUTO_TARGET, null, input("  FocusedDisplayId: 4")))
    }

    @Test
    fun `the root command reads one line of dumpsys input`() {
        assertEquals("dumpsys input | grep -m1 'FocusedDisplayId:'", FocusedScreen.INPUT_FOCUS_COMMAND)
    }

    @Test
    fun `a fixed route names its display id without reading the active window`() {
        var asked = 0
        val active = { asked++; bottom }
        assertEquals(0, FocusedScreen.displayId(ControllerFocus.TOP_VALUE, 0, bottom, active))
        assertEquals(bottom, FocusedScreen.displayId(ControllerFocus.BOTTOM_VALUE, 0, bottom, active))
        assertEquals(0, FocusedScreen.displayId(ControllerFocus.BOTTOM_VALUE, 0, null, active))
        assertEquals(0, asked)
    }

    @Test
    fun `auto-lock follows the display of the active window`() {
        for (setting in listOf(ControllerFocus.AUTO_VALUE, null)) {
            assertEquals(bottom, FocusedScreen.displayId(setting, 0, bottom) { bottom })
            assertEquals(0, FocusedScreen.displayId(setting, 0, bottom) { 0 })
            // No active window, an unknown display or no bottom screen: the top screen.
            assertEquals(0, FocusedScreen.displayId(setting, 0, bottom) { null })
            assertEquals(0, FocusedScreen.displayId(setting, 0, bottom) { 7 })
            assertEquals(0, FocusedScreen.displayId(setting, 0, null) { bottom })
        }
    }

    @Test
    fun `the blocking read follows Android's input display in Auto-lock and keeps a fixed route`() {
        assertEquals(bottom, FocusedScreen.readDisplayId(ControllerFocus.AUTO_VALUE, 0, bottom, 0, input("  FocusedDisplayId: 4")))
        // The accessibility answer lagging on the bottom screen does not win over the input display.
        assertEquals(0, FocusedScreen.readDisplayId(ControllerFocus.AUTO_VALUE, 0, bottom, bottom, input("  FocusedDisplayId: 0")))
        assertEquals(2, reads)
        // Unreadable or unknown: the fallback.
        assertEquals(bottom, FocusedScreen.readDisplayId(null, 0, bottom, bottom, input(null)))
        assertEquals(bottom, FocusedScreen.readDisplayId(null, 0, bottom, bottom, input("  FocusedDisplayId: 7")))
        reads = 0
        assertEquals(0, FocusedScreen.readDisplayId(ControllerFocus.TOP_VALUE, 0, bottom, bottom, input("  FocusedDisplayId: 4")))
        assertEquals(bottom, FocusedScreen.readDisplayId(ControllerFocus.BOTTOM_VALUE, 0, bottom, 0, input("  FocusedDisplayId: 0")))
        assertEquals(0, reads)
    }
}
