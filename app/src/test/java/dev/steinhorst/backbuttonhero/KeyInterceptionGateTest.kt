package dev.steinhorst.backbuttonhero

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KeyInterceptionGateTest {
    private val gate = KeyInterceptionGate()
    private var calls = 0
    private fun event(action: Int, key: Int = KeyEvent.KEYCODE_BACK, repeat: Int = 0) =
        KeyEvent(10000L, 10000L, action, key, repeat)
    private fun handle(action: Int, enabled: Boolean, consume: Boolean = true, repeat: Int = 0): Boolean =
        gate.handle(event(action, repeat = repeat), enabled) { calls++; consume }

    @Test
    fun `disabled keys go straight through without calling interception`() {
        assertFalse(handle(KeyEvent.ACTION_DOWN, false))
        assertFalse(handle(KeyEvent.ACTION_UP, false))
        assertEquals(0, calls)
    }

    @Test
    fun `consumed press stays consumed through disable and reenable until release`() {
        assertTrue(handle(KeyEvent.ACTION_DOWN, true))
        gate.changed()
        assertTrue(handle(KeyEvent.ACTION_DOWN, false, repeat = 1))
        gate.changed()
        assertTrue(handle(KeyEvent.ACTION_UP, true))
        assertEquals(1, calls)
        assertTrue(handle(KeyEvent.ACTION_DOWN, true))
        assertTrue(handle(KeyEvent.ACTION_UP, true))
        assertEquals(3, calls)
    }

    @Test
    fun `passthrough press stays untouched if interception is reenabled before release`() {
        assertFalse(handle(KeyEvent.ACTION_DOWN, false))
        gate.changed()
        assertFalse(handle(KeyEvent.ACTION_DOWN, true, repeat = 1))
        assertFalse(handle(KeyEvent.ACTION_UP, true))
        assertEquals(0, calls)
        assertTrue(handle(KeyEvent.ACTION_DOWN, true))
        assertTrue(handle(KeyEvent.ACTION_UP, true))
    }

    @Test
    fun `unconsumed game button keeps its release across disable`() {
        assertFalse(handle(KeyEvent.ACTION_DOWN, true, consume = false))
        gate.changed()
        assertFalse(handle(KeyEvent.ACTION_UP, false))
        assertEquals(1, calls)
    }

    @Test
    fun `clearing service state discards old press ownership`() {
        handle(KeyEvent.ACTION_DOWN, true)
        gate.changed()
        gate.clear()
        assertFalse(handle(KeyEvent.ACTION_DOWN, true, consume = false))
        assertEquals(2, calls)
    }
}
