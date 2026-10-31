package dev.steinhorst.backbuttonhero

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class ControllerFocusTest {
    @Test
    fun `fixed targets resolve to their firmware values`() {
        assertEquals(ControllerFocus.TOP_VALUE, ControllerFocus.resolveValue(Prefs.DISPLAY_TOP, "2"))
        assertEquals(ControllerFocus.BOTTOM_VALUE, ControllerFocus.resolveValue(Prefs.DISPLAY_BOTTOM, "1"))
    }

    @Test
    fun `the auto-lock restore target resolves to AYN's auto-lock value`() {
        assertEquals(ControllerFocus.AUTO_VALUE, ControllerFocus.resolveValue(ControllerFocus.AUTO_TARGET, "2"))
    }

    @Test
    fun `toggle flips known values and safely defaults to top`() {
        assertEquals(ControllerFocus.BOTTOM_VALUE, ControllerFocus.resolveValue(Prefs.FOCUS_TOGGLE, "1"))
        assertEquals(ControllerFocus.TOP_VALUE, ControllerFocus.resolveValue(Prefs.FOCUS_TOGGLE, "2"))
        assertEquals(ControllerFocus.TOP_VALUE, ControllerFocus.resolveValue(Prefs.FOCUS_TOGGLE, null))
    }

    @Test
    fun `OEM command acknowledgement is independent of Android window focus`() {
        assertTrue(ControllerFocus.commandSucceeded("OK\n"))
        assertTrue(ControllerFocus.commandSucceeded("unrelated pservice output\nOK"))
        assertFalse(ControllerFocus.commandSucceeded(null))
        assertFalse(ControllerFocus.commandSucceeded(""))
        assertFalse(ControllerFocus.commandSucceeded("command failed"))
    }
}

@RunWith(RobolectricTestRunner::class)
class ControllerFocusRoutingTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val focus get() = ControllerFocus(context) {}

    private fun routeIs(value: String?) {
        Settings.System.putString(context.contentResolver, ControllerFocus.FOCUS_SETTING, value)
    }

    @Test
    fun `toggle goes to the screen that does not have the route`() {
        routeIs(ControllerFocus.TOP_VALUE)
        assertFalse(focus.targetIsTop(Prefs.FOCUS_TOGGLE))
        routeIs(ControllerFocus.BOTTOM_VALUE)
        assertTrue(focus.targetIsTop(Prefs.FOCUS_TOGGLE))
    }

    @Test
    fun `fixed targets ignore the current route`() {
        routeIs(ControllerFocus.BOTTOM_VALUE)
        assertTrue(focus.targetIsTop(Prefs.DISPLAY_TOP))
        routeIs(ControllerFocus.TOP_VALUE)
        assertFalse(focus.targetIsTop(Prefs.DISPLAY_BOTTOM))
    }

    @Test
    fun `the retained route keeps fixed screens and auto-lock`() {
        routeIs(ControllerFocus.TOP_VALUE)
        assertEquals(Prefs.DISPLAY_TOP, focus.currentTarget())
        routeIs(ControllerFocus.BOTTOM_VALUE)
        assertEquals(Prefs.DISPLAY_BOTTOM, focus.currentTarget())
        routeIs(ControllerFocus.AUTO_VALUE)
        assertEquals(ControllerFocus.AUTO_TARGET, focus.currentTarget())
        routeIs(null)
        assertEquals(ControllerFocus.AUTO_TARGET, focus.currentTarget())
    }

    @Test
    fun `an unreadable route toggles towards the top screen`() {
        routeIs(null)
        assertTrue(focus.targetIsTop(Prefs.FOCUS_TOGGLE))
    }
}
