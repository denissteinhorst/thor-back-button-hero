package dev.steinhorst.backbuttonhero.macro

import android.view.KeyEvent
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.input.FaceButton
import dev.steinhorst.backbuttonhero.input.Stick
import dev.steinhorst.backbuttonhero.input.base
import dev.steinhorst.backbuttonhero.pad.PadHelper
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Eight directions on a 3x3 grid; [dx]/[dy] are -1, 0 or 1 with up negative, like the pad's axes. */
enum class MacroDirection(val wire: String, val dx: Int, val dy: Int) {
    UP("U", 0, -1),
    UP_RIGHT("UR", 1, -1),
    RIGHT("R", 1, 0),
    DOWN_RIGHT("DR", 1, 1),
    DOWN("D", 0, 1),
    DOWN_LEFT("DL", -1, 1),
    LEFT("L", -1, 0),
    UP_LEFT("UL", -1, -1);

    companion object {
        fun of(dx: Int, dy: Int): MacroDirection? = entries.firstOrNull { it.dx == dx && it.dy == dy }

        fun fromWire(value: String): MacroDirection? = entries.firstOrNull { it.wire == value }

        /** The 45 degree sector a deflection points into; [y] grows downwards. */
        fun ofAngle(x: Float, y: Float): MacroDirection {
            val sector = Math.floorMod((atan2(y, x) / (PI / 4)).roundToInt(), 8)
            return listOf(RIGHT, DOWN_RIGHT, DOWN, DOWN_LEFT, LEFT, UP_LEFT, UP, UP_RIGHT)[sector]
        }
    }
}

/**
 * The pad's buttons a macro can press. Back and Home are missing on purpose: they start the macro
 * themselves. The face buttons are kept by their printed label, because the Thor's style switch swaps
 * the codes the pad reports for them (see [FaceButton]); the others have fixed evdev codes. The
 * triggers also report an analog axis, which playback moves together with the key.
 */
enum class MacroButton(val wire: String, private val code: Int, val face: FaceButton? = null, val triggerAxis: Int? = null) {
    A("A", 0, FaceButton.A),
    B("B", 0, FaceButton.B),
    X("X", 0, FaceButton.X),
    Y("Y", 0, FaceButton.Y),
    L1("L1", Evdev.BTN_TL),
    R1("R1", Evdev.BTN_TR),
    L2("L2", Evdev.BTN_TL2, triggerAxis = Evdev.ABS_BRAKE),
    R2("R2", Evdev.BTN_TR2, triggerAxis = Evdev.ABS_GAS),
    L3("L3", Evdev.BTN_THUMBL),
    R3("R3", Evdev.BTN_THUMBR),
    START("Start", Evdev.BTN_START),
    SELECT("Select", Evdev.BTN_SELECT);

    /** The evdev key code this button sends; [flipped] is the Thor's `flip_button_layout` setting. */
    fun evdev(flipped: Boolean): Int = face?.let { Evdev.ofKeyCode(it.keyCode(flipped)) } ?: code

    companion object {
        fun fromWire(value: String): MacroButton? = entries.firstOrNull { it.wire == value }

        /** The button behind an evdev key code, or null for keys a macro never records. */
        fun ofEvdev(code: Int, flipped: Boolean): MacroButton? = entries.firstOrNull { it.evdev(flipped) == code }
    }
}

/** One thing a step presses: a button, the D-pad or a stick, each held in one direction. */
sealed interface MacroInput {
    val wire: String
    /** Inputs on the same channel cannot be pressed at once: one D-pad, one direction per stick. */
    val channel: String

    data class Button(val button: MacroButton) : MacroInput {
        override val wire: String get() = button.wire
        override val channel: String get() = button.wire
    }

    data class Dpad(val direction: MacroDirection) : MacroInput {
        override val wire: String get() = "D:${direction.wire}"
        override val channel: String get() = "D"
    }

    data class Analog(val stick: Stick, val direction: MacroDirection) : MacroInput {
        override val wire: String get() = "${stick.wire}S:${direction.wire}"
        override val channel: String get() = "${stick.wire}S"
    }

