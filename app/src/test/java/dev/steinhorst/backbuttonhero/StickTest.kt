package dev.steinhorst.backbuttonhero

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class StickDetectorTest {
    private val detector = StickDetector()

    private fun pressed(direction: DpadDirection) = StickDetector.Edge.Pressed(direction)

    @Test
    fun `a stick inside the engage threshold fires nothing`() {
        assertNull(detector.update(0f, 0f))
        assertNull(detector.update(0.59f, 0f))
        assertNull(detector.update(0f, -0.59f))
    }

    @Test
    fun `each axis direction fires once at the engage threshold - up is negative y`() {
        assertEquals(pressed(DpadDirection.LEFT), detector.update(-0.6f, 0f))
        detector.update(0f, 0f)
        assertEquals(pressed(DpadDirection.RIGHT), detector.update(1f, 0f))
        detector.update(0f, 0f)
        assertEquals(pressed(DpadDirection.UP), detector.update(0f, -1f))
        detector.update(0f, 0f)
        assertEquals(pressed(DpadDirection.DOWN), detector.update(0f, 0.8f))
    }

    @Test
    fun `a diagonal picks the dominant axis`() {
        assertEquals(pressed(DpadDirection.RIGHT), detector.update(0.9f, -0.7f))
        detector.update(0f, 0f)
        assertEquals(pressed(DpadDirection.UP), detector.update(0.7f, -0.9f))
    }

    @Test
    fun `a held direction fires once however many frames arrive`() {
        assertEquals(pressed(DpadDirection.UP), detector.update(0f, -0.7f))
        repeat(20) { assertNull(detector.update(0f, -1f)) }
    }

    @Test
    fun `another direction needs the stick to pass neutral first`() {
        assertEquals(pressed(DpadDirection.UP), detector.update(0f, -1f))
        // Swept straight across without settling inside the neutral zone: still the same deflection.
        assertNull(detector.update(1f, 0f))
        assertNull(detector.update(0.4f, 0f))
        assertEquals(StickDetector.Edge.Released, detector.update(0.29f, 0.29f))
        assertEquals(pressed(DpadDirection.RIGHT), detector.update(1f, 0f))
    }

    @Test
    fun `neutral needs both axes inside the zone`() {
        detector.update(0f, -1f)
        assertNull(detector.update(0.3f, 0f))
        assertNull(detector.update(0f, -0.3f))
        assertEquals(StickDetector.Edge.Released, detector.update(0.29f, -0.29f))
        assertNull(detector.update(0f, 0f))
    }

    @Test
    fun `reset forgets an engaged deflection`() {
        detector.update(0f, -1f)
        detector.reset()
        assertEquals(pressed(DpadDirection.UP), detector.update(0f, -1f))
    }
}

class StickMappingTest {
    @Test
    fun `wire letters map to directions and sticks`() {
        assertEquals(DpadDirection.UP, DpadDirection.fromWire("U"))
        assertEquals(DpadDirection.DOWN, DpadDirection.fromWire("D"))
        assertEquals(DpadDirection.LEFT, DpadDirection.fromWire("L"))
        assertEquals(DpadDirection.RIGHT, DpadDirection.fromWire("R"))
        assertNull(DpadDirection.fromWire("N"))
        assertEquals(Stick.LEFT, Stick.fromWire("L"))
        assertEquals(Stick.RIGHT, Stick.fromWire("R"))
        assertNull(Stick.fromWire("X"))
    }

    @Test
    fun `every stick direction has exactly one button with a unique key`() {
        assertEquals(8, StickButton.entries.size)
        assertEquals(8, StickButton.entries.map { it.prefKey }.toSet().size)
        Stick.entries.forEach { stick ->
            DpadDirection.entries.forEach { direction ->
                val button = StickButton.of(stick, direction)
                assertEquals(stick, button.stick)
                assertEquals(direction, button.direction)
            }
        }
        assertEquals(setOf(1, 2), Stick.entries.map { it.bit }.toSet())
    }

    @Test
    fun `no stick key collides with another control`() {
        val others = (ShoulderButton.entries.map { it.prefKey } + DpadDirection.entries.map { it.prefKey }).toSet()
        assertTrue((StickButton.entries.map { it.prefKey } + StickClickButton.entries.map { it.prefKey }).none { it in others })
        val sticks = StickButton.entries.map { it.prefKey } + StickClickButton.entries.map { it.prefKey }
        assertEquals(sticks.size, sticks.toSet().size)
    }

