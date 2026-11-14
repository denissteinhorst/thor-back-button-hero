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
enum class DpadDirection(override val prefKey: String, val earlierDefault: String) : ComboButton {
    UP("dpad_up", Prefs.SHOULDER_BRIGHTNESS_UP),
    DOWN("dpad_down", Prefs.SHOULDER_BRIGHTNESS_DOWN),
    LEFT("dpad_left", Prefs.SHOULDER_VOLUME_DOWN),
    RIGHT("dpad_right", Prefs.SHOULDER_VOLUME_UP);

    // Everything starts unassigned. [earlierDefault] is what installs made before that still have
    // pinned by Prefs' migration, so an update never takes a shortcut away.
    override val defaultAction: String get() = ""

    companion object {
        /** The direction a hat axis value points to; null while the axis rests at 0. */
        /** The direction letter of a stick report such as `STICK L U`. */
        fun fromWire(letter: String): DpadDirection? = when (letter) {
            "U" -> UP
            "D" -> DOWN
            "L" -> LEFT
            "R" -> RIGHT
            else -> null
        }

        fun fromHat(horizontal: Boolean, value: Float): DpadDirection? = when {
            value <= -0.5f -> if (horizontal) LEFT else UP
            value >= 0.5f -> if (horizontal) RIGHT else DOWN
            else -> null
        }
    }
}

/**
 * The Back button's own gestures, freely assignable like every other control. Unlike the combos
 * they run without another button: the gesture is the trigger. The defaults are what each gesture
 * did before it became assignable. An unassigned gesture does nothing (a single press is simply
 * swallowed), and Back + button combos keep working either way.
 */
enum class BackGestureButton(override val prefKey: String, val earlierDefault: String) : ComboButton {
    SINGLE("back_single", Prefs.SHOULDER_HOME),
    DOUBLE("back_double", Prefs.SHOULDER_SWITCHER),
    TAP_HOLD("back_tap_hold", Prefs.SHOULDER_RECENTS),
    HOLD("back_hold", Prefs.SHOULDER_SWAP_APPS);

    /** Gestures start unassigned; [earlierDefault] is what they did before they became assignable. */
    override val defaultAction: String get() = ""
}