    companion object {
        fun fromWire(value: String): MacroInput? {
            val parts = value.split(':')
            if (parts.size == 1) return MacroButton.fromWire(value)?.let(::Button)
            if (parts.size != 2) return null
            val direction = MacroDirection.fromWire(parts[1]) ?: return null
            return when (parts[0]) {
                "D" -> Dpad(direction)
                "LS" -> Analog(Stick.LEFT, direction)
                "RS" -> Analog(Stick.RIGHT, direction)
                else -> null
            }
        }
    }
}

/** Presses [inputs] together, holds them for [holdMs], then waits [gapMs] before the next step. */
data class MacroStep(val inputs: List<MacroInput>, val holdMs: Int, val gapMs: Int = 0)

/** One raw input change for the helper: a key (0/1) or an axis in per mille of its range. */
data class MacroEvent(val atMs: Long, val key: Boolean, val code: Int, val value: Int) {
    fun wire(): String = "MEV $atMs ${if (key) "K" else "A"} $code $value"
}

/**
 * A recorded sequence of pad inputs, bound to one hotkey. Steps run strictly one after another; inputs
 * pressed together form one step. Stored as compact text ([encode]), which [decode] reads back with
 * every value checked, so neither a damaged setting nor a backup can produce an invalid macro.
 */
data class Macro(val steps: List<MacroStep>) {
    val totalMs: Long get() = steps.sumOf { it.holdMs.toLong() + it.gapMs }

    fun encode(): String = VERSION + steps.joinToString("|") { step ->
        "${step.inputs.joinToString("+") { it.wire }};${step.holdMs};${step.gapMs}"
    }

    /**
     * The helper's timeline: every press and release from the start. Releases come before presses at
     * the same moment, and an input pressed again right after its release gets a short pause, so the
     * game sees two separate presses rather than one long one.
     */
    fun events(flipped: Boolean): List<MacroEvent> {
        val result = mutableListOf<MacroEvent>()
        var at = 0L
        steps.forEachIndexed { index, step ->
            step.inputs.forEach { result += press(it, at, flipped) }
            at += step.holdMs
            step.inputs.forEach { result += release(it, at, flipped) }
            val next = steps.getOrNull(index + 1)
            val repeats = next != null && next.inputs.any { input -> step.inputs.any { it.channel == input.channel } }
            at += if (repeats) maxOf(step.gapMs, MIN_REPRESS_MS) else step.gapMs
        }
        return result.withIndex().sortedWith(compareBy({ it.value.atMs }, { it.value.value != 0 }, { it.index })).map { it.value }
    }

    private fun press(input: MacroInput, at: Long, flipped: Boolean): List<MacroEvent> = when (input) {
        is MacroInput.Button -> listOfNotNull(
            MacroEvent(at, true, input.button.evdev(flipped), 1),
            input.button.triggerAxis?.let { MacroEvent(at, false, it, FULL) },
        )
        is MacroInput.Dpad -> listOfNotNull(
            input.direction.dx.takeIf { it != 0 }?.let { MacroEvent(at, false, Evdev.ABS_HAT0X, it * FULL) },
            input.direction.dy.takeIf { it != 0 }?.let { MacroEvent(at, false, Evdev.ABS_HAT0Y, it * FULL) },
        )
        is MacroInput.Analog -> {
            // A diagonal sits on the stick's round edge, not in the corner of its square range.
            val scale = if (input.direction.dx != 0 && input.direction.dy != 0) DIAGONAL else FULL
            val (x, y) = stickAxes(input.stick)
            listOf(MacroEvent(at, false, x, input.direction.dx * scale), MacroEvent(at, false, y, input.direction.dy * scale))
        }
    }

    private fun release(input: MacroInput, at: Long, flipped: Boolean): List<MacroEvent> =
        press(input, at, flipped).map { it.copy(value = 0) }

