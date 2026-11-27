package dev.steinhorst.backbuttonhero.input

import android.view.KeyEvent

/**
 * The controller keys of the app's prompts and switchers, after Android's convention: whichever
 * button reports BUTTON_A confirms, BUTTON_B cancels, and BUTTON_X closes all apps in the switcher.
 * The Thor's style switch decides which physical button that is (Standard: A right, B bottom; Xbox:
 * the other way round), so the keys stay fixed and only the hints read the switch.
 */
object PromptKeys {
    const val CONFIRM = KeyEvent.KEYCODE_BUTTON_A
    const val CANCEL = KeyEvent.KEYCODE_BUTTON_B
    const val CLOSE_ALL = KeyEvent.KEYCODE_BUTTON_X

    /** Where the button reporting [keyCode] sits; [flipped] is the Thor's `flip_button_layout` setting. */
    fun position(keyCode: Int, flipped: Boolean): FacePosition? = FaceButton.of(keyCode, flipped)?.position
}
