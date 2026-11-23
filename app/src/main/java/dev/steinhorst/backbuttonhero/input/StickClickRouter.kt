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
    }

    private val keys = PhysicalKeyTracker()
    /** Per stick: whether its click gesture began under Home in dual mode, so it runs the Home layer. */
    private val home = mutableMapOf<Stick, Boolean>()
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

    fun handle(stick: Stick, action: Int, keyCode: Int, deviceId: Int, repeatCount: Int): Boolean {
        val machine = machines.getValue(stick)
        return when (action) {
            KeyEvent.ACTION_DOWN -> {
                if (!host.interceptionEnabled || !host.modifierDown || !host.allows(StickClickButton.of(stick, StickClick.PRESS))) return false
                val first = keys.down(keyCode, deviceId)
                if (first && repeatCount == 0) {
                    home[stick] = host.homeDown
                    if (!machine.down()) { keys.up(keyCode, deviceId); return false }
                    host.markComboFired()
                }
                keys.isDown(keyCode)
            }
            KeyEvent.ACTION_UP -> {
                val tracked = keys.up(keyCode, deviceId)
                if (tracked && !keys.isDown(keyCode)) {
                    if ((host.repeatingButton?.base as? StickClickButton)?.stick == stick) host.stopLevelRepeat()
                    machine.up()
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
    }
}
