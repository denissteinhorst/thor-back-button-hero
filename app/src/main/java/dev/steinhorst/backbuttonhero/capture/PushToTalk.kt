package dev.steinhorst.backbuttonhero.capture

import android.view.KeyEvent

/** Which keys can be the push-to-talk button of a recording, and how they are named. */
object PushToTalk {
    /** Back and Home carry the app's own gestures, Power and the app switcher belong to the system,
     *  and the D-pad never reaches the key filter (it is a hat axis on the Thor). */
    private val RESERVED = setOf(
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_UP_LEFT, KeyEvent.KEYCODE_DPAD_UP_RIGHT,
        KeyEvent.KEYCODE_DPAD_DOWN_LEFT, KeyEvent.KEYCODE_DPAD_DOWN_RIGHT, KeyEvent.KEYCODE_UNKNOWN,
    )

    private val NAMES = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to "A", KeyEvent.KEYCODE_BUTTON_B to "B",
        KeyEvent.KEYCODE_BUTTON_X to "X", KeyEvent.KEYCODE_BUTTON_Y to "Y",
        KeyEvent.KEYCODE_BUTTON_L1 to "L1", KeyEvent.KEYCODE_BUTTON_R1 to "R1",
        KeyEvent.KEYCODE_BUTTON_L2 to "L2", KeyEvent.KEYCODE_BUTTON_R2 to "R2",
        KeyEvent.KEYCODE_BUTTON_THUMBL to "L3", KeyEvent.KEYCODE_BUTTON_THUMBR to "R3",
        KeyEvent.KEYCODE_BUTTON_START to "Start", KeyEvent.KEYCODE_BUTTON_SELECT to "Select",
    )

    fun isAllowed(keyCode: Int): Boolean = keyCode > 0 && keyCode <= KeyEvent.getMaxKeyCode() && keyCode !in RESERVED

    /** A short name for [keyCode]: the controller's own label where there is one, else Android's key name. */
    fun label(keyCode: Int): String = NAMES[keyCode]
        ?: KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").removePrefix("BUTTON_")
            .lowercase().split('_').joinToString(" ") { part -> part.replaceFirstChar(Char::uppercase) }
}
