package dev.steinhorst.backbuttonhero

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ActiveAppSwitchTest {
    @Test
    fun `focus follows by default for every hotkey and remains separate from screen targets`() {
        val prefs = Prefs(ApplicationProvider.getApplicationContext())
        for (button in ShoulderButton.entries + DpadDirection.entries) {
            assertTrue(prefs.switchAppFollowFocus(button))
            prefs.setSwitchAppFollowFocus(button, false)
            assertFalse(prefs.switchAppFollowFocus(button))
        }
        assertEquals(Prefs.DISPLAY_BOTTOM, prefs.shoulderDisplay(ShoulderButton.L1))
        prefs.resetToDefaults()
        assertTrue(prefs.switchAppFollowFocus(ShoulderButton.L1))
    }

    @Test
    fun `focus preference round trips through snapshots and scoped presets`() {
        val prefs = Prefs(ApplicationProvider.getApplicationContext())
        prefs.setShoulderAction(DpadDirection.UP, Prefs.SHOULDER_SWITCH_APP)
        prefs.setSwitchAppFollowFocus(DpadDirection.UP, false)
        assertTrue(prefs.switchAppFollowFocus(ShoulderButton.L1))
        val before = prefs.snapshot()
        assertFalse(before.bindings.getValue(DpadDirection.UP.prefKey).switchAppFollow)
        val plan = SettingsChangePlan.create(before, SettingsSection.DPAD, SettingsChangeAction.PRESET)
        assertTrue(prefs.applySettings(before, plan.after))
        assertTrue(prefs.switchAppFollowFocus(DpadDirection.UP))
        assertEquals(plan.after, prefs.snapshot())
    }

    @Test
    fun `a settings window with the same UID cannot substitute for the focus shield`() {
        val title = "BBHAutoFocus-1234"
        val window = "0: name='abc $title', displayId=17, inputConfig=0x0, ownerPid=123, ownerUid=10186, frame=[0,0][48,48]"
        assertTrue(ControllerFocusTouch.shieldPresent(window, title, 17, 10186))
        assertFalse(ControllerFocusTouch.shieldPresent(window, "BBHAutoFocus-5678", 17, 10186))
        assertFalse(ControllerFocusTouch.shieldPresent(window, title, 0, 10186))
        assertFalse(ControllerFocusTouch.shieldPresent(window, title, 17, 10187))
        assertFalse(ControllerFocusTouch.shieldPresent(window.replace("0x0", "NOT_TOUCHABLE"), title, 17, 10186))
        assertFalse(ControllerFocusTouch.shieldPresent(window.replace(title, "SettingsActivity"), title, 17, 10186))
    }

    @Test
    fun `auto route parses the actual focused display without assuming a bottom display id`() {
        assertEquals(17, AutoControllerFocus.focusedDisplay("Input Dispatcher State:\n  FocusedDisplayId: 17\n  FocusedApplications:"))
        assertEquals(0, AutoControllerFocus.focusedDisplay("  FocusedDisplayId: 0\n"))
        assertNull(AutoControllerFocus.focusedDisplay(null))
        assertNull(AutoControllerFocus.focusedDisplay("  FocusedDisplayId: -1"))
        assertNull(AutoControllerFocus.focusedDisplay("ERR unavailable"))
    }
}