    companion object {
        const val MAX_STEPS = 200
        const val MAX_TOTAL_MS = 60_000L
        const val MAX_INPUTS = 8
        const val MIN_HOLD_MS = 20
        const val MAX_HOLD_MS = 10_000
        const val MIN_GAP_MS = 0
        const val MAX_GAP_MS = 10_000
        const val STEP_MS = 10
        /** Below this the press after a release of the same input can merge with it in the game. */
        const val MIN_REPRESS_MS = 20
        /** A full deflection, in per mille of the axis range. */
        const val FULL = 1000
        const val DIAGONAL = 707
        private const val VERSION = "1:"

        fun stickAxes(stick: Stick): Pair<Int, Int> =
            if (stick == Stick.LEFT) Evdev.ABS_X to Evdev.ABS_Y else Evdev.ABS_Z to Evdev.ABS_RZ

        /** Rounds to the editor's 10 ms steps. */
        fun round(ms: Long): Int = ((ms + STEP_MS / 2) / STEP_MS * STEP_MS).toInt()

        fun clampHold(ms: Int): Int = ms.coerceIn(MIN_HOLD_MS, MAX_HOLD_MS)
        fun clampGap(ms: Int): Int = ms.coerceIn(MIN_GAP_MS, MAX_GAP_MS)

        /** The stored macro, or null when [text] is missing, from another format or damaged anywhere. */
        fun decode(text: String?): Macro? {
            if (text == null || !text.startsWith(VERSION)) return null
            val body = text.removePrefix(VERSION)
            if (body.isEmpty()) return null
            val parts = body.split('|')
            if (parts.size > MAX_STEPS) return null
            val steps = parts.map { part ->
                val fields = part.split(';')
                if (fields.size != 3) return null
                val names = fields[0].split('+')
                if (names.size > MAX_INPUTS) return null
                val inputs = names.map { MacroInput.fromWire(it) ?: return null }
                if (inputs.map { it.channel }.toSet().size != inputs.size) return null
                val hold = fields[1].toIntOrNull()?.takeIf { it in MIN_HOLD_MS..MAX_HOLD_MS } ?: return null
                val gap = fields[2].toIntOrNull()?.takeIf { it in MIN_GAP_MS..MAX_GAP_MS } ?: return null
                MacroStep(inputs, hold, gap)
            }
            return Macro(steps).takeIf { it.totalMs <= MAX_TOTAL_MS }
        }
    }
}

/**
 * A macro in the library under its own name. Hotkeys refer to it by [id], so one macro can serve
 * several hotkeys and an edit reaches all of them.
 */
data class SavedMacro(val id: String, val name: String, val macro: Macro) {
    companion object {
        const val MAX_NAME = 40
        private val ID = Regex("m[1-9][0-9]{0,8}")

        fun isId(id: String?): Boolean = id != null && ID.matches(id)

        /** [name] without control characters (they would break the stored lines), or [fallback] when nothing is left. */
        fun cleanName(name: String?, fallback: String): String =
            name.orEmpty().filterNot { it.isISOControl() }.trim().take(MAX_NAME).ifEmpty { fallback }

        /** [base] when no macro is called that yet, else the first free "[base] (2)", "(3)", … */
        fun freeName(base: String, names: Collection<String>): String {
            val stem = base.replace(NUMBERED, "").ifEmpty { base }
            return generateSequence(1) { it + 1 }.map { if (it == 1) stem else "$stem ($it)" }.first { it !in names }
        }

        private val NUMBERED = Regex(" \\(\\d+\\)$")

        fun nextId(library: List<SavedMacro>): String = "m${(library.maxOfOrNull { it.id.drop(1).toInt() } ?: 0) + 1}"

        /** One line per macro: id, name and the encoded macro, separated by tabs. */
        fun encode(library: List<SavedMacro>): String = library.joinToString("\n") { "${it.id}\t${it.name}\t${it.macro.encode()}" }

        /** The library in [text]; damaged lines and repeated ids are left out. */
        fun decode(text: String?): List<SavedMacro> = text.orEmpty().lineSequence().mapNotNull { line ->
            val fields = line.split('\t')
            if (fields.size != 3 || !isId(fields[0])) return@mapNotNull null
            val name = cleanName(fields[1], "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Macro.decode(fields[2])?.let { SavedMacro(fields[0], name, it) }
        }.distinctBy { it.id }.toList()
    }
}

/**
 * The editor's changes to a step list. Each returns a new list whose last step has no pause, since
 * nothing follows it. Pauses stay where they are when steps move: the rhythm of the macro is kept,
 * only the order of the presses changes.
 */
object MacroEdits {
    /** The pause put in front of steps that are added after the last one. */
    const val DEFAULT_GAP_MS = 100
    const val TAP_MS = 80
    const val SHORT_MS = 250
    const val LONG_MS = 1000
    /** What one tap on minus or plus changes a time by. */
    const val NUDGE_MS = 50
    val GAP_PRESETS = listOf(0, 50, 100, 250, 500, 1000)

