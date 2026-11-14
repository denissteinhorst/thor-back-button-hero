package dev.steinhorst.backbuttonhero

import android.view.KeyEvent
import kotlin.math.abs

enum class Stick(val wire: String, val bit: Int, val prefix: String, val keyCode: Int) {
    LEFT("L", 1, "lstick", KeyEvent.KEYCODE_BUTTON_THUMBL),
    RIGHT("R", 2, "rstick", KeyEvent.KEYCODE_BUTTON_THUMBR);

    companion object {
        fun fromWire(value: String): Stick? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * The four directions of each analog stick, combined with holding Back. Like the D-pad they are
 * unassigned until chosen, and arrive from [PadHelper] or [DpadOverlay] rather than onKeyEvent.
 */
enum class StickButton(val stick: Stick, val direction: DpadDirection, override val prefKey: String) : ComboButton {
    LEFT_UP(Stick.LEFT, DpadDirection.UP, "lstick_up"),
    LEFT_DOWN(Stick.LEFT, DpadDirection.DOWN, "lstick_down"),
    LEFT_LEFT(Stick.LEFT, DpadDirection.LEFT, "lstick_left"),
    LEFT_RIGHT(Stick.LEFT, DpadDirection.RIGHT, "lstick_right"),
    RIGHT_UP(Stick.RIGHT, DpadDirection.UP, "rstick_up"),
    RIGHT_DOWN(Stick.RIGHT, DpadDirection.DOWN, "rstick_down"),
    RIGHT_LEFT(Stick.RIGHT, DpadDirection.LEFT, "rstick_left"),
    RIGHT_RIGHT(Stick.RIGHT, DpadDirection.RIGHT, "rstick_right");

    override val defaultAction: String get() = ""

    companion object {
        fun of(stick: Stick, direction: DpadDirection): StickButton =
            entries.first { it.stick == stick && it.direction == direction }
    }
}

/** What pressing a stick in (L3/R3) can do while Back is held; timed like Back's own gestures. */
enum class StickClick(val suffix: String) { PRESS("press"), DOUBLE("double"), TAP_HOLD("tap_hold") }

/** A stick's click gestures, combined with holding Back; unassigned until chosen like the directions. */
enum class StickClickButton(val stick: Stick, val click: StickClick) : ComboButton {
    LEFT_PRESS(Stick.LEFT, StickClick.PRESS),
    LEFT_DOUBLE(Stick.LEFT, StickClick.DOUBLE),
    LEFT_TAP_HOLD(Stick.LEFT, StickClick.TAP_HOLD),
    RIGHT_PRESS(Stick.RIGHT, StickClick.PRESS),
    RIGHT_DOUBLE(Stick.RIGHT, StickClick.DOUBLE),
    RIGHT_TAP_HOLD(Stick.RIGHT, StickClick.TAP_HOLD);

    override val prefKey: String get() = "${stick.prefix}_${click.suffix}"
    override val defaultAction: String get() = ""

    companion object {
        fun of(stick: Stick, click: StickClick): StickClickButton =
            entries.first { it.stick == stick && it.click == click }
    }
}

/**
 * Turns one stick's two axes (each -1..1, up is negative) into a single press per deflection.
 * A direction fires when an axis crosses [ENGAGE]; nothing else fires until the stick is back
 * inside [NEUTRAL] on both axes, so a sweep from one direction to another is still one press.
 * padhelper.c mirrors these thresholds for the root path.
 */
class StickDetector {
    sealed interface Edge {
        data class Pressed(val direction: DpadDirection) : Edge
        data object Released : Edge
    }

    private var engaged = false

    fun reset() { engaged = false }

    fun update(x: Float, y: Float): Edge? {
        if (engaged) {
            if (abs(x) < NEUTRAL && abs(y) < NEUTRAL) {
                engaged = false
                return Edge.Released
            }
            return null
        }
        if (maxOf(abs(x), abs(y)) < ENGAGE) return null
        engaged = true
        return Edge.Pressed(
            if (abs(x) >= abs(y)) (if (x < 0) DpadDirection.LEFT else DpadDirection.RIGHT)
            else (if (y < 0) DpadDirection.UP else DpadDirection.DOWN),
        )
    }

    companion object {
        const val ENGAGE = 0.6f
        const val NEUTRAL = 0.3f
    }
}
