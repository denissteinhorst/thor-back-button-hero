package dev.steinhorst.backbuttonhero.input

import dev.steinhorst.backbuttonhero.prefs.Prefs
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectionalRouterTest {
    private class FakeHost : DirectionalRouter.Host {
        override var modifierDown = true
        override var stickInputBlocked = false
        override var overlayAttached = false
        override var repeatingButton: ComboButton? = null
        var homeLayer = false
        var allowed = true
        val actions = mutableMapOf<ComboButton, String>()
        val calls = mutableListOf<String>()
        override fun layered(button: ComboButton) = if (homeLayer) HomeLayerButton.of(button) else button
        override fun action(button: ComboButton) = actions[button].orEmpty()
        override fun allows(button: ComboButton) = allowed
        override fun markComboFired() { calls += "fired" }
        override fun log(message: String) { calls += "log $message" }
        override fun showFocusIndicator(button: ComboButton) { calls += "indicator $button" }
        override fun runCombo(button: ComboButton, action: String) { calls += "run $button $action" }
        override fun startLevelRepeat(button: ComboButton, action: String) { calls += "repeat $button" }
        override fun stopLevelRepeat() { calls += "stop" }
        override fun runReleased(button: ComboButton) { calls += "released $button" }
    }

    private val host = FakeHost()
    private val router = DirectionalRouter(host)
    private val leftUp = StickButton.of(Stick.LEFT, DpadDirection.UP)

    @Test
    fun `a direction needs the modifier and a binding, and runs with level repeat`() {
        router.press(DpadDirection.LEFT)
        host.actions[DpadDirection.LEFT] = Prefs.SHOULDER_QUICK_SETTINGS
        host.modifierDown = false
        router.press(DpadDirection.LEFT)
        assertEquals(emptyList<String>(), host.calls)
        host.modifierDown = true
        router.press(DpadDirection.LEFT)
        assertEquals(listOf("fired", "stop", "run LEFT ${Prefs.SHOULDER_QUICK_SETTINGS}", "repeat LEFT"), host.calls)
    }

    @Test
    fun `an excepted direction is a combo without an action, an excepted stick is neither`() {
        host.actions[DpadDirection.UP] = Prefs.SHOULDER_QUICK_SETTINGS
        host.actions[leftUp] = Prefs.SHOULDER_QUICK_SETTINGS
        host.allowed = false
        router.press(DpadDirection.UP)
        router.stick(Stick.LEFT, DpadDirection.UP)
        assertEquals(listOf("fired", "log DPAD   UP passthrough action=${Prefs.SHOULDER_QUICK_SETTINGS}"), host.calls)
    }

    @Test
    fun `the Home layer's binding is used while Home owns the press`() {
        host.homeLayer = true
        host.actions[HomeLayerButton.of(DpadDirection.DOWN)] = Prefs.SHOULDER_NOTIFICATIONS
        router.press(DpadDirection.DOWN)
        assertEquals("fired", host.calls.first())
        assertEquals(4, host.calls.size)
    }

    @Test
    fun `a focus direction arms and commits on its own axis, a switch on release too`() {
        host.actions[DpadDirection.LEFT] = Prefs.SHOULDER_CONTROLLER_FOCUS
        host.actions[DpadDirection.UP] = Prefs.SHOULDER_SWITCH_APP
        router.press(DpadDirection.LEFT)
        router.release(horizontal = false)
        assertEquals(listOf("fired", "indicator LEFT"), host.calls)
        router.release(horizontal = true)
        router.release(horizontal = true)
        assertEquals(listOf("fired", "indicator LEFT", "released LEFT"), host.calls)
        host.calls.clear()
        router.press(DpadDirection.UP)
        router.release(horizontal = false)
        assertEquals(listOf("fired", "released UP"), host.calls)
    }

    @Test
    fun `an attached overlay defers the commit until it is detached, and forgetting drops it`() {
        host.actions[DpadDirection.LEFT] = Prefs.SHOULDER_CONTROLLER_FOCUS
        host.overlayAttached = true
        router.press(DpadDirection.LEFT)
        router.release(horizontal = true)
        assertEquals(2, host.calls.size)
        host.overlayAttached = false
        router.overlayDetached()
        assertEquals("released LEFT", host.calls.last())
        host.calls.clear()
        router.press(DpadDirection.LEFT)
        router.forgetArmed()
        router.overlayDetached()
        assertEquals(listOf("fired", "indicator LEFT"), host.calls)
    }

    @Test
    fun `a stick runs per deflection unless blocked, and commits an armed focus at its own neutral`() {
        host.actions[leftUp] = Prefs.SHOULDER_QUICK_SETTINGS
        host.stickInputBlocked = true
        router.stick(Stick.LEFT, DpadDirection.UP)
        assertEquals(emptyList<String>(), host.calls)
        host.stickInputBlocked = false
        router.stick(Stick.LEFT, DpadDirection.UP)
        assertEquals(listOf("fired", "stop", "log STICK  $leftUp action=${Prefs.SHOULDER_QUICK_SETTINGS}",
            "run $leftUp ${Prefs.SHOULDER_QUICK_SETTINGS}", "repeat $leftUp"), host.calls)
        host.calls.clear()
        host.actions[leftUp] = Prefs.SHOULDER_CONTROLLER_FOCUS
        router.stick(Stick.LEFT, DpadDirection.UP)
        router.stick(Stick.RIGHT, null)
        router.stick(Stick.LEFT, null)
        assertEquals(listOf("fired", "stop", "indicator $leftUp", "released $leftUp"), host.calls)
    }

    @Test
    fun `releases stop a repeat only on their own axis or stick`() {
        host.repeatingButton = DpadDirection.LEFT
        router.release(horizontal = false)
        router.release(horizontal = true)
        host.repeatingButton = leftUp
        router.stick(Stick.RIGHT, null)
        router.stick(Stick.LEFT, null)
        host.repeatingButton = HomeLayerButton.of(DpadDirection.UP)
        router.release(horizontal = false)
        assertEquals(listOf("stop", "stop", "stop"), host.calls)
    }
}