    /** Moves step [from] into slot [slot] (0..size: in front of the step there, or at the end). */
    fun move(steps: List<MacroStep>, from: Int, slot: Int): List<MacroStep> {
        val target = if (slot > from) slot - 1 else slot
        if (target == from) return steps
        val moved = steps.toMutableList().apply { add(target, removeAt(from)) }
        return moved.mapIndexed { index, step -> step.copy(gapMs = steps[index].gapMs) }
    }

    fun delete(steps: List<MacroStep>, index: Int): List<MacroStep> = lastWithoutGap(steps.filterIndexed { i, _ -> i != index })

    /** A copy of step [index] right behind it; the copy takes over the pause that followed the original. */
    fun duplicate(steps: List<MacroStep>, index: Int): List<MacroStep> {
        val step = steps[index]
        val between = if (step.gapMs > 0) step.gapMs else DEFAULT_GAP_MS
        return lastWithoutGap(steps.take(index) + step.copy(gapMs = between) + step + steps.drop(index + 1))
    }

    /** [added] behind [steps], with the default pause in between. */
    fun append(steps: List<MacroStep>, added: List<MacroStep>): List<MacroStep> {
        if (steps.isEmpty()) return lastWithoutGap(added)
        return lastWithoutGap(steps.dropLast(1) + steps.last().copy(gapMs = DEFAULT_GAP_MS) + added)
    }

    fun withInputs(steps: List<MacroStep>, index: Int, inputs: List<MacroInput>): List<MacroStep> =
        steps.mapIndexed { i, step -> if (i == index) step.copy(inputs = inputs) else step }

    fun withHold(steps: List<MacroStep>, index: Int, holdMs: Int): List<MacroStep> =
        steps.mapIndexed { i, step -> if (i == index) step.copy(holdMs = Macro.clampHold(Macro.round(holdMs.toLong()))) else step }

    fun withGap(steps: List<MacroStep>, index: Int, gapMs: Int): List<MacroStep> =
        lastWithoutGap(steps.mapIndexed { i, step -> if (i == index) step.copy(gapMs = Macro.clampGap(Macro.round(gapMs.toLong()))) else step })

    fun withAllGaps(steps: List<MacroStep>, gapMs: Int): List<MacroStep> =
        lastWithoutGap(steps.map { it.copy(gapMs = Macro.clampGap(Macro.round(gapMs.toLong()))) })

    /** Whether [steps] stay within the step count and the length a macro may have. */
    fun fits(steps: List<MacroStep>): Boolean = steps.size <= Macro.MAX_STEPS && Macro(steps).totalMs <= Macro.MAX_TOTAL_MS

    /** The longest start of [steps] that [fits]. */
    fun fit(steps: List<MacroStep>): List<MacroStep> {
        var total = 0L
        val kept = steps.take(Macro.MAX_STEPS).takeWhile { step -> (total + step.holdMs <= Macro.MAX_TOTAL_MS).also { total += step.holdMs + step.gapMs } }
        return if (kept.size == steps.size) steps else lastWithoutGap(kept)
    }

    /**
     * [input] switched in a chord: chosen again it goes; another input on its channel (a second D-pad
     * direction, say) is replaced; and a chord full at [Macro.MAX_INPUTS] takes nothing more.
     */
    fun toggle(inputs: List<MacroInput>, input: MacroInput): List<MacroInput> {
        if (input in inputs) return inputs - input
        val next = inputs.filterNot { it.channel == input.channel } + input
        return if (next.size > Macro.MAX_INPUTS) inputs else next
    }

    /** The slot a step dropped at [x] lands in, given the centres of all steps from left to right. */
    fun slot(centers: List<Float>, x: Float): Int = centers.count { it < x }

    private fun lastWithoutGap(steps: List<MacroStep>): List<MacroStep> =
        if (steps.isEmpty() || steps.last().gapMs == 0) steps else steps.dropLast(1) + steps.last().copy(gapMs = 0)
}

/**
 * Turns the helper's raw recording ("REC" lines, see [PadHelper]) into a [Macro]. Keys count at once;
 * the D-pad, the sticks and the triggers' axes are judged per frame, because the two axes of a
 * diagonal arrive one after the other. A stick counts once deflected past [ENGAGE] and is released
 * inside [NEUTRAL], like the Back + stick shortcuts; sweeping to another direction starts a new press.
 * Presses that overlap, or start within [CHORD_MS] of each other, become one step.
 */
class MacroRecorder(private val flipped: Boolean) {
    private class Interval(val input: MacroInput, val start: Long, val end: Long)
    private class Held(val input: MacroInput, val start: Long)

