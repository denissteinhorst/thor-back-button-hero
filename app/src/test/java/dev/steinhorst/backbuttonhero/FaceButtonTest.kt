package dev.steinhorst.backbuttonhero

import android.view.KeyEvent
import dev.steinhorst.backbuttonhero.input.AndroidButton
import dev.steinhorst.backbuttonhero.input.ComboButton
import dev.steinhorst.backbuttonhero.input.FaceButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceButtonTest {
    @Test
    fun `face buttons follow their printed label in both controller styles`() {
        // Measured on the Thor: with flip_button_layout 0 the label matches the key code, with 1 the
        // pad reports A as B and X as Y (and back).
        val codes = listOf(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y)
        assertEquals(listOf(FaceButton.A, FaceButton.B, FaceButton.X, FaceButton.Y), codes.map { FaceButton.of(it, flipped = false) })
        assertEquals(listOf(FaceButton.B, FaceButton.A, FaceButton.Y, FaceButton.X), codes.map { FaceButton.of(it, flipped = true) })
        assertNull(FaceButton.of(KeyEvent.KEYCODE_BUTTON_START, flipped = false))
    }

    @Test
    fun `Home Start and Select map to their own buttons`() {
        assertEquals(AndroidButton.HOME, AndroidButton.of(KeyEvent.KEYCODE_HOME))
        assertEquals(AndroidButton.START, AndroidButton.of(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals(AndroidButton.SELECT, AndroidButton.of(KeyEvent.KEYCODE_BUTTON_SELECT))
        assertNull(AndroidButton.of(KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun `new buttons start unassigned under keys of their own`() {
        val buttons: List<ComboButton> = AndroidButton.entries + FaceButton.entries
        buttons.forEach { assertEquals("", it.defaultAction) }
        assertEquals(listOf("home", "start", "select", "face_a", "face_b", "face_x", "face_y"), buttons.map { it.prefKey })
    }
}