    @Test
    fun `click buttons cover each stick with press, double and hold`() {
        assertEquals(6, StickClickButton.entries.size)
        Stick.entries.forEach { stick ->
            StickClick.entries.forEach { click ->
                val button = StickClickButton.of(stick, click)
                assertEquals("${stick.prefix}_${click.suffix}", button.prefKey)
                assertEquals("", button.defaultAction)
            }
        }
        assertEquals(android.view.KeyEvent.KEYCODE_BUTTON_THUMBL, Stick.LEFT.keyCode)
        assertEquals(android.view.KeyEvent.KEYCODE_BUTTON_THUMBR, Stick.RIGHT.keyCode)
    }
}

@RunWith(RobolectricTestRunner::class)
class StickPrefsTest {
    private val prefs = Prefs(ApplicationProvider.getApplicationContext())

    @Test
    fun `all eight stick directions start unassigned`() {
        StickButton.entries.forEach { assertEquals("", prefs.shoulderAction(it)) }
        assertFalse(prefs.hasStickAction())
    }

    @Test
    fun `hasStickAction is per stick`() {
        prefs.setShoulderAction(StickButton.RIGHT_DOWN, Prefs.SHOULDER_VOLUME_DOWN)
        assertTrue(prefs.hasStickAction())
        assertTrue(prefs.hasStickAction(Stick.RIGHT))
        assertFalse(prefs.hasStickAction(Stick.LEFT))
    }

    @Test
    fun `click actions are per stick`() {
        prefs.setShoulderAction(StickClickButton.RIGHT_DOUBLE, Prefs.SHOULDER_HOME)
        assertTrue(prefs.hasStickClickAction(Stick.RIGHT))
        assertFalse(prefs.hasStickClickAction(Stick.LEFT))
        // Click gestures need no root helper, so they do not count as an axis action.
        assertFalse(prefs.hasStickAction())
    }

    @Test
    fun `stick plans and writes cover click bindings and timings`() {
        prefs.setShoulderAction(StickClickButton.LEFT_TAP_HOLD, Prefs.SHOULDER_VOLUME_UP)
        prefs.setStickDoubleMs(Stick.LEFT, 450)
        prefs.setStickTapHoldMs(Stick.RIGHT, 1_500)
        val before = prefs.snapshot()
        assertEquals(StickTimings(450, Prefs.DEFAULT_TAP_HOLD_MS), before.stickTimings.getValue("lstick"))
        val unbind = SettingsChangePlan.create(before, SettingsSection.STICKS, SettingsChangeAction.UNBIND)
        assertEquals("", unbind.after.bindings.getValue("lstick_tap_hold").action)
        assertEquals(before.stickTimings, unbind.after.stickTimings)
        val preset = SettingsChangePlan.create(before, SettingsSection.STICKS, SettingsChangeAction.PRESET)
        assertEquals(SettingsState().stickTimings, preset.after.stickTimings)
        assertTrue(prefs.applySettings(before, preset.after))
        assertEquals(Prefs.DEFAULT_DOUBLE_TAP_MS, prefs.stickDoubleMs(Stick.LEFT))
        assertEquals(Prefs.DEFAULT_TAP_HOLD_MS, prefs.stickTapHoldMs(Stick.RIGHT))
        assertFalse(prefs.hasStickClickAction(Stick.LEFT))
    }

    @Test
    fun `stick bindings take part in snapshots, previews and the stick page plans`() {
        prefs.setShoulderAction(StickButton.LEFT_LEFT, Prefs.SHOULDER_VOLUME_DOWN)
        val before = prefs.snapshot()
        assertEquals(Prefs.SHOULDER_VOLUME_DOWN, before.bindings.getValue(StickButton.LEFT_LEFT.prefKey).action)
        val unbind = SettingsChangePlan.create(before, SettingsSection.STICKS, SettingsChangeAction.UNBIND)
        assertEquals("", unbind.after.bindings.getValue(StickButton.LEFT_LEFT.prefKey).action)
        // The stick page never touches the D-pad or the shoulder buttons.
        assertEquals(before.bindings.getValue("dpad_up"), unbind.after.bindings.getValue("dpad_up"))
        assertTrue(prefs.applySettings(before, unbind.after))
        assertFalse(prefs.hasStickAction())
        val everything = SettingsChangePlan.create(prefs.snapshot(), null, SettingsChangeAction.PRESET)
        assertTrue(everything.after.bindings.keys.containsAll(StickButton.entries.map { it.prefKey }))
    }
}