    private val intervals = mutableListOf<Interval>()
    private val keys = mutableMapOf<MacroButton, Long>()
    private val axes = mutableMapOf<Int, Int>()
    private val triggerKeys = mutableSetOf<MacroButton>()
    private val triggers = mutableMapOf<MacroButton, Long>()
    private var dpad: Held? = null
    private val sticks = mutableMapOf<Stick, Held>()

    /** Whether at least one press has started; a recording without any is not worth keeping. */
    val isEmpty: Boolean get() = intervals.isEmpty() && !holding

    /** Whether any input is held right now. */
    val holding: Boolean get() = keys.isNotEmpty() || triggers.isNotEmpty() || dpad != null || sticks.isNotEmpty()

    /** How long the pad has rested since its last release at [nowMs]: the pause still being recorded. */
    fun restingMs(nowMs: Long): Long? = if (holding || intervals.isEmpty()) null else nowMs - intervals.maxOf { it.end }

    /** One line from the helper, without its "REC " prefix: `<ms> K <code> <0|1>`, `<ms> A <code> <permille>` or `<ms> S`. */
    fun accept(parts: List<String>) {
        val at = parts.getOrNull(0)?.toLongOrNull() ?: return
        when (parts.getOrNull(1)) {
            "S" -> frame(at)
            "K" -> key(at, parts.getOrNull(2)?.toIntOrNull() ?: return, parts.getOrNull(3)?.toIntOrNull() ?: return)
            "A" -> axes[parts.getOrNull(2)?.toIntOrNull() ?: return] = parts.getOrNull(3)?.toIntOrNull() ?: return
        }
    }

    private fun key(at: Long, code: Int, value: Int) {
        val button = MacroButton.ofEvdev(code, flipped) ?: return
        val down = value != 0
        if (button.triggerAxis != null) {
            if (down) triggerKeys += button else triggerKeys -= button
            trigger(at, button)
            return
        }
        if (down && button !in keys) keys[button] = at
        if (!down) keys.remove(button)?.let { intervals += Interval(MacroInput.Button(button), it, at) }
    }

    private fun trigger(at: Long, button: MacroButton) {
        val axis = axes[checkNotNull(button.triggerAxis)] ?: 0
        val wasDown = button in triggers
        val down = button in triggerKeys || axis >= (if (wasDown) RELEASE_PERMILLE else PRESS_PERMILLE)
        if (down && !wasDown) triggers[button] = at
        if (!down && wasDown) intervals += Interval(MacroInput.Button(button), checkNotNull(triggers.remove(button)), at)
    }

    private fun frame(at: Long) {
        MacroButton.entries.filter { it.triggerAxis != null }.forEach { trigger(at, it) }
        val dx = Integer.signum(axes[Evdev.ABS_HAT0X] ?: 0)
        val dy = Integer.signum(axes[Evdev.ABS_HAT0Y] ?: 0)
        val direction = MacroDirection.of(dx, dy)
        if (dpad?.let { (it.input as MacroInput.Dpad).direction } != direction) {
            dpad?.let { intervals += Interval(it.input, it.start, at) }
            dpad = direction?.let { Held(MacroInput.Dpad(it), at) }
        }
        Stick.entries.forEach { stick(at, it) }
    }

    private fun stick(at: Long, stick: Stick) {
        val (xCode, yCode) = Macro.stickAxes(stick)
        // Pads following the Linux convention put the right stick on RX/RY instead of Android's Z/RZ.
        val x = (if (stick == Stick.RIGHT) axes[Evdev.ABS_RX] ?: axes[xCode] else axes[xCode]) ?: 0
        val y = (if (stick == Stick.RIGHT) axes[Evdev.ABS_RY] ?: axes[yCode] else axes[yCode]) ?: 0
        val magnitude = hypot(x.toFloat(), y.toFloat()) / Macro.FULL
        val held = sticks[stick]
        val direction = MacroDirection.ofAngle(x.toFloat(), y.toFloat())
        when {
            held != null && magnitude < NEUTRAL -> { close(held, at); sticks.remove(stick) }
            held != null && magnitude >= ENGAGE && (held.input as MacroInput.Analog).direction != direction -> {
                close(held, at)
                sticks[stick] = Held(MacroInput.Analog(stick, direction), at)
            }
            held == null && magnitude >= ENGAGE -> sticks[stick] = Held(MacroInput.Analog(stick, direction), at)
        }
    }

