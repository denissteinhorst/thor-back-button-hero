package dev.steinhorst.backbuttonhero.input

import android.view.KeyEvent
import dev.steinhorst.backbuttonhero.prefs.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickClickRouterTest {
    private class Clock : GestureClock {
        var now = 10_000L
        override fun uptimeMillis() = now
    }

    private class Scheduler : GestureScheduler {
        val pending = linkedMapOf<Runnable, Long>()
        override fun postDelayed(runnable: Runnable, delayMs: Long) { pending[runnable] = delayMs }
        override fun removeCallbacks(runnable: Runnable) { pending.remove(runnable) }
        override fun hasCallbacks(runnable: Runnable) = pending.containsKey(runnable)
    }

    private class FakeHost : StickClickRouter.Host {
        override var interceptionEnabled = true
        override var modifierDown = true
        override var homeDown = false
        override var repeatingButton: ComboButton? = null
        var allowed = true
        val actions = mutableMapOf<ComboButton, String>()
        val calls = mutableListOf<String>()
        val timings = mutableListOf<String>()
        override fun allows(button: ComboButton) = allowed
        override fun markComboFired() { calls += "fired" }
        override fun stopLevelRepeat() { calls += "stop" }
        override fun doubleMs(stick: Stick, home: Boolean) = 300L.also { timings += "double $stick $home" }
        override fun tapHoldMs(stick: Stick, home: Boolean) = 400L.also { timings += "tapHold $stick $home" }
        override fun action(button: ComboButton) = actions[button].orEmpty()
        override fun fire(button: ComboButton) { calls += "fire $button" }
        var holds = false
        override fun holdsWhilePressed(button: ComboButton) = holds
        override fun press(button: ComboButton) { calls += "press $button" }
        var recordReleases = false
        override fun released(stick: Stick) { if (recordReleases) calls += "up $stick" }
    }

    private val host = FakeHost()
    private val scheduler = Scheduler()
    private val router = StickClickRouter(scheduler, Clock(), host)
    private val press = StickClickButton.of(Stick.LEFT, StickClick.PRESS)
    private val code = KeyEvent.KEYCODE_BUTTON_THUMBL

    private fun down(device: Int = 7, repeat: Int = 0) = router.handle(Stick.LEFT, KeyEvent.ACTION_DOWN, code, device, repeat)
    private fun up(device: Int = 7) = router.handle(Stick.LEFT, KeyEvent.ACTION_UP, code, device, 0)

    @Test
    fun `a held press runs at the DOWN, once across mirrored devices, and hears the last release`() {
        host.holds = true
        host.recordReleases = true
        host.actions[press] = Prefs.SHOULDER_PAD_START
        assertTrue(down(7)); assertTrue(down(8))
        assertEquals(listOf("fired", "press $press"), host.calls)
        assertTrue(router.isDown(code))
        assertTrue(up(7))
        assertTrue(up(8))
        assertFalse(router.isDown(code))
        assertEquals(listOf("fired", "press $press", "up LEFT"), host.calls)
        // Nothing waits in the gesture machine: the release did not also fire a press.
        assertTrue(scheduler.pending.isEmpty())
    }

    @Test
    fun `with a double press or tap-and-hold bound the press waits for the gesture, and each release is still heard first`() {
        host.holds = true
        host.recordReleases = true
        host.actions[press] = Prefs.SHOULDER_PAD_START
        host.actions[StickClickButton.of(Stick.LEFT, StickClick.TAP_HOLD)] = Prefs.SHOULDER_QUICK_SETTINGS
        down(); up()
        assertEquals(listOf("fired", "up LEFT", "fire $press"), host.calls)
        host.calls.clear()
        router.reset()
        host.actions.remove(StickClickButton.of(Stick.LEFT, StickClick.TAP_HOLD))
        host.actions[StickClickButton.of(Stick.LEFT, StickClick.DOUBLE)] = Prefs.SHOULDER_QUICK_SETTINGS
        down(); up()
        assertEquals(listOf("fired", "up LEFT"), host.calls)
        router.reset()
        host.actions.remove(StickClickButton.of(Stick.LEFT, StickClick.DOUBLE))
        down()
        router.reset()
        assertFalse(up())
    }

    @Test
    fun `paused, without the modifier, excepted or unbound the click is the game's`() {
        host.actions[press] = Prefs.SHOULDER_QUICK_SETTINGS
        host.interceptionEnabled = false
        assertFalse(down()); assertFalse(up())
        host.interceptionEnabled = true
        host.modifierDown = false
        assertFalse(down()); assertFalse(up())
        host.modifierDown = true
        host.allowed = false
        assertFalse(down()); assertFalse(up())
        host.allowed = true
        host.actions.clear()
        assertFalse(down()); assertFalse(up())
        assertEquals(emptyList<String>(), host.calls)
    }

    @Test
    fun `a bound click is consumed on both mirrored devices and fires once on the last release`() {
        host.actions[press] = Prefs.SHOULDER_QUICK_SETTINGS
        assertTrue(down(7)); assertTrue(down(8)); assertTrue(down(7, repeat = 1))
        assertTrue(up(7))
        assertEquals(listOf("fired"), host.calls)
        assertTrue(up(8))
        assertFalse(up(8))
        assertEquals(listOf("fired", "fire $press"), host.calls)
    }

    @Test
    fun `the layer is fixed at the click's DOWN and drives timing and binding`() {
        host.homeDown = true
        host.actions[HomeLayerButton.of(press)] = Prefs.SHOULDER_NOTIFICATIONS
        assertTrue(down())
        host.homeDown = false
        assertTrue(up())
        assertEquals(listOf("fired", "fire ${HomeLayerButton.of(press)}"), host.calls)
        router.forgetLayers()
        assertFalse("Back layer has nothing bound", down())
    }

    @Test
    fun `a repeating click stops on release, reset forgets held clicks and pending gestures`() {
        host.actions[press] = Prefs.SHOULDER_QUICK_SETTINGS
        host.actions[StickClickButton.of(Stick.LEFT, StickClick.DOUBLE)] = Prefs.SHOULDER_NOTIFICATIONS
        host.repeatingButton = StickClickButton.of(Stick.LEFT, StickClick.TAP_HOLD)
        down(); up()
        assertEquals(listOf("fired", "stop"), host.calls)
        assertEquals(1, scheduler.pending.size)
        down()
        router.reset()
        assertEquals(0, scheduler.pending.size)
        assertFalse(up())
        assertFalse(router.handle(Stick.LEFT, KeyEvent.ACTION_MULTIPLE, code, 7, 0))
        assertTrue(host.timings.all { it.endsWith("false") })
    }
}
