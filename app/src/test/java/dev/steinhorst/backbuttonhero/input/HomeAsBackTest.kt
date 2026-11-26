package dev.steinhorst.backbuttonhero.input

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_HOME
import dev.steinhorst.backbuttonhero.input.HomeAsBack.Route
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeAsBackTest {
    private val alias = HomeAsBack()

    private fun down(key: Int, enabled: Boolean = true, device: Int = 7, repeat: Int = 0) =
        alias.route(key, device, ACTION_DOWN, repeat, enabled)

    private fun up(key: Int, enabled: Boolean = true, device: Int = 7) = alias.route(key, device, ACTION_UP, 0, enabled)

    @Test
    fun `disabled leaves both keys alone`() {
        assertEquals(Route.PASS, down(KEYCODE_HOME, enabled = false))
        assertEquals(Route.PASS, up(KEYCODE_HOME, enabled = false))
        assertEquals(Route.PASS, down(KEYCODE_BACK, enabled = false))
        assertEquals(Route.PASS, up(KEYCODE_BACK, enabled = false))
    }

    @Test
    fun `home alone becomes back, repeats included`() {
        assertEquals(Route.AS_BACK, down(KEYCODE_HOME))
        assertEquals(Route.AS_BACK, down(KEYCODE_HOME, repeat = 1))
        assertEquals(Route.AS_BACK, up(KEYCODE_HOME))
        assertEquals(Route.PASS, up(KEYCODE_HOME))
    }

    @Test
    fun `back alone stays back`() {
        assertEquals(Route.PASS, down(KEYCODE_BACK))
        assertEquals(Route.PASS, down(KEYCODE_BACK, repeat = 1))
        assertEquals(Route.PASS, up(KEYCODE_BACK))
    }

    @Test
    fun `the second key is swallowed whole while the first is held`() {
        assertEquals(Route.PASS, down(KEYCODE_BACK))
        assertEquals(Route.SWALLOW, down(KEYCODE_HOME))
        assertEquals(Route.SWALLOW, down(KEYCODE_HOME, repeat = 1))
        assertEquals(Route.PASS, up(KEYCODE_BACK))
        // Back is gone, but this Home press began as the second key and stays swallowed.
        assertEquals(Route.SWALLOW, down(KEYCODE_HOME, repeat = 2))
        assertEquals(Route.SWALLOW, up(KEYCODE_HOME))
        assertEquals(Route.AS_BACK, down(KEYCODE_HOME))
    }

    @Test
    fun `back while home owns the virtual back is swallowed`() {
        assertEquals(Route.AS_BACK, down(KEYCODE_HOME))
        assertEquals(Route.SWALLOW, down(KEYCODE_BACK))
        assertEquals(Route.AS_BACK, up(KEYCODE_HOME))
        assertEquals(Route.SWALLOW, up(KEYCODE_BACK))
    }

    @Test
    fun `a press keeps its route when the option changes before its release`() {
        assertEquals(Route.AS_BACK, down(KEYCODE_HOME))
        assertEquals(Route.AS_BACK, up(KEYCODE_HOME, enabled = false))
        assertEquals(Route.PASS, down(KEYCODE_HOME, enabled = false))
        assertEquals(Route.PASS, up(KEYCODE_HOME))
    }

    @Test
    fun `duplicate devices release the virtual back only after the last up`() {
        assertEquals(Route.AS_BACK, down(KEYCODE_HOME, device = 7))
        assertEquals(Route.AS_BACK, down(KEYCODE_HOME, device = 8))
        assertEquals(Route.AS_BACK, up(KEYCODE_HOME, device = 7))
        assertEquals(Route.SWALLOW, down(KEYCODE_BACK))
        assertEquals(Route.AS_BACK, up(KEYCODE_HOME, device = 8))
        assertEquals(Route.SWALLOW, up(KEYCODE_BACK))
        assertEquals(Route.PASS, up(KEYCODE_HOME, device = 9))
    }

    @Test
    fun `reset forgets held keys`() {
        down(KEYCODE_HOME)
        down(KEYCODE_BACK)
        alias.reset()
        assertEquals(Route.PASS, up(KEYCODE_BACK))
        assertEquals(Route.PASS, down(KEYCODE_BACK))
    }

    @Test
    fun `an unknown action keeps the owner's route`() {
        assertEquals(Route.AS_BACK, down(KEYCODE_HOME))
        assertEquals(Route.AS_BACK, alias.route(KEYCODE_HOME, 7, 2, 0, true))
        assertEquals(Route.PASS, alias.route(KEYCODE_BACK, 7, 2, 0, true))
    }
}
