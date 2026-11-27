package dev.steinhorst.backbuttonhero.input

import android.view.KeyEvent

/**
 * L3/R3 while Back is held. A press is ours only when the stick has a click action; otherwise
 * the game keeps it, like an unassigned shoulder button. The Thor mirrors its controller, so
 * both devices' DOWN/UP are consumed and the gesture sees one press. Each stick's
 * [StickClickMachine] times press, double press and tap-and-hold; the layer is fixed when the
 * click begins.
 */
class StickClickRouter(scheduler: GestureScheduler, clock: GestureClock, private val host: Host) {
    interface Host {
        val interceptionEnabled: Boolean
        /** Back, or in dual mode Home, is currently held. */
        val modifierDown: Boolean
        /** Home owns the current press in dual mode. */
        val homeDown: Boolean
        val repeatingButton: ComboButton?
        fun allows(button: ComboButton): Boolean
        fun markComboFired()
        fun stopLevelRepeat()
        fun doubleMs(stick: Stick, home: Boolean): Long
        fun tapHoldMs(stick: Stick, home: Boolean): Long
        fun action(button: ComboButton): String
        fun fire(button: ComboButton)
        /** Whether [button]'s action holds something for as long as the click is held (a pad button press). */
        fun holdsWhilePressed(button: ComboButton): Boolean = false
        /** Runs [button] at the click's DOWN, held until [released]. */
        fun press(button: ComboButton) {}
        /** [stick]'s click is released on every mirrored device. */
        fun released(stick: Stick) {}
    }

    private val keys = PhysicalKeyTracker()
    /** Per stick: whether its click gesture began under Home in dual mode, so it runs the Home layer. */
    private val home = mutableMapOf<Stick, Boolean>()
    /**
     * Sticks whose click runs its press action from the DOWN and holds it: with neither a double press
     * nor tap-and-hold to tell apart, the press need not wait for the release.
     */
    private val holding = mutableSetOf<Stick>()
    private val machines = Stick.entries.associateWith { stick ->
        StickClickMachine(
            scheduler,
            clock,
            { host.doubleMs(stick, home[stick] == true) },
            { host.tapHoldMs(stick, home[stick] == true) },
            { host.action(button(stick, it)).isNotEmpty() },
        ) { host.fire(button(stick, it)) }
    }

    private fun button(stick: Stick, click: StickClick): ComboButton =
        StickClickButton.of(stick, click).let { if (home[stick] == true) HomeLayerButton.of(it) else it }

    fun isDown(keyCode: Int): Boolean = keys.isDown(keyCode)

    private fun holdsFromDown(stick: Stick): Boolean =
        host.holdsWhilePressed(button(stick, StickClick.PRESS)) &&
            host.action(button(stick, StickClick.DOUBLE)).isEmpty() && host.action(button(stick, StickClick.TAP_HOLD)).isEmpty()

    fun handle(stick: Stick, action: Int, keyCode: Int, deviceId: Int, repeatCount: Int): Boolean {
        val machine = machines.getValue(stick)
        return when (action) {
            KeyEvent.ACTION_DOWN -> {
                if (!host.interceptionEnabled || !host.modifierDown || !host.allows(StickClickButton.of(stick, StickClick.PRESS))) return false
                val first = keys.down(keyCode, deviceId)
                if (first && repeatCount == 0) {
                    home[stick] = host.homeDown
                    if (holdsFromDown(stick)) {
                        holding += stick
                        host.markComboFired()
                        host.press(button(stick, StickClick.PRESS))
                        return true
                    }
                    if (!machine.down()) { keys.up(keyCode, deviceId); return false }
                    host.markComboFired()
                }
                keys.isDown(keyCode)
            }
            KeyEvent.ACTION_UP -> {
                val tracked = keys.up(keyCode, deviceId)
                if (tracked && !keys.isDown(keyCode)) {
                    if ((host.repeatingButton?.base as? StickClickButton)?.stick == stick) host.stopLevelRepeat()
                    // First, so a press the release itself fires (a tap) is not cut short.
                    host.released(stick)
                    if (!holding.remove(stick)) machine.up()
                }
                tracked
            }
            else -> false
        }
    }

    fun forgetLayers() = home.clear()

    /** Every click as released and every pending gesture dropped. */
    fun reset() {
        machines.values.forEach(StickClickMachine::reset)
        keys.clear()
        holding.clear()
    }
}
