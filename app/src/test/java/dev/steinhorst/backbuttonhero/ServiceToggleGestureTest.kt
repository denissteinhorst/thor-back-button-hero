package dev.steinhorst.backbuttonhero

import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        prefs.resetToDefaults()
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
