package dev.steinhorst.backbuttonhero.macro

import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.Stick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroTest {
    private val a = MacroInput.Button(MacroButton.A)
    private val b = MacroInput.Button(MacroButton.B)

    @Test
    fun `directions come from the grid, the wire and the angle of a deflection`() {
        assertEquals(MacroDirection.UP_LEFT, MacroDirection.of(-1, -1))
        assertNull(MacroDirection.of(0, 0))
        assertEquals(MacroDirection.DOWN_RIGHT, MacroDirection.fromWire("DR"))
        assertNull(MacroDirection.fromWire("X"))
        val expected = mapOf(
            (1f to 0f) to MacroDirection.RIGHT, (1f to 1f) to MacroDirection.DOWN_RIGHT, (0f to 1f) to MacroDirection.DOWN,
            (-1f to 1f) to MacroDirection.DOWN_LEFT, (-1f to 0f) to MacroDirection.LEFT, (-1f to -1f) to MacroDirection.UP_LEFT,
            (0f to -1f) to MacroDirection.UP, (1f to -1f) to MacroDirection.UP_RIGHT, (1f to -0.3f) to MacroDirection.RIGHT,
        )
        expected.forEach { (xy, direction) -> assertEquals(direction, MacroDirection.ofAngle(xy.first, xy.second)) }
    }

    @Test
    fun `face buttons follow the controller style, the others keep their codes`() {
        assertEquals(Evdev.BTN_A, MacroButton.A.evdev(flipped = false))
        assertEquals(Evdev.BTN_B, MacroButton.A.evdev(flipped = true))
        assertEquals(Evdev.BTN_Y, MacroButton.X.evdev(flipped = true))
        assertEquals(Evdev.BTN_X, MacroButton.Y.evdev(flipped = true))
        assertEquals(Evdev.BTN_TL2, MacroButton.L2.evdev(flipped = true))
        assertEquals(MacroButton.B, MacroButton.ofEvdev(Evdev.BTN_A, flipped = true))
        assertEquals(MacroButton.START, MacroButton.ofEvdev(Evdev.BTN_START, flipped = false))
        assertNull(MacroButton.ofEvdev(0x9e, flipped = false))
        assertEquals(MacroButton.SELECT, MacroButton.fromWire("Select"))
        assertNull(MacroButton.fromWire("Home"))
    }

    @Test
    fun `inputs read back from their wire form and share channels where they must`() {
        listOf(a, MacroInput.Dpad(MacroDirection.UP), MacroInput.Analog(Stick.LEFT, MacroDirection.UP_RIGHT),
            MacroInput.Analog(Stick.RIGHT, MacroDirection.LEFT)).forEach { assertEquals(it, MacroInput.fromWire(it.wire)) }
        listOf("Q", "D:Q", "XS:U", "D:U:R").forEach { assertNull(it, MacroInput.fromWire(it)) }
        assertEquals("D", MacroInput.Dpad(MacroDirection.UP).channel)
        assertEquals(MacroInput.Dpad(MacroDirection.UP).channel, MacroInput.Dpad(MacroDirection.LEFT).channel)
        assertEquals("RS", MacroInput.Analog(Stick.RIGHT, MacroDirection.UP).channel)
        assertEquals("A", a.channel)
    }

    @Test
    fun `a macro survives its stored form`() {
        val macro = Macro(listOf(
            MacroStep(listOf(a, MacroInput.Analog(Stick.LEFT, MacroDirection.RIGHT)), 500, 120),
            MacroStep(listOf(MacroInput.Dpad(MacroDirection.DOWN_LEFT)), 20, 0),
        ))
        assertEquals("1:A+LS:R;500;120|D:DL;20;0", macro.encode())
        assertEquals(macro, Macro.decode(macro.encode()))
        assertEquals(640L, macro.totalMs)
    }

    @Test
    fun `damaged or foreign macros are refused as a whole`() {
        val tooMany = "1:" + List(Macro.MAX_STEPS + 1) { "A;20;0" }.joinToString("|")
        val tooManyInputs = "1:" + List(Macro.MAX_INPUTS + 1) { MacroButton.entries[it].wire }.joinToString("+") + ";20;0"
        val tooLong = "1:" + List(7) { "A;10000;0" }.joinToString("|")
        listOf(null, "", "2:A;20;0", "1:", tooMany, "1:A;20", tooManyInputs, "1:Q;20;0", "1:D:U+D:L;20;0",
            "1:A;10;0", "1:A;20;-1", "1:A;x;0", "1:A;20;10001", tooLong).forEach { assertNull(it, Macro.decode(it)) }
    }

    @Test
    fun `playback presses, holds and releases every input in order`() {
        val macro = Macro(listOf(
            MacroStep(listOf(MacroInput.Button(MacroButton.R2), MacroInput.Dpad(MacroDirection.UP_RIGHT)), 100, 50),
            MacroStep(listOf(MacroInput.Analog(Stick.RIGHT, MacroDirection.DOWN_LEFT), MacroInput.Analog(Stick.LEFT, MacroDirection.UP)), 30, 0),
        ))
        val events = macro.events(flipped = false).map { it.wire() }
        assertEquals(listOf(
            "MEV 0 K ${Evdev.BTN_TR2} 1", "MEV 0 A ${Evdev.ABS_GAS} 1000", "MEV 0 A ${Evdev.ABS_HAT0X} 1000", "MEV 0 A ${Evdev.ABS_HAT0Y} -1000",
            "MEV 100 K ${Evdev.BTN_TR2} 0", "MEV 100 A ${Evdev.ABS_GAS} 0", "MEV 100 A ${Evdev.ABS_HAT0X} 0", "MEV 100 A ${Evdev.ABS_HAT0Y} 0",
            "MEV 150 A ${Evdev.ABS_X} 0", "MEV 150 A ${Evdev.ABS_Z} -707", "MEV 150 A ${Evdev.ABS_RZ} 707", "MEV 150 A ${Evdev.ABS_Y} -1000",
            "MEV 180 A ${Evdev.ABS_Z} 0", "MEV 180 A ${Evdev.ABS_RZ} 0", "MEV 180 A ${Evdev.ABS_X} 0", "MEV 180 A ${Evdev.ABS_Y} 0",
        ), events)
    }

    @Test
    fun `an input pressed again right away gets a pause and releases come first`() {
        val macro = Macro(listOf(MacroStep(listOf(a), 40, 0), MacroStep(listOf(a), 40, 0), MacroStep(listOf(b), 40, 0)))
        val events = macro.events(flipped = true)
        assertEquals(listOf(0L, 40L, 60L, 100L, 100L, 140L), events.map { it.atMs })
        assertEquals(listOf(1, 0, 1, 0, 1, 0), events.map { it.value })
        assertEquals(Evdev.BTN_A, events[4].code)
        assertTrue(events.all { it.key })
    }

    @Test
    fun `times round to ten and stay in range`() {
        assertEquals(130, Macro.round(126))
        assertEquals(120, Macro.round(124))
        assertEquals(Macro.MIN_HOLD_MS, Macro.clampHold(3))
        assertEquals(Macro.MAX_HOLD_MS, Macro.clampHold(99_999))
        assertEquals(0, Macro.clampGap(-5))
        assertEquals(Macro.MAX_GAP_MS, Macro.clampGap(20_000))
        assertEquals(Evdev.ABS_X to Evdev.ABS_Y, Macro.stickAxes(Stick.LEFT))
    }

    @Test
    fun `face buttons map the key layout's codes`() {
        assertEquals(Evdev.BTN_A, Evdev.ofKeyCode(android.view.KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(Evdev.BTN_B, Evdev.ofKeyCode(android.view.KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(Evdev.BTN_X, Evdev.ofKeyCode(android.view.KeyEvent.KEYCODE_BUTTON_X))
        assertEquals(Evdev.BTN_Y, Evdev.ofKeyCode(android.view.KeyEvent.KEYCODE_BUTTON_Y))
    }

    private fun MacroRecorder.line(text: String) = accept(text.split(' '))
    private fun MacroRecorder.key(at: Long, code: Int, value: Int) { line("$at K $code $value"); line("$at S") }
    private fun MacroRecorder.axes(at: Long, vararg values: Pair<Int, Int>) {
        values.forEach { (code, value) -> line("$at A $code $value") }
        line("$at S")
    }

    @Test
    fun `taps become steps with the pauses in between`() {
        val r = MacroRecorder(flipped = false)
        assertTrue(r.isEmpty)
        assertNull(r.finish(100))
        r.key(1000, Evdev.BTN_A, 1)
        assertFalse(r.isEmpty)
        r.key(1000, Evdev.BTN_A, 1) // a repeated down keeps the first start
        r.key(1084, Evdev.BTN_A, 0)
        r.key(1300, Evdev.BTN_B, 1)
        r.key(1305, Evdev.BTN_B, 0) // shorter than the shortest hold
        r.key(1400, 0x9e, 1) // Back is never recorded
        r.key(1500, Evdev.BTN_X, 0) // a release without a press
        assertEquals(Macro(listOf(MacroStep(listOf(a), 80, 220), MacroStep(listOf(b), 20, 0))), r.finish(2000))
    }

    @Test
    fun `a recording in progress tells what is held and how long the pad has rested`() {
        val r = MacroRecorder(flipped = false)
        assertNull(r.restingMs(500))
        r.key(1000, Evdev.BTN_A, 1)
        assertTrue(r.holding)
        assertNull(r.restingMs(1200))
        // The editor shows the press so far while it is still held, and that changes nothing.
        assertEquals(200, checkNotNull(r.finish(1200)).steps.single().holdMs)
        r.key(1300, Evdev.BTN_A, 0)
        assertFalse(r.holding)
        assertEquals(450L, r.restingMs(1750))
        assertEquals(300, checkNotNull(r.finish(1750)).steps.single().holdMs)
    }

    @Test
    fun `the library keeps names clean and survives its stored form`() {
        val tap = Macro(listOf(MacroStep(listOf(a), 80, 0)))
        assertTrue(SavedMacro.isId("m12"))
        assertFalse(SavedMacro.isId("m0"))
        assertFalse(SavedMacro.isId(null))
        assertEquals("ab", SavedMacro.cleanName(" a\tb\n ", "x"))
        assertEquals("x", SavedMacro.cleanName(null, "x"))
        assertEquals(SavedMacro.MAX_NAME, SavedMacro.cleanName("y".repeat(99), "x").length)
        assertEquals("m1", SavedMacro.nextId(emptyList()))
        val library = listOf(SavedMacro("m1", "One", tap), SavedMacro("m4", "Four", tap))
        assertEquals("m5", SavedMacro.nextId(library))
        assertEquals(library, SavedMacro.decode(SavedMacro.encode(library)))
        val damaged = SavedMacro.encode(library) + "\nm1\tAgain\t${tap.encode()}\nm2\t\t${tap.encode()}\nx\tBad\t${tap.encode()}\nm3\tBad\t1:Home;20;0\nm5\tShort"
        assertEquals(library, SavedMacro.decode(damaged))
        assertTrue(SavedMacro.decode(null).isEmpty())
    }

    @Test
    fun `editor changes keep pauses in place and the last step without one`() {
        fun step(button: MacroButton, hold: Int, gap: Int) = MacroStep(listOf(MacroInput.Button(button)), hold, gap)
        val steps = listOf(step(MacroButton.A, 80, 100), step(MacroButton.B, 90, 200), step(MacroButton.X, 70, 0))
        assertEquals(listOf(step(MacroButton.B, 90, 100), step(MacroButton.X, 70, 200), step(MacroButton.A, 80, 0)), MacroEdits.move(steps, 0, 3))
        assertEquals(steps, MacroEdits.move(steps, 1, 2))
        assertEquals(listOf(step(MacroButton.A, 80, 100), step(MacroButton.B, 90, 0)), MacroEdits.delete(steps, 2))
        assertEquals(listOf(step(MacroButton.A, 80, 100), step(MacroButton.B, 90, 200), step(MacroButton.X, 70, MacroEdits.DEFAULT_GAP_MS), step(MacroButton.X, 70, 0)), MacroEdits.duplicate(steps, 2))
        assertEquals(steps.take(1).map { it.copy(gapMs = 0) }, MacroEdits.append(emptyList(), steps.take(1)))
        assertEquals(MacroEdits.DEFAULT_GAP_MS, MacroEdits.append(steps, steps.take(1))[2].gapMs)
        assertEquals(listOf(250, 250, 0), MacroEdits.withAllGaps(steps, 247).map { it.gapMs })
        assertEquals(listOf(100, 0, 0), MacroEdits.withGap(steps, 1, -5).map { it.gapMs })
        assertEquals(Macro.MAX_HOLD_MS, MacroEdits.withHold(steps, 0, 99_999)[0].holdMs)
        assertEquals(listOf(MacroInput.Button(MacroButton.Y)), MacroEdits.withInputs(steps, 1, listOf(MacroInput.Button(MacroButton.Y)))[1].inputs)
        assertEquals(2, MacroEdits.slot(listOf(10f, 20f, 30f), 25f))
    }

    @Test
    fun `editor changes respect the limits and chords keep one input per channel`() {
        val long = List(7) { MacroStep(listOf(a), 10_000, 0) }
        assertFalse(MacroEdits.fits(long))
        assertEquals(6, MacroEdits.fit(long).size)
        assertEquals(0, MacroEdits.fit(List(4) { MacroStep(listOf(a), 9_000, 9_000) }).last().gapMs)
        val ok = listOf(MacroStep(listOf(a), 80, 0))
        assertTrue(MacroEdits.fits(ok))
        assertEquals(ok, MacroEdits.fit(ok))
        assertFalse(MacroEdits.fits(List(Macro.MAX_STEPS + 1) { MacroStep(listOf(a), 20, 0) }))
        val up = MacroInput.Dpad(MacroDirection.UP)
        val down = MacroInput.Dpad(MacroDirection.DOWN)
        assertEquals(listOf(a, down), MacroEdits.toggle(listOf(a, up), down))
        assertEquals(listOf(up), MacroEdits.toggle(listOf(a, up), a))
        val full = MacroButton.entries.take(Macro.MAX_INPUTS).map { MacroInput.Button(it) }
        assertEquals(full, MacroEdits.toggle(full, up))
    }

    @Test
    fun `garbage lines are ignored`() {
        val r = MacroRecorder(flipped = false)
        listOf("", "x K 1 1", "5", "5 Q", "5 K", "5 K 1", "5 A", "5 A 1").forEach { r.line(it) }
        assertTrue(r.isEmpty)
    }

    @Test
    fun `the printed button is recorded under the flipped layout`() {
        val r = MacroRecorder(flipped = true)
        r.key(0, Evdev.BTN_A, 1)
        r.key(50, Evdev.BTN_A, 0)
        assertEquals(listOf(b), r.finish(100)?.steps?.single()?.inputs)
    }

    @Test
    fun `triggers count by key or by axis with some hysteresis`() {
        val r = MacroRecorder(flipped = false)
        r.key(0, Evdev.BTN_TL2, 1)
        r.key(100, Evdev.BTN_TL2, 0)
        r.axes(1000, Evdev.ABS_GAS to 600)
        r.axes(1100, Evdev.ABS_GAS to 400)
        r.axes(1200, Evdev.ABS_GAS to 200)
        r.axes(2000, Evdev.ABS_GAS to 450) // never pressed far enough
        r.axes(3000, Evdev.ABS_GAS to 900) // still held at stop
        val steps = checkNotNull(r.finish(3500)).steps
        assertEquals(listOf(MacroButton.L2, MacroButton.R2, MacroButton.R2), steps.map { (it.inputs.single() as MacroInput.Button).button })
        assertEquals(listOf(100, 200, 500), steps.map { it.holdMs })
        assertEquals(listOf(900, 1800, 0), steps.map { it.gapMs })
    }

    @Test
    fun `the D-pad records each direction it is held in`() {
        val r = MacroRecorder(flipped = false)
        r.axes(0, Evdev.ABS_HAT0X to 1000)
        r.axes(200, Evdev.ABS_HAT0Y to -1000)
        r.axes(400, Evdev.ABS_HAT0X to 0, Evdev.ABS_HAT0Y to 0)
        r.axes(1000, Evdev.ABS_HAT0Y to 1000)
        val steps = checkNotNull(r.finish(1100)).steps
        assertEquals(
            listOf(MacroDirection.RIGHT, MacroDirection.UP_RIGHT, MacroDirection.DOWN),
            steps.map { (it.inputs.single() as MacroInput.Dpad).direction },
        )
        assertEquals(listOf(200, 200, 100), steps.map { it.holdMs })
        assertEquals(listOf(0, 600, 0), steps.map { it.gapMs })
    }

    @Test
    fun `sticks record directions they are really held in`() {
        val r = MacroRecorder(flipped = false)
        r.axes(0, Evdev.ABS_X to 900)
        r.axes(10, Evdev.ABS_Y to 900) // passing the diagonal for a moment
        r.axes(20, Evdev.ABS_X to 0)
        r.axes(300, Evdev.ABS_X to 500, Evdev.ABS_Y to 0) // still deflected, not yet a new direction
        r.axes(400, Evdev.ABS_X to 100)
        r.axes(1000, Evdev.ABS_Z to -800)
        r.axes(1500, Evdev.ABS_Z to 0)
        r.axes(2000, Evdev.ABS_RX to 0, Evdev.ABS_RY to -800)
        val steps = checkNotNull(r.finish(2600)).steps
        assertEquals(
            listOf(MacroInput.Analog(Stick.LEFT, MacroDirection.DOWN), MacroInput.Analog(Stick.RIGHT, MacroDirection.LEFT),
                MacroInput.Analog(Stick.RIGHT, MacroDirection.UP)),
            steps.map { it.inputs.single() },
        )
        assertEquals(listOf(380, 500, 600), steps.map { it.holdMs })
    }

    @Test
    fun `presses that overlap or start together are one step`() {
        val r = MacroRecorder(flipped = false)
        r.axes(0, Evdev.ABS_X to 1000)
        r.key(500, Evdev.BTN_A, 1)
        r.key(600, Evdev.BTN_A, 0)
        r.axes(1000, Evdev.ABS_X to 0)
        r.key(2000, Evdev.BTN_B, 1)
        r.key(2040, Evdev.BTN_TR, 1)
        r.key(2100, Evdev.BTN_B, 0)
        r.key(2100, Evdev.BTN_TR, 0)
        val steps = checkNotNull(r.finish(3000)).steps
        assertEquals(listOf(MacroInput.Analog(Stick.LEFT, MacroDirection.RIGHT), a), steps[0].inputs)
        assertEquals(1000, steps[0].holdMs)
        assertEquals(1000, steps[0].gapMs)
        assertEquals(listOf(b, MacroInput.Button(MacroButton.R1)), steps[1].inputs)
    }

    @Test
    fun `presses still held at stop end there`() {
        val r = MacroRecorder(flipped = false)
        r.key(0, Evdev.BTN_A, 1)
        r.key(0, Evdev.BTN_TR2, 1)
        r.axes(0, Evdev.ABS_HAT0X to -1000, Evdev.ABS_Y to 1000)
        val step = checkNotNull(r.finish(250)).steps.single()
        assertEquals(setOf(a, MacroInput.Button(MacroButton.R2), MacroInput.Dpad(MacroDirection.LEFT), MacroInput.Analog(Stick.LEFT, MacroDirection.DOWN)), step.inputs.toSet())
        assertEquals(250, step.holdMs)
    }

    @Test
    fun `a chord keeps at most eight inputs`() {
        val r = MacroRecorder(flipped = false)
        val codes = listOf(Evdev.BTN_A, Evdev.BTN_B, Evdev.BTN_X, Evdev.BTN_Y, Evdev.BTN_TL, Evdev.BTN_TR, Evdev.BTN_TL2,
            Evdev.BTN_TR2, Evdev.BTN_THUMBL)
        codes.forEachIndexed { i, code -> r.key(i.toLong(), code, 1) }
        assertEquals(Macro.MAX_INPUTS, checkNotNull(r.finish(100)).steps.single().inputs.size)
    }

    @Test
    fun `recordings stop at the step and length limits`() {
        val many = MacroRecorder(flipped = false)
        repeat(Macro.MAX_STEPS + 5) { i ->
            many.key(i * 100L, Evdev.BTN_A, 1)
            many.key(i * 100L + 30, Evdev.BTN_A, 0)
        }
        val steps = checkNotNull(many.finish(100_000)).steps
        assertEquals(Macro.MAX_STEPS, steps.size)
        assertEquals(0, steps.last().gapMs)

        val long = MacroRecorder(flipped = false)
        repeat(8) { i ->
            long.key(i * 10_000L, Evdev.BTN_A, 1)
            long.key(i * 10_000L + 9_000, Evdev.BTN_A, 0)
        }
        val kept = checkNotNull(long.finish(100_000))
        assertEquals(6, kept.steps.size)
        assertTrue(kept.totalMs <= Macro.MAX_TOTAL_MS)
        assertEquals(kept, Macro.decode(kept.encode()))
    }
}
