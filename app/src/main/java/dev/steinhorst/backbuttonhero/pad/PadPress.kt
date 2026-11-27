package dev.steinhorst.backbuttonhero.pad

import android.view.KeyEvent
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.input.ComboButton
import dev.steinhorst.backbuttonhero.input.DpadDirection
import dev.steinhorst.backbuttonhero.input.GestureClock
import dev.steinhorst.backbuttonhero.input.GestureScheduler
import dev.steinhorst.backbuttonhero.input.base
import dev.steinhorst.backbuttonhero.macro.Evdev
import dev.steinhorst.backbuttonhero.macro.Macro
import dev.steinhorst.backbuttonhero.macro.MacroButton
import dev.steinhorst.backbuttonhero.macro.MacroEvent
import dev.steinhorst.backbuttonhero.prefs.Prefs

/**
 * The pad buttons the "Press button" actions press, stored as [action]. Face buttons keep their printed
 * label and go through the controller style like a macro's ([MacroButton.evdev]); the triggers move their
 * analog axis with the key; Home is the pad's own house key; the D-pad is the pad's hat axis.
 */
enum class PadButton(
    val action: String,
    val labelRes: Int,
    private val macro: MacroButton? = null,
    private val androidKey: Int? = null,
    private val direction: DpadDirection? = null,
) {
    A(Prefs.SHOULDER_PAD_A, R.string.button_a, MacroButton.A),
    B(Prefs.SHOULDER_PAD_B, R.string.button_b, MacroButton.B),
    X(Prefs.SHOULDER_PAD_X, R.string.button_x, MacroButton.X),
    Y(Prefs.SHOULDER_PAD_Y, R.string.button_y, MacroButton.Y),
    L1(Prefs.SHOULDER_PAD_L1, R.string.shoulder_l1, MacroButton.L1, KeyEvent.KEYCODE_BUTTON_L1),
    R1(Prefs.SHOULDER_PAD_R1, R.string.shoulder_r1, MacroButton.R1, KeyEvent.KEYCODE_BUTTON_R1),
    L2(Prefs.SHOULDER_PAD_L2, R.string.shoulder_l2, MacroButton.L2, KeyEvent.KEYCODE_BUTTON_L2),
    R2(Prefs.SHOULDER_PAD_R2, R.string.shoulder_r2, MacroButton.R2, KeyEvent.KEYCODE_BUTTON_R2),
    L3(Prefs.SHOULDER_PAD_L3, R.string.button_l3, MacroButton.L3, KeyEvent.KEYCODE_BUTTON_THUMBL),
    R3(Prefs.SHOULDER_PAD_R3, R.string.button_r3, MacroButton.R3, KeyEvent.KEYCODE_BUTTON_THUMBR),
    START(Prefs.SHOULDER_PAD_START, R.string.button_start, MacroButton.START, KeyEvent.KEYCODE_BUTTON_START),
    SELECT(Prefs.SHOULDER_PAD_SELECT, R.string.button_select, MacroButton.SELECT, KeyEvent.KEYCODE_BUTTON_SELECT),
    HOME(Prefs.SHOULDER_PAD_HOME, R.string.button_home, androidKey = KeyEvent.KEYCODE_HOME),
    UP(Prefs.SHOULDER_PAD_UP, R.string.dpad_up, direction = DpadDirection.UP),
    DOWN(Prefs.SHOULDER_PAD_DOWN, R.string.dpad_down, direction = DpadDirection.DOWN),
    LEFT(Prefs.SHOULDER_PAD_LEFT, R.string.dpad_left, direction = DpadDirection.LEFT),
    RIGHT(Prefs.SHOULDER_PAD_RIGHT, R.string.dpad_right, direction = DpadDirection.RIGHT);

    /** What the helper emits to press ([down]) or release this button; [flipped] is `flip_button_layout`. */
    fun events(flipped: Boolean, down: Boolean): List<MacroEvent> {
        direction?.let {
            val horizontal = it == DpadDirection.LEFT || it == DpadDirection.RIGHT
            val sign = if (it == DpadDirection.UP || it == DpadDirection.LEFT) -1 else 1
            return listOf(MacroEvent(0, false, if (horizontal) Evdev.ABS_HAT0X else Evdev.ABS_HAT0Y, if (down) sign * Macro.FULL else 0))
        }
        val button = macro ?: return listOf(MacroEvent(0, true, KEY_HOME, if (down) 1 else 0))
        return listOfNotNull(
            MacroEvent(0, true, button.evdev(flipped), if (down) 1 else 0),
            button.triggerAxis?.let { MacroEvent(0, false, it, if (down) Macro.FULL else 0) },
        )
    }

    /** The Android key code the pressed button reaches the service with; null for the D-pad, which sends no key. */
    fun keyCode(flipped: Boolean): Int? = macro?.face?.keyCode(flipped) ?: androidKey

    companion object {
        /** The pad's house key (linux/input-event-codes.h KEY_HOME). */
        const val KEY_HOME = 102

        fun of(action: String): PadButton? = entries.firstOrNull { it.action == action }
    }
}

