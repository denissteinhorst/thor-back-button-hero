package dev.steinhorst.backbuttonhero.input

import android.view.KeyEvent
import dev.steinhorst.backbuttonhero.prefs.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComboRouterTest {
    private class FakeHost : ComboRouter.Host {
        override var modifierDown = true
        val actions = mutableMapOf<ComboButton, String>()
        var allowed = true
        val calls = mutableListOf<String>()
        override fun action(button: ComboButton) = actions[button].orEmpty()
        override fun allows(button: ComboButton) = allowed
        override fun markComboFired() { calls += "fired" }
        override fun log(message: String) { calls += "log $message" }
        override fun runCombo(button: ComboButton, action: String) { calls += "run $button $action" }
        override fun showFocusIndicator(button: ComboButton) { calls += "indicator $button" }
        override fun runReleased(button: ComboButton) { calls += "released $button" }
        var recordReleases = false
        override fun released(button: ComboButton) { if (recordReleases) calls += "up $button" }
    }

    private val host = FakeHost()
    private val router = ComboRouter(host)
    private val l1 = ShoulderButton.L1
    private val code = KeyEvent.KEYCODE_BUTTON_L1

    private fun down(device: Int = 7, repeat: Int = 0) = router.handle(KeyEvent.ACTION_DOWN, code, device, repeat, l1)
    private fun up(device: Int = 7) = router.handle(KeyEvent.ACTION_UP, code, device, 0, l1)

    @Test
    fun `without a held modifier or a binding the press is not ours and not tracked`() {
        host.actions[l1] = Prefs.SHOULDER_QUICK_SETTINGS
        host.modifierDown = false
        assertFalse(down())
        assertFalse(router.isDown(code))
        assertFalse(up())
        host.modifierDown = true
        host.actions.clear()
        assertFalse(down())
        assertFalse(up())
        assertEquals(emptyList<String>(), host.calls)
    }

    @Test
    fun `an excepted button reaches the app but still counts as a combo`() {
        host.actions[l1] = Prefs.SHOULDER_QUICK_SETTINGS
        host.allowed = false
        assertFalse(down())
        assertFalse(up())
        assertEquals(listOf("fired", "log SHOULDER L1 passthrough action=${Prefs.SHOULDER_QUICK_SETTINGS}"), host.calls)
    }

    @Test
    fun `a bound press runs once across mirrored devices and repeats, and both releases are consumed`() {
        host.actions[l1] = Prefs.SHOULDER_QUICK_SETTINGS
        assertTrue(down(7))
        assertTrue(down(8))
        assertTrue(down(7, repeat = 1))
        assertTrue(router.isDown(code))
        assertTrue(up(7))
        assertTrue(router.isDown(code))
        assertTrue(up(8))
        assertFalse(router.isDown(code))
        assertFalse(up(8))
        assertEquals(listOf("fired", "run L1 ${Prefs.SHOULDER_QUICK_SETTINGS}"), host.calls)
    }

    @Test
    fun `controller focus is armed with its indicator and runs after the last release`() {
        host.actions[l1] = Prefs.SHOULDER_CONTROLLER_FOCUS
        down(7); down(8)
        assertEquals(listOf("fired", "indicator L1"), host.calls)
        up(7)
        assertEquals(2, host.calls.size)
        up(8)
        assertEquals(listOf("fired", "indicator L1", "released L1"), host.calls)
    }

    @Test
    fun `switch app is armed without an indicator, and clear forgets armed presses`() {
        host.actions[l1] = Prefs.SHOULDER_SWITCH_APP
        down()
        router.clear()
        assertFalse(router.isDown(code))
        assertFalse(up())
        assertEquals(listOf("fired"), host.calls)
        down(); up()
        assertEquals(listOf("fired", "fired", "released L1"), host.calls)
    }

    @Test
    fun `a combo that ran at its press hears its release after the last mirrored device, with its own binding`() {
        host.recordReleases = true
        host.actions[l1] = Prefs.SHOULDER_PAD_A
        down(7); down(8)
        up(7)
        assertEquals(listOf("fired", "run L1 ${Prefs.SHOULDER_PAD_A}"), host.calls)
        // The layer may change while held; the release still names the binding that ran.
        router.handle(KeyEvent.ACTION_UP, code, 8, 0, HomeLayerButton.of(l1))
        assertEquals(listOf("fired", "run L1 ${Prefs.SHOULDER_PAD_A}", "up L1"), host.calls)
        assertFalse(up(8))
        assertEquals(3, host.calls.size)
    }

    @Test
    fun `armed presses and cleared ones report no release`() {
        host.recordReleases = true
        host.actions[l1] = Prefs.SHOULDER_CONTROLLER_FOCUS
        down(); up()
        assertEquals(listOf("fired", "indicator L1", "released L1"), host.calls)
        host.calls.clear()
        host.actions[l1] = Prefs.SHOULDER_PAD_A
        down()
        router.clear()
        down(); router.clear(); up()
        assertEquals(listOf("fired", "run L1 ${Prefs.SHOULDER_PAD_A}", "fired", "run L1 ${Prefs.SHOULDER_PAD_A}"), host.calls)
    }

    @Test
    fun `other actions are never consumed`() {
        host.actions[l1] = Prefs.SHOULDER_QUICK_SETTINGS
        assertFalse(router.handle(KeyEvent.ACTION_MULTIPLE, code, 7, 0, l1))
    }
}
