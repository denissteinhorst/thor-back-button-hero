package dev.steinhorst.backbuttonhero.pad

import dev.steinhorst.backbuttonhero.input.DpadDirection
import dev.steinhorst.backbuttonhero.input.Stick
import dev.steinhorst.backbuttonhero.input.StickButton
import dev.steinhorst.backbuttonhero.macro.MacroEvent

/**
 * The service's half of the line protocol to the root pad helper (docs/HELPER.md): every command as
 * the exact line `padhelper.c` parses, and the bit masks it expects. Sending, the connection and the
 * helper's events stay with [PadHelper].
 */
internal object HelperProtocol {
    fun switcher(wireValue: Int) = "SWITCHER $wireValue\n"

    /** Bits follow DpadDirection's order: up 1, down 2, left 4, right 8. */
    fun passthroughMask(directions: Set<DpadDirection>) = directions.sumOf { 1 shl it.ordinal }
    fun passthrough(mask: Int) = "PASS $mask\n"

    fun stickMask(sticks: Set<Stick>) = sticks.sumOf { it.bit }
    fun sticks(mask: Int) = "STICKS $mask\n"

    /** The stick's index times four plus [DpadDirection]'s order, like [passthroughMask]. */
    fun stickPassthroughMask(buttons: Set<StickButton>) = buttons.sumOf { 1 shl (it.stick.ordinal * 4 + it.direction.ordinal) }
    fun stickPassthrough(mask: Int) = "SPASS $mask\n"

    fun homeMode(mode: Int) = "HOMEBACK $mode\n"
    fun recording(on: Boolean) = "RECORD ${if (on) 1 else 0}\n"

    fun macro(id: Int, events: List<MacroEvent>) = buildString {
        append("MCLEAR\n")
        events.forEach { append(it.wire()).append('\n') }
        append("MPLAY ").append(id).append('\n')
    }
    const val MACRO_STOP = "MSTOP\n"

    fun gyro(stick: Stick?, x: Int, y: Int): String {
        val wire = when (stick) { null -> 0; Stick.LEFT -> 1; Stick.RIGHT -> 2 }
        return "GYRO $wire $x $y\n"
    }

    /** Linux key codes, emitted by the existing clone without another root-shell round trip. */
    fun navigation(direction: DpadDirection): String = "NAV ${when (direction) {
        DpadDirection.UP -> 103
        DpadDirection.DOWN -> 108
        DpadDirection.LEFT -> 105
        DpadDirection.RIGHT -> 106
    }}\n"
}