/**
 * The "Press button" actions: a hotkey holds one of the pad's buttons through the helper's clone for as
 * long as its trigger is held ([press] with `hold`), or taps it when the trigger has no physical hold,
 * such as Back's own gestures. Every exit releases: the trigger's release ([release]), [releaseAll] for
 * forgotten keys, a pause or the service going away, and [forget] when the helper and its clone are gone.
 *
 * The pressed button comes back to the service through the clone, possibly while Back is still held.
 * [passes] tells the service to hand it straight to the game, so it never starts a Back combo of its own.
 */
class PadPress(private val scheduler: GestureScheduler, private val clock: GestureClock, private val host: Host) {
    interface Host {
        /** The Thor's `flip_button_layout`, read when a press starts. */
        fun flipped(): Boolean
        /** Hands [events] to the helper; false when no helper holds the pad. */
        fun send(events: List<MacroEvent>): Boolean
        /** Lets the helper release everything it pressed for us. */
        fun releaseEverything()
        fun unavailable()
    }

    private class Active(val release: List<MacroEvent>, val keyCode: Int?)

    /** What each trigger (by its physical control) holds pressed. */
    private val held = mutableMapOf<ComboButton, PadButton>()
    private val pressed = mutableMapOf<PadButton, Active>()
    private val taps = mutableMapOf<ComboButton, Runnable>()
    /** Key codes we pressed: null while held, else when the release was sent. */
    private val guards = mutableMapOf<Int, Long?>()

    /** Whether any button is pressed on a hotkey's behalf right now. */
    val active: Boolean get() = pressed.isNotEmpty()

    fun press(trigger: ComboButton, button: PadButton, hold: Boolean) {
        val base = trigger.base
        release(base)
        if (button !in pressed) {
            val flipped = host.flipped()
            if (!host.send(button.events(flipped, down = true))) return host.unavailable()
            // The release is fixed now: a style switch while held must not release another code.
            val keyCode = button.keyCode(flipped)
            pressed[button] = Active(button.events(flipped, down = false), keyCode)
            keyCode?.let { guards[it] = null }
        }
        held[base] = button
        if (!hold) {
            val tap = Runnable { release(base) }
            taps[base] = tap
            scheduler.postDelayed(tap, TAP_MS)
        }
    }

    /** [trigger] let go; its button is released unless another trigger still holds it. */
    fun release(trigger: ComboButton) {
        val base = trigger.base
        taps.remove(base)?.let(scheduler::removeCallbacks)
        val button = held.remove(base) ?: return
        if (button in held.values) return
        val active = pressed.remove(button) ?: return
        host.send(active.release)
        active.keyCode?.let { guards[it] = clock.uptimeMillis() }
    }

    /** Every key counts as released (a pause, standby, the service going away): nothing may stay pressed. */
    fun releaseAll() {
        taps.values.forEach(scheduler::removeCallbacks)
        taps.clear()
        held.clear()
        if (pressed.isEmpty()) return
        host.releaseEverything()
        val now = clock.uptimeMillis()
        pressed.values.forEach { active -> active.keyCode?.let { guards[it] = now } }
        pressed.clear()
    }

    /** The helper and its clone are gone, and with them everything they pressed. */
    fun forget() {
        taps.values.forEach(scheduler::removeCallbacks)
        taps.clear()
        held.clear()
        pressed.clear()
        guards.clear()
    }

    /**
     * Whether a key event is the button we pressed, coming back through the clone: it belongs to the game.
     * [fromPad] is whether it came from a gamepad; [tracked] whether a router owns a physical press of that
     * key, whose release must keep its path so nothing stays held.
     */
    fun passes(keyCode: Int, action: Int, fromPad: Boolean, tracked: Boolean): Boolean {
        if (!fromPad || tracked || keyCode !in guards) return false
        val releasedAt = guards[keyCode] ?: return true
        if (clock.uptimeMillis() - releasedAt > GUARD_MS) {
            guards.remove(keyCode)
            return false
        }
        if (action == KeyEvent.ACTION_UP) guards.remove(keyCode)
        return true
    }

    companion object {
        /** How long a tap holds the button: long enough for a game's frame, like a macro's tap. */
        const val TAP_MS = 80L
        /** How long after a release its returning key still counts as ours. */
        const val GUARD_MS = 1000L
    }
}
