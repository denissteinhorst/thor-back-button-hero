package dev.steinhorst.backbuttonhero

import org.junit.Assert.assertEquals
import org.junit.Test

class TopRecentsNavigationTest {
    private val events = mutableListOf<String>()
    private val navigation = TopRecentsNavigation({ events += it.name }, { events += "DISMISS" })

    @Test
    fun `first left and down are retained until ready exactly once and in order`() {
        navigation.press(DpadDirection.LEFT)
        navigation.press(DpadDirection.DOWN)
        assertEquals(emptyList<String>(), events)
        navigation.ready()
        navigation.ready()
        assertEquals(listOf("LEFT", "DOWN"), events)
    }

    @Test
    fun `up leaves actions before it can dismiss a card`() {
        navigation.ready()
        navigation.press(DpadDirection.DOWN)
        navigation.press(DpadDirection.DOWN)
        navigation.press(DpadDirection.RIGHT)
        navigation.press(DpadDirection.UP)
        navigation.press(DpadDirection.UP)
        assertEquals(listOf("DOWN", "RIGHT", "UP", "DISMISS"), events)
    }

    @Test
    fun `closing cancels queued navigation and resets action region`() {
        navigation.press(DpadDirection.DOWN)
        navigation.reset()
        navigation.ready()
        navigation.press(DpadDirection.UP)
        assertEquals(listOf("DISMISS"), events)
    }

    @Test
    fun `dismissal can suspend draining until card focus is restored`() {
        lateinit var state: TopRecentsNavigation
        state = TopRecentsNavigation({ events += it.name }, {
            events += "DISMISS"
            state.pause()
        })
        state.press(DpadDirection.UP)
        state.press(DpadDirection.LEFT)
        state.ready()
        assertEquals(listOf("DISMISS"), events)
        state.ready()
        assertEquals(listOf("DISMISS", "LEFT"), events)
    }

    @Test
    fun `readiness on an actual action focus preserves up as navigation instead of dismissal`() {
        navigation.press(DpadDirection.UP)
        navigation.ready(actionsFocused = true)
        navigation.press(DpadDirection.UP)
        assertEquals(listOf("UP", "DISMISS"), events)
    }
}
