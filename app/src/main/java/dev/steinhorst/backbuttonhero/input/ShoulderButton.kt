package dev.steinhorst.backbuttonhero.input

import android.view.KeyEvent
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.pad.DpadOverlay
import dev.steinhorst.backbuttonhero.pad.PadHelper
import dev.steinhorst.backbuttonhero.prefs.Prefs

/** A control that runs a configurable action while Back is held (see [Prefs.shoulderAction]). */
interface ComboButton {
    val prefKey: String
    val name: String
    /** The action before the user has chosen one. */
    val defaultAction: String
}

/**
 * The Home layer's copy of [base] in dual mode (see docs/HOME-DUAL-MODE.md): the same control with its own
 * binding stored under [PREFIX], so everything keyed by [prefKey] works on it unchanged.
 */
class HomeLayerButton private constructor(val base: ComboButton) : ComboButton {
    override val prefKey: String = PREFIX + base.prefKey
    override val name: String = "HOME_" + base.name
    override val defaultAction: String get() = base.defaultAction

    override fun toString(): String = name

    companion object {
        const val PREFIX = "dual_"
        private val cache = mutableMapOf<ComboButton, HomeLayerButton>()

        /** One instance per control, so the wrappers compare and hash like the enums they wrap. */
        fun of(button: ComboButton): HomeLayerButton =
            button as? HomeLayerButton ?: synchronized(cache) { cache.getOrPut(button) { HomeLayerButton(button) } }
    }
}

/** The control behind a binding: itself, or what a [HomeLayerButton] wraps. */
val ComboButton.base: ComboButton get() = (this as? HomeLayerButton)?.base ?: this

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
 * Home, Start and Select, combined with holding Back. Home is the pad's house key only: AYN's own
 * button reports the same KEYCODE_HOME from another input device and keeps its panel role.
 */
enum class AndroidButton(override val prefKey: String, val keyCode: Int) : ComboButton {
    HOME("home", KeyEvent.KEYCODE_HOME),
    START("start", KeyEvent.KEYCODE_BUTTON_START),
    SELECT("select", KeyEvent.KEYCODE_BUTTON_SELECT);

    override val defaultAction: String get() = ""

    companion object {
        fun of(keyCode: Int): AndroidButton? = entries.firstOrNull { it.keyCode == keyCode }
    }
}

/** Where a face button sits on the Thor's diamond. */
enum class FacePosition { LEFT, TOP, RIGHT, BOTTOM }

/**
 * The four face buttons, named and stored by their printed label. The Thor's style switch swaps the
 * keys the pad reports (A with B, X with Y), so the key code behind a label depends on it. The
 * labels themselves never move: Y left, X top, A right, B bottom.
 */
enum class FaceButton(
    override val prefKey: String,
    private val keyCode: Int,
    private val flippedKeyCode: Int,
    val position: FacePosition,
) : ComboButton {
    A("face_a", KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, FacePosition.RIGHT),
    B("face_b", KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_A, FacePosition.BOTTOM),
    X("face_x", KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y, FacePosition.TOP),
    Y("face_y", KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_X, FacePosition.LEFT);

    override val defaultAction: String get() = ""

    /** The key code the pad reports for this printed button; [flipped] as in [of]. */
    fun keyCode(flipped: Boolean): Int = if (flipped) flippedKeyCode else keyCode

    companion object {
        /** The printed button behind [keyCode]; [flipped] is the Thor's `flip_button_layout` setting. */
        fun of(keyCode: Int, flipped: Boolean): FaceButton? =
            entries.firstOrNull { (if (flipped) it.flippedKeyCode else it.keyCode) == keyCode }
    }
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
