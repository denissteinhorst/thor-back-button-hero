package dev.steinhorst.backbuttonhero.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromptKeysTest {
    @Test
    fun `confirm and cancel follow Android's convention whatever the style`() {
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, PromptKeys.CONFIRM)
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, PromptKeys.CANCEL)
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, PromptKeys.CLOSE_ALL)
    }

    @Test
    fun `Standard style puts confirm on the right and cancel at the bottom`() {
        assertEquals(FacePosition.RIGHT, PromptKeys.position(PromptKeys.CONFIRM, flipped = false))
        assertEquals(FacePosition.BOTTOM, PromptKeys.position(PromptKeys.CANCEL, flipped = false))
        assertEquals(FacePosition.TOP, PromptKeys.position(PromptKeys.CLOSE_ALL, flipped = false))
    }

    @Test
    fun `Xbox style puts confirm at the bottom and cancel on the right`() {
        assertEquals(FacePosition.BOTTOM, PromptKeys.position(PromptKeys.CONFIRM, flipped = true))
        assertEquals(FacePosition.RIGHT, PromptKeys.position(PromptKeys.CANCEL, flipped = true))
        assertEquals(FacePosition.LEFT, PromptKeys.position(PromptKeys.CLOSE_ALL, flipped = true))
    }

    @Test
    fun `printed labels keep their place and other keys have none`() {
        assertEquals(
            listOf(FacePosition.RIGHT, FacePosition.BOTTOM, FacePosition.TOP, FacePosition.LEFT),
            listOf(FaceButton.A, FaceButton.B, FaceButton.X, FaceButton.Y).map { it.position },
        )
        assertNull(PromptKeys.position(KeyEvent.KEYCODE_BUTTON_START, flipped = false))
    }
}
