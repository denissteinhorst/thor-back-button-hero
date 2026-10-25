package dev.steinhorst.backbuttonhero

import android.view.KeyEvent

/** A control that runs a configurable action while Back is held (see [Prefs.shoulderAction]). */
interface ComboButton {
    val prefKey: String
    val name: String
    /** The action before the user has chosen one. */
    val defaultAction: String
}

/** The four shoulder buttons that can be combined with holding Back to run a chosen action. */
enum class ShoulderButton(override val prefKey: String, val keyCode: Int) : ComboButton {
    L1("l1", KeyEvent.KEYCODE_BUTTON_L1),
    L2("l2", KeyEvent.KEYCODE_BUTTON_L2),
    R1("r1", KeyEvent.KEYCODE_BUTTON_R1),
    R2("r2", KeyEvent.KEYCODE_BUTTON_R2);

    // L1/R1 get their navigation-flick defaults from the one-time migration in Prefs.
    override val defaultAction: String get() = ""
}

/**
 * The four D-pad directions, combined with holding Back. The Thor's D-pad reports a hat axis rather
 * than key events, so these arrive from [PadHelper] or [DpadOverlay], not from onKeyEvent.
 */
enum class DpadDirection(override val prefKey: String, override val defaultAction: String) : ComboButton {
    UP("dpad_up", Prefs.SHOULDER_BRIGHTNESS_UP),
    DOWN("dpad_down", Prefs.SHOULDER_BRIGHTNESS_DOWN),
    LEFT("dpad_left", Prefs.SHOULDER_VOLUME_DOWN),
    RIGHT("dpad_right", Prefs.SHOULDER_VOLUME_UP);

    companion object {
        /** The direction a hat axis value points to; null while the axis rests at 0. */
        fun fromHat(horizontal: Boolean, value: Float): DpadDirection? = when {
            value <= -0.5f -> if (horizontal) LEFT else UP
            value >= 0.5f -> if (horizontal) RIGHT else DOWN
            else -> null
        }
    }
}
