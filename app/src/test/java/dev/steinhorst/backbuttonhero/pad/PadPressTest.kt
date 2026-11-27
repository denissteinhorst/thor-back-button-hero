package dev.steinhorst.backbuttonhero.pad

import android.view.KeyEvent
import dev.steinhorst.backbuttonhero.input.BackGestureButton
import dev.steinhorst.backbuttonhero.input.GestureClock
import dev.steinhorst.backbuttonhero.input.GestureScheduler
import dev.steinhorst.backbuttonhero.input.HomeLayerButton
import dev.steinhorst.backbuttonhero.input.ShoulderButton
import dev.steinhorst.backbuttonhero.macro.Evdev
import dev.steinhorst.backbuttonhero.macro.MacroEvent
import dev.steinhorst.backbuttonhero.prefs.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PadPressTest {
    private class Clock : GestureClock {
        var now = 10_000L
        override fun uptimeMillis() = now
    }

    private class Scheduler : GestureScheduler {
        val pending = linkedMapOf<Runnable, Long>()
        override fun postDelayed(runnable: Runnable, delayMs: Long) { pending[runnable] = delayMs }
        override fun removeCallbacks(runnable: Runnable) { pending.remove(runnable) }
        override fun hasCallbacks(runnable: Runnable) = pending.containsKey(runnable)
        fun runAll() = pending.keys.toList().also { pending.clear() }.forEach(Runnable::run)
    }

    private class FakeHost : PadPress.Host {
        var flipped = false
        var helper = true
        val calls = mutableListOf<String>()
        override fun flipped() = flipped
        override fun send(events: List<MacroEvent>): Boolean {
            if (!helper) return false
            calls += events.joinToString(" ") { "${if (it.key) "K" else "A"}${it.code}=${it.value}" }
            return true
        }
        override fun releaseEverything() { calls += "unpress" }
        override fun unavailable() { calls += "unavailable" }
    }

    private val clock = Clock()
    private val scheduler = Scheduler()
    private val host = FakeHost()
    private val press = PadPress(scheduler, clock, host)
    private val l1 = ShoulderButton.L1
    private val r1 = ShoulderButton.R1
    private val a = KeyEvent.KEYCODE_BUTTON_A
    private val down = KeyEvent.ACTION_DOWN
    private val up = KeyEvent.ACTION_UP

    private fun wire(button: PadButton, flipped: Boolean, down: Boolean) =
        button.events(flipped, down).joinToString(" ") { "${if (it.key) "K" else "A"}${it.code}=${it.value}" }

    @Test
    fun `every button has its stored action, and the actions are exactly Prefs' pad actions`() {
        assertEquals(Prefs.PAD_ACTIONS, PadButton.entries.map { it.action }.toSet())
        PadButton.entries.forEach { assertEquals(it, PadButton.of(it.action)) }
        assertNull(PadButton.of(Prefs.SHOULDER_MACRO))
    }

    @Test
    fun `face buttons press their printed label through the controller style, like a macro`() {
        assertEquals("K${Evdev.BTN_A}=1", wire(PadButton.A, flipped = false, down = true))
        assertEquals("K${Evdev.BTN_B}=1", wire(PadButton.A, flipped = true, down = true))
        assertEquals("K${Evdev.BTN_Y}=1", wire(PadButton.X, flipped = true, down = true))
        assertEquals("K${Evdev.BTN_X}=0", wire(PadButton.Y, flipped = true, down = false))
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, PadButton.A.keyCode(flipped = false))
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, PadButton.A.keyCode(flipped = true))
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, PadButton.Y.keyCode(flipped = true))
    }

    @Test
    fun `triggers move their axis, Home is the house key, the D-pad is the hat`() {
        assertEquals("K${Evdev.BTN_TL2}=1 A${Evdev.ABS_BRAKE}=1000", wire(PadButton.L2, false, true))
        assertEquals("K${Evdev.BTN_TR2}=0 A${Evdev.ABS_GAS}=0", wire(PadButton.R2, false, false))
        assertEquals("K${Evdev.BTN_TL}=1", wire(PadButton.L1, true, true))
        assertEquals("K${Evdev.BTN_THUMBR}=1", wire(PadButton.R3, false, true))
        assertEquals("K${Evdev.BTN_SELECT}=1", wire(PadButton.SELECT, false, true))
        assertEquals("K102=1", wire(PadButton.HOME, false, true))
        assertEquals("A${Evdev.ABS_HAT0Y}=-1000", wire(PadButton.UP, false, true))
        assertEquals("A${Evdev.ABS_HAT0Y}=1000", wire(PadButton.DOWN, false, true))
        assertEquals("A${Evdev.ABS_HAT0X}=-1000", wire(PadButton.LEFT, false, true))
        assertEquals("A${Evdev.ABS_HAT0X}=0", wire(PadButton.RIGHT, false, false))
        assertEquals(KeyEvent.KEYCODE_BUTTON_L2, PadButton.L2.keyCode(false))
        assertEquals(KeyEvent.KEYCODE_BUTTON_THUMBL, PadButton.L3.keyCode(false))
        assertEquals(KeyEvent.KEYCODE_BUTTON_START, PadButton.START.keyCode(false))
        assertEquals(KeyEvent.KEYCODE_HOME, PadButton.HOME.keyCode(false))
        assertNull(PadButton.UP.keyCode(false))
    }

    @Test
    fun `a held press lasts until its trigger is released`() {
        press.press(l1, PadButton.A, hold = true)
        assertTrue(press.active)
        assertEquals(listOf("K${Evdev.BTN_A}=1"), host.calls)
        assertTrue(scheduler.pending.isEmpty())
        press.release(r1)
        assertEquals(1, host.calls.size)
        press.release(l1)
        assertFalse(press.active)
        assertEquals(listOf("K${Evdev.BTN_A}=1", "K${Evdev.BTN_A}=0"), host.calls)
        press.release(l1)
        assertEquals(2, host.calls.size)
    }

    @Test
    fun `the Home layer's trigger is the same control and two triggers share one press`() {
        press.press(HomeLayerButton.of(l1), PadButton.B, hold = true)
        press.press(r1, PadButton.B, hold = true)
        assertEquals(listOf("K${Evdev.BTN_B}=1"), host.calls)
        press.release(l1)
        assertEquals(1, host.calls.size)
        press.release(r1)
        assertEquals(listOf("K${Evdev.BTN_B}=1", "K${Evdev.BTN_B}=0"), host.calls)
    }

    @Test
    fun `a trigger pressed again lets go of what it held first`() {
        press.press(l1, PadButton.A, hold = true)
        press.press(l1, PadButton.UP, hold = true)
        assertEquals(listOf("K${Evdev.BTN_A}=1", "K${Evdev.BTN_A}=0", "A${Evdev.ABS_HAT0Y}=-1000"), host.calls)
    }

    @Test
    fun `a trigger without a physical hold taps the button`() {
        press.press(BackGestureButton.SINGLE, PadButton.START, hold = false)
        assertEquals(listOf(PadPress.TAP_MS), scheduler.pending.values.toList())
        scheduler.runAll()
        assertEquals(listOf("K${Evdev.BTN_START}=1", "K${Evdev.BTN_START}=0"), host.calls)
        // A second tap before the first ended releases the first at once and starts its own.
        press.press(BackGestureButton.SINGLE, PadButton.START, hold = false)
        press.press(BackGestureButton.SINGLE, PadButton.START, hold = false)
        assertEquals(1, scheduler.pending.size)
        assertEquals(5, host.calls.size)
    }

    @Test
    fun `the release keeps the code of the style the press began in`() {
        press.press(l1, PadButton.A, hold = true)
        host.flipped = true
        press.release(l1)
        assertEquals(listOf("K${Evdev.BTN_A}=1", "K${Evdev.BTN_A}=0"), host.calls)
    }

    @Test
    fun `without the helper the action is unavailable and nothing is held`() {
        host.helper = false
        press.press(l1, PadButton.A, hold = true)
        assertEquals(listOf("unavailable"), host.calls)
        assertFalse(press.active)
        host.helper = true
        press.release(l1)
        assertEquals(1, host.calls.size)
        assertFalse(press.passes(a, down, fromPad = true, tracked = false))
    }

    @Test
    fun `release all lets the helper drop everything, cancels taps and is quiet when nothing is held`() {
        press.releaseAll()
        assertTrue(host.calls.isEmpty())
        press.press(l1, PadButton.A, hold = true)
        press.press(BackGestureButton.DOUBLE, PadButton.X, hold = false)
        press.releaseAll()
        assertTrue(scheduler.pending.isEmpty())
        assertFalse(press.active)
        assertEquals("unpress", host.calls.last())
        press.release(l1)
        assertEquals("unpress", host.calls.last())
        // The returning releases still pass to the game.
        assertTrue(press.passes(a, up, fromPad = true, tracked = false))
        assertFalse(press.passes(a, down, fromPad = true, tracked = false))
    }

    @Test
    fun `the pressed key passes to the game while held and until its release came back`() {
        press.press(l1, PadButton.A, hold = true)
        assertTrue(press.passes(a, down, fromPad = true, tracked = false))
        assertTrue(press.passes(a, KeyEvent.ACTION_DOWN, fromPad = true, tracked = false))
        assertFalse(press.passes(KeyEvent.KEYCODE_BUTTON_B, down, fromPad = true, tracked = false))
        // AYN's own Home button and a physical press a router owns keep their paths.
        assertFalse(press.passes(a, down, fromPad = false, tracked = false))
        assertFalse(press.passes(a, up, fromPad = true, tracked = true))
        press.release(l1)
        clock.now += 50
        assertTrue(press.passes(a, down, fromPad = true, tracked = false))
        assertTrue(press.passes(a, up, fromPad = true, tracked = false))
        assertFalse(press.passes(a, up, fromPad = true, tracked = false))
    }

    @Test
    fun `a release that never comes back stops counting after a while`() {
        press.press(l1, PadButton.A, hold = true)
        press.release(l1)
        clock.now += PadPress.GUARD_MS + 1
        assertFalse(press.passes(a, up, fromPad = true, tracked = false))
        clock.now -= PadPress.GUARD_MS
        assertFalse(press.passes(a, up, fromPad = true, tracked = false))
    }

    @Test
    fun `the D-pad needs no guard, and a lost helper forgets everything`() {
        press.press(l1, PadButton.LEFT, hold = true)
        press.press(r1, PadButton.A, hold = false)
        press.forget()
        assertFalse(press.active)
        assertTrue(scheduler.pending.isEmpty())
        assertFalse(press.passes(a, down, fromPad = true, tracked = false))
        press.release(l1)
        assertEquals(2, host.calls.size)
    }
}
