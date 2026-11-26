package dev.steinhorst.backbuttonhero.input

import android.view.InputDevice
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.prefs.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ServiceToggleGestureTest {
    private val prefs = Prefs(ApplicationProvider.getApplicationContext()).apply { toggleButtons = Prefs.TOGGLE_BOTH }
    private val scheduler = ToggleScheduler()
    private var toggles = 0
    private val gesture = ServiceToggleGesture(prefs, scheduler) { toggles++ }
    private val left = KeyEvent.KEYCODE_BUTTON_THUMBL
    private val right = KeyEvent.KEYCODE_BUTTON_THUMBR

    private fun event(key: Int, action: Int, device: Int = 1, repeat: Int = 0) =
        KeyEvent(10000L, 10000L, action, key, repeat, 0, device, 0)

    private fun down(key: Int, device: Int = 1) = gesture.handle(event(key, KeyEvent.ACTION_DOWN, device))
    private fun up(key: Int, device: Int = 1) = gesture.handle(event(key, KeyEvent.ACTION_UP, device))

    @Test
    fun `factory default toggles with L2 and R2 instead of stick clicks`() {
        prefs.toggleButtons = Prefs.TOGGLE_L2_R2
        down(left)
        down(right)
        assertTrue(scheduler.pending.isEmpty())
        down(KeyEvent.KEYCODE_BUTTON_L2)
        assertTrue(scheduler.pending.isEmpty())
        down(KeyEvent.KEYCODE_BUTTON_R2)
        assertEquals(listOf(300L), scheduler.pending.values.toList())
        scheduler.fire()
        assertEquals(1, toggles)
    }

    @Test
    fun `both sticks must overlap for the complete hold duration and fire once`() {
        down(left)
        assertTrue(scheduler.pending.isEmpty())
        down(right)
        assertEquals(listOf(300L), scheduler.pending.values.toList())
        scheduler.fire()
        assertEquals(1, toggles)
        gesture.handle(event(right, KeyEvent.ACTION_DOWN, repeat = 1))
        down(left, 2)
        assertTrue(scheduler.pending.isEmpty())
        up(right)
        down(right)
        scheduler.fire()
        assertEquals(2, toggles)
    }

    @Test
    fun `releasing either stick before deadline cancels the combination`() {
        down(left)
        down(right)
        up(left)
        scheduler.fire()
        assertEquals(0, toggles)
        down(left)
        assertEquals(1, scheduler.pending.size)
        up(right)
        assertTrue(scheduler.pending.isEmpty())
    }

    @Test
    fun `mirrored devices do not restart the timer or release a still held stick`() {
        down(left, 1)
        down(right, 1)
        val timer = scheduler.pending.keys.single()
        down(left, 2)
        down(right, 2)
        assertEquals(timer, scheduler.pending.keys.single())
        up(left, 1)
        up(right, 1)
        assertFalse(scheduler.pending.isEmpty())
        scheduler.fire()
        assertEquals(1, toggles)
        up(left, 2)
        up(right, 2)
        down(left)
        down(right)
        assertEquals(1, scheduler.pending.size)
    }

    @Test
    fun `configured hold duration applies to the selected combination`() {
        prefs.toggleHoldMs = 1700L
        down(left)
        assertTrue(scheduler.pending.isEmpty())
        down(right)
        assertEquals(listOf(1700L), scheduler.pending.values.toList())
        scheduler.fire()
        assertEquals(1, toggles)
    }

    @Test
    fun `reset cancels a pending hold and discards stale key state`() {
        down(left)
        down(right)
        gesture.reset()
        scheduler.fire()
        assertEquals(0, toggles)
        down(right)
        assertTrue(scheduler.pending.isEmpty())
        down(left)
        scheduler.fire()
        assertEquals(1, toggles)
    }

    @Test
    fun `shoulder pairs require both selected buttons and cancel when either is released`() {
        val bindings = listOf(
            Triple(Prefs.TOGGLE_L1_R1, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1),
            Triple(Prefs.TOGGLE_L2_R2, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2),
        )
        for ((binding, first, second) in bindings) {
            gesture.reset()
            prefs.toggleButtons = binding
            down(left)
            down(right)
            down(first)
            down(if (second == KeyEvent.KEYCODE_BUTTON_R1) KeyEvent.KEYCODE_BUTTON_R2 else KeyEvent.KEYCODE_BUTTON_R1)
            assertTrue(scheduler.pending.isEmpty())
            down(second)
            assertEquals(listOf(300L), scheduler.pending.values.toList())
            up(first)
            assertTrue(scheduler.pending.isEmpty())
            down(first)
            up(second)
            assertTrue(scheduler.pending.isEmpty())
            down(second)
            scheduler.fire()
        }
        assertEquals(2, toggles)
    }

    @Test
    fun `mirrored shoulders and repeats do not retrigger until the combination is released`() {
        prefs.toggleButtons = Prefs.TOGGLE_L1_R1
        val first = KeyEvent.KEYCODE_BUTTON_L1
        val second = KeyEvent.KEYCODE_BUTTON_R1
        down(first, 1)
        down(second, 1)
        down(first, 2)
        down(second, 2)
        up(first, 1)
        up(second, 1)
        scheduler.fire()
        assertEquals(1, toggles)
        gesture.handle(event(first, KeyEvent.ACTION_DOWN, device = 2, repeat = 1))
        down(second, 3)
        assertTrue(scheduler.pending.isEmpty())
        up(first, 2)
        down(first, 2)
        scheduler.fire()
        assertEquals(2, toggles)
    }

    @Test
    fun `other keys and stray repeats cannot arm the toggle`() {
        down(KeyEvent.KEYCODE_BACK)
        gesture.handle(event(left, KeyEvent.ACTION_DOWN, repeat = 1))
        gesture.handle(event(right, KeyEvent.ACTION_DOWN, repeat = 1))
        scheduler.fire()
        assertEquals(0, toggles)
    }
    @Test
    fun `not set never arms any former pair including mirrored devices`() {
        prefs.toggleButtons = Prefs.TOGGLE_NONE
        val buttons = listOf(left, right, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2)
        for (device in 1..2) buttons.forEach { down(it, device) }
        scheduler.fire()
        assertEquals(0, toggles)
        assertTrue(scheduler.pending.isEmpty())
        for (device in 1..2) buttons.forEach { up(it, device) }
    }

    @Test
    fun `changing or removing a pending combination prevents its timer from toggling`() {
        down(left)
        down(right)
        prefs.toggleButtons = Prefs.TOGGLE_NONE
        scheduler.fire()
        assertEquals(0, toggles)
        down(left)
        assertTrue(scheduler.pending.isEmpty())
        prefs.toggleButtons = Prefs.TOGGLE_L1_R1
        down(KeyEvent.KEYCODE_BUTTON_L1)
        assertTrue(scheduler.pending.isEmpty())
        down(KeyEvent.KEYCODE_BUTTON_R1)
        prefs.toggleButtons = Prefs.TOGGLE_L2_R2
        scheduler.fire()
        assertEquals(0, toggles)
        down(KeyEvent.KEYCODE_BUTTON_L2)
        down(KeyEvent.KEYCODE_BUTTON_R2)
        scheduler.fire()
        assertEquals(1, toggles)
    }

    private val pad = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_KEYBOARD
    private fun padEvent(key: Int, action: Int, device: Int = 1, repeat: Int = 0, source: Int = pad) =
        KeyEvent(10000L, 10000L, action, key, repeat, 0, device, 0, 0, source)
    private fun padDown(key: Int, device: Int = 1, repeat: Int = 0) =
        gesture.handle(padEvent(key, KeyEvent.ACTION_DOWN, device, repeat))
    private fun padUp(key: Int, device: Int = 1) = gesture.handle(padEvent(key, KeyEvent.ACTION_UP, device))
    private val back = KeyEvent.KEYCODE_BACK
    private val home = KeyEvent.KEYCODE_HOME

    @Test
    fun `start and select toggle like the other pairs without consuming either`() {
        prefs.toggleButtons = Prefs.TOGGLE_START_SELECT
        assertFalse(down(KeyEvent.KEYCODE_BUTTON_START))
        assertTrue(scheduler.pending.isEmpty())
        assertFalse(down(KeyEvent.KEYCODE_BUTTON_SELECT))
        assertEquals(listOf(300L), scheduler.pending.values.toList())
        scheduler.fire()
        assertEquals(1, toggles)
        assertFalse(up(KeyEvent.KEYCODE_BUTTON_START))
        assertFalse(up(KeyEvent.KEYCODE_BUTTON_SELECT))
    }

    @Test
    fun `back then home toggles and swallows only that home press`() {
        prefs.toggleButtons = Prefs.TOGGLE_BACK_HOME
        assertFalse(padDown(back))
        assertTrue(scheduler.pending.isEmpty())
        assertTrue(padDown(home))
        assertEquals(listOf(300L), scheduler.pending.values.toList())
        scheduler.fire()
        assertEquals(1, toggles)
        assertTrue(padDown(home, repeat = 1))
        assertFalse(padUp(back))
        // A mirrored Home of the same press stays swallowed after Back is gone.
        assertTrue(padDown(home, device = 2))
        assertTrue(padUp(home))
        assertTrue(padUp(home, device = 2))
        assertFalse(padDown(home))
        assertFalse(padUp(home))
    }

    @Test
    fun `an undeferred first press stays its own and the paired key is consumed whole`() {
        var paired = 0
        val gesture = ServiceToggleGesture(prefs, scheduler, onPaired = { paired++ }) { toggles++ }
        prefs.toggleButtons = Prefs.TOGGLE_BACK_HOME
        assertFalse(gesture.handle(padEvent(home, KeyEvent.ACTION_DOWN)))
        assertTrue(gesture.handle(padEvent(back, KeyEvent.ACTION_DOWN)))
        assertEquals(1, paired)
        scheduler.fire()
        assertEquals(1, toggles)
        assertFalse(gesture.handle(padEvent(home, KeyEvent.ACTION_UP)))
        assertTrue(gesture.handle(padEvent(back, KeyEvent.ACTION_UP)))
    }

    @Test
    fun `deferred presses are held back and replayed only when they stay alone`() {
        val replays = mutableListOf<Pair<KeyEvent, KeyEvent>>()
        var paired = 0
        val gesture = ServiceToggleGesture(prefs, scheduler, defers = { true }, onPaired = { paired++ },
            onReplay = { down, up -> replays += down to up }) { toggles++ }
        prefs.toggleButtons = Prefs.TOGGLE_BACK_HOME
        for (key in listOf(back, home)) {
            assertTrue(gesture.handle(padEvent(key, KeyEvent.ACTION_DOWN)))
            assertTrue(gesture.handle(padEvent(key, KeyEvent.ACTION_DOWN, device = 2)))
            assertTrue(gesture.handle(padEvent(key, KeyEvent.ACTION_UP)))
            assertTrue(replays.isEmpty() || replays.last().first.keyCode != key)
            assertTrue(gesture.handle(padEvent(key, KeyEvent.ACTION_UP, device = 2)))
            assertEquals(key, replays.last().first.keyCode)
            assertEquals(KeyEvent.ACTION_UP, replays.last().second.action)
        }
        // Paired, in either order: nothing is replayed and nothing passes.
        for ((first, second) in listOf(back to home, home to back)) {
            assertTrue(gesture.handle(padEvent(first, KeyEvent.ACTION_DOWN)))
            assertTrue(gesture.handle(padEvent(second, KeyEvent.ACTION_DOWN)))
            scheduler.fire()
            assertTrue(gesture.handle(padEvent(first, KeyEvent.ACTION_UP)))
            assertTrue(gesture.handle(padEvent(second, KeyEvent.ACTION_UP)))
        }
        assertEquals(2, replays.size)
        assertEquals(2, toggles)
        assertEquals(0, paired)
    }

    @Test
    fun `pairs that only observe never track presses`() {
        val gesture = ServiceToggleGesture(prefs, scheduler, defers = { true }) { toggles++ }
        prefs.toggleButtons = Prefs.TOGGLE_START_SELECT
        assertFalse(gesture.handle(padEvent(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.ACTION_DOWN)))
        assertFalse(gesture.handle(padEvent(KeyEvent.KEYCODE_BUTTON_START, KeyEvent.ACTION_UP)))
    }

    @Test
    fun `releasing back or home before the deadline cancels`() {
        prefs.toggleButtons = Prefs.TOGGLE_BACK_HOME
        padDown(back)
        padDown(home)
        padUp(back)
        assertTrue(scheduler.pending.isEmpty())
        padDown(back)
        assertEquals(1, scheduler.pending.size)
        assertTrue(padUp(home))
        assertTrue(scheduler.pending.isEmpty())
        assertEquals(0, toggles)
    }

    @Test
    fun `AYN's own Home and injected Back never count or get swallowed`() {
        prefs.toggleButtons = Prefs.TOGGLE_BACK_HOME
        padDown(back)
        assertFalse(gesture.handle(padEvent(home, KeyEvent.ACTION_DOWN, source = InputDevice.SOURCE_KEYBOARD)))
        assertTrue(scheduler.pending.isEmpty())
        padUp(back)
        assertFalse(padDown(back, device = -1))
        assertFalse(padDown(home))
        assertTrue(scheduler.pending.isEmpty())
        assertEquals(0, toggles)
    }

    @Test
    fun `other bindings and other actions never swallow home`() {
        prefs.toggleButtons = Prefs.TOGGLE_L1_R1
        assertFalse(padDown(back))
        assertFalse(padDown(home))
        prefs.toggleButtons = Prefs.TOGGLE_BACK_HOME
        assertFalse(padDown(back))
        assertFalse(gesture.handle(padEvent(home, KeyEvent.ACTION_MULTIPLE)))
    }

    @Test
    fun `labels name the printed buttons of every binding`() {
        assertNull(ServiceToggleGesture.label(Prefs.TOGGLE_NONE))
        assertEquals("L1 + R1", ServiceToggleGesture.label(Prefs.TOGGLE_L1_R1))
        assertEquals("L2 + R2", ServiceToggleGesture.label(Prefs.TOGGLE_L2_R2))
        assertEquals("L3 + R3", ServiceToggleGesture.label(Prefs.TOGGLE_BOTH))
        assertEquals("Start + Select", ServiceToggleGesture.label(Prefs.TOGGLE_START_SELECT))
        assertEquals("Back + Home", ServiceToggleGesture.label(Prefs.TOGGLE_BACK_HOME))
    }

}

private class ToggleScheduler : GestureScheduler {
    val pending = linkedMapOf<Runnable, Long>()
    override fun postDelayed(runnable: Runnable, delayMs: Long) { pending[runnable] = delayMs }
    override fun removeCallbacks(runnable: Runnable) { pending.remove(runnable) }
    override fun hasCallbacks(runnable: Runnable) = pending.containsKey(runnable)
    fun fire() {
        val due = pending.keys.toList()
        pending.clear()
        due.forEach(Runnable::run)
    }
}