    private fun close(held: Held, at: Long) {
        // A sweep passes through neighbouring sectors on its way; only directions really held count.
        if (at - held.start >= MIN_STICK_MS) intervals += Interval(held.input, held.start, at)
    }

    /**
     * The macro recorded until [stopMs]; presses still held end there. Null when nothing was pressed.
     * It changes nothing, so the editor also calls it while recording to show the steps so far.
     */
    fun finish(stopMs: Long): Macro? {
        val all = intervals.toMutableList()
        keys.forEach { (button, start) -> all += Interval(MacroInput.Button(button), start, stopMs) }
        triggers.forEach { (button, start) -> all += Interval(MacroInput.Button(button), start, stopMs) }
        dpad?.let { all += Interval(it.input, it.start, stopMs) }
        sticks.values.forEach { all += Interval(it.input, it.start, stopMs) }
        if (all.isEmpty()) return null

        class Group(val start: Long, var end: Long, val members: MutableList<Interval>)
        val groups = mutableListOf<Group>()
        all.sortedBy { it.start }.forEach { interval ->
            val current = groups.lastOrNull()
            if (current != null && (interval.start < current.end || interval.start - current.start <= CHORD_MS)) {
                current.end = maxOf(current.end, interval.end)
                current.members += interval
            } else {
                groups += Group(interval.start, interval.end, mutableListOf(interval))
            }
        }
        val steps = mutableListOf<MacroStep>()
        var total = 0L
        for ((index, group) in groups.withIndex()) {
            // One input per channel: the longest press wins where a sweep left two directions.
            val inputs = group.members.groupBy { it.input.channel }.values
                .map { same -> same.maxBy { it.end - it.start } }
                .sortedBy { it.start }
                .map { it.input }
                .take(Macro.MAX_INPUTS)
            val hold = Macro.clampHold(Macro.round(group.end - group.start))
            val gap = groups.getOrNull(index + 1)?.let { Macro.clampGap(Macro.round(it.start - group.end)) } ?: 0
            if (steps.size == Macro.MAX_STEPS || total + hold > Macro.MAX_TOTAL_MS) break
            steps += MacroStep(inputs, hold, gap)
            total += hold + gap
        }
        val last = steps.last()
        steps[steps.lastIndex] = last.copy(gapMs = 0)
        return Macro(steps)
    }

    companion object {
        const val ENGAGE = 0.6f
        const val NEUTRAL = 0.3f
        const val CHORD_MS = 60L
        const val MIN_STICK_MS = 40L
        private const val PRESS_PERMILLE = 500
        private const val RELEASE_PERMILLE = 300
    }
}

/** The evdev codes of the Thor's pad (linux/input-event-codes.h) and their Android key codes. */
object Evdev {
    const val BTN_A = 0x130
    const val BTN_B = 0x131
    const val BTN_X = 0x133
    const val BTN_Y = 0x134
    const val BTN_TL = 0x136
    const val BTN_TR = 0x137
    const val BTN_TL2 = 0x138
    const val BTN_TR2 = 0x139
    const val BTN_SELECT = 0x13a
    const val BTN_START = 0x13b
    const val BTN_THUMBL = 0x13d
    const val BTN_THUMBR = 0x13e
    const val ABS_X = 0x00
    const val ABS_Y = 0x01
    const val ABS_Z = 0x02
    const val ABS_RX = 0x03
    const val ABS_RY = 0x04
    const val ABS_RZ = 0x05
    const val ABS_GAS = 0x09
    const val ABS_BRAKE = 0x0a
    const val ABS_HAT0X = 0x10
    const val ABS_HAT0Y = 0x11

    /** The face buttons as the Thor's key layout maps them (Vendor_2020_Product_011x.kl). */
    fun ofKeyCode(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> BTN_A
        KeyEvent.KEYCODE_BUTTON_B -> BTN_B
        KeyEvent.KEYCODE_BUTTON_X -> BTN_X
        else -> BTN_Y
    }
}