private class TestClock(var now: Long = 10_000L) : GestureClock {
    override fun uptimeMillis() = now
}

private class TestScheduler : GestureScheduler {
    val pending = linkedMapOf<Runnable, Long>()
    override fun postDelayed(runnable: Runnable, delayMs: Long) { pending[runnable] = delayMs }
    override fun removeCallbacks(runnable: Runnable) { pending.remove(runnable) }
    override fun hasCallbacks(runnable: Runnable) = pending.containsKey(runnable)
    /** Runs the oldest pending runnable and returns the delay it had been posted with. */
    fun fire(): Long {
        val (next, delay) = pending.entries.first().toPair()
        pending.remove(next)
        next.run()
        return delay
    }
}

class RepeatCurveTest {
    @Test
    fun `steps start slow, ease in and settle at the fastest pace`() {
        assertEquals(RepeatCurve.SLOWEST_INTERVAL_MS, RepeatCurve.intervalAfter(0))
        assertEquals(RepeatCurve.SLOWEST_INTERVAL_MS, RepeatCurve.intervalAfter(-50))
        assertEquals(RepeatCurve.FASTEST_INTERVAL_MS, RepeatCurve.intervalAfter(RepeatCurve.RAMP_MS))
        assertEquals(RepeatCurve.FASTEST_INTERVAL_MS, RepeatCurve.intervalAfter(RepeatCurve.RAMP_MS * 10))
        val samples = (0..RepeatCurve.RAMP_MS step 100).map(RepeatCurve::intervalAfter)
        assertEquals(samples.sortedDescending(), samples)
        // Smoothstep: the midpoint is exactly halfway.
        assertEquals((RepeatCurve.SLOWEST_INTERVAL_MS + RepeatCurve.FASTEST_INTERVAL_MS) / 2,
            RepeatCurve.intervalAfter(RepeatCurve.RAMP_MS / 2))
    }
}

class LevelRepeaterTest {
    private val scheduler = TestScheduler()
    private val clock = TestClock()

    @Test
    fun `waits the initial delay, then repeats faster and faster while step allows it`() {
        var steps = 0
        val repeater = LevelRepeater(scheduler, clock) { steps++; steps < 12 }
        repeater.start()
        assertTrue(repeater.running)
        assertEquals(RepeatCurve.INITIAL_DELAY_MS, scheduler.fire())
        assertEquals(1, steps)
        val intervals = mutableListOf<Long>()
        while (repeater.running) {
            val delay = scheduler.pending.values.single()
            intervals += delay
            clock.now += delay
            scheduler.fire()
        }
        assertEquals(12, steps)
        assertEquals(RepeatCurve.SLOWEST_INTERVAL_MS, intervals.first())
        assertTrue(intervals.last() < intervals.first())
        assertEquals(intervals.sortedDescending(), intervals)
    }

    @Test
    fun `stop cancels, start never doubles and a restart begins slow again`() {
        var steps = 0
        val repeater = LevelRepeater(scheduler, clock) { steps++; true }
        repeater.start()
        repeater.start()
        assertEquals(1, scheduler.pending.size)
        scheduler.fire()
        clock.now += 5_000
        scheduler.fire()
        assertEquals(RepeatCurve.FASTEST_INTERVAL_MS, scheduler.pending.values.single())
        repeater.stop()
        assertFalse(repeater.running)
        repeater.start()
        clock.now += 100
        scheduler.fire()
        assertEquals(RepeatCurve.SLOWEST_INTERVAL_MS, scheduler.pending.values.single())
    }
}

class StickClickMachineTest {
    private val scheduler = TestScheduler()
    private val clock = TestClock()
    private val fired = mutableListOf<StickClick>()
    private var bound = setOf(StickClick.PRESS, StickClick.DOUBLE, StickClick.TAP_HOLD)
    private val machine = StickClickMachine(scheduler, clock, { 300L }, { 1_000L }, { it in bound }) { fired += it }

    private fun press(): Boolean = machine.down()
    private fun release() = machine.up()
    private fun tap(gapMs: Long = 100) { press(); clock.now += 50; release(); clock.now += gapMs }

    @Test
    fun `nothing assigned leaves the press to the game`() {
        bound = emptySet()
        assertFalse(press())
        release()
        assertTrue(fired.isEmpty())
        assertTrue(scheduler.pending.isEmpty())
    }

    @Test
    fun `a lone press waits the double window when a double press exists`() {
        assertTrue(press())
        release()
        assertTrue(fired.isEmpty())
        assertEquals(300L, scheduler.pending.values.single())
        scheduler.fire()
        assertEquals(listOf(StickClick.PRESS), fired)
    }

    @Test
    fun `without a double press the press fires at once on release`() {
        bound = setOf(StickClick.PRESS)
        press()
        release()
        assertEquals(listOf(StickClick.PRESS), fired)
        assertTrue(scheduler.pending.isEmpty())
    }

    @Test
    fun `two quick presses fire only the double press`() {
        tap()
        press()
        clock.now += 50
        release()
        assertEquals(listOf(StickClick.DOUBLE), fired)
        assertTrue(scheduler.pending.isEmpty())
    }

    @Test
    fun `a second press after the double window is a new tap`() {
        tap(gapMs = 400)
        scheduler.pending.clear()
        press(); release()
        assertTrue(fired.isEmpty())
        scheduler.fire()
        assertEquals(listOf(StickClick.PRESS), fired)
    }

    @Test
    fun `a double press without a press assigned fires nothing alone`() {
        bound = setOf(StickClick.DOUBLE)
        tap(gapMs = 0)
        scheduler.fire()
        assertTrue(fired.isEmpty())
        clock.now += 1_000
        tap(); press(); release()
        assertEquals(listOf(StickClick.DOUBLE), fired)
    }

    @Test
    fun `tap then press and hold fires tap and hold while still held`() {
        tap()
        press()
        assertEquals(1_000L, scheduler.pending.values.single())
        scheduler.fire()
        assertEquals(listOf(StickClick.TAP_HOLD), fired)
        release()
        assertEquals(listOf(StickClick.TAP_HOLD), fired)
        assertTrue(scheduler.pending.isEmpty())
    }

    @Test
    fun `a first press alone is never a hold however long it lasts`() {
        press()
        assertTrue(scheduler.pending.isEmpty())
        clock.now += 5_000
        release()
        scheduler.fire()
        assertEquals(listOf(StickClick.PRESS), fired)
    }

    @Test
    fun `the tap before a hold fires its press at once without a double press`() {
        bound = setOf(StickClick.PRESS, StickClick.TAP_HOLD)
        tap()
        assertEquals(listOf(StickClick.PRESS), fired)
        press()
        scheduler.fire()
        assertEquals(listOf(StickClick.PRESS, StickClick.TAP_HOLD), fired)
    }

    @Test
    fun `with tap and hold assigned the second press may come later than the double window`() {
        bound = setOf(StickClick.TAP_HOLD)
        tap(gapMs = 400)
        press()
        assertEquals(1_000L, scheduler.pending.values.single())
        release()
        assertTrue(fired.isEmpty())
        clock.now += 700
        press()
        assertTrue(scheduler.pending.isEmpty())
    }

    @Test
    fun `releasing before the hold time is just another tap and cancels the hold`() {
        bound = setOf(StickClick.PRESS, StickClick.TAP_HOLD)
        tap()
        press()
        release()
        assertTrue(scheduler.pending.isEmpty())
        assertEquals(listOf(StickClick.PRESS, StickClick.PRESS), fired)
    }

    @Test
    fun `an unmatched release is ignored and reset drops everything pending`() {
        release()
        assertTrue(fired.isEmpty())
        tap()
        press()
        machine.reset()
        assertTrue(scheduler.pending.isEmpty())
        release()
        assertTrue(fired.isEmpty())
        press()
        assertTrue(scheduler.pending.isEmpty())
    }
}
