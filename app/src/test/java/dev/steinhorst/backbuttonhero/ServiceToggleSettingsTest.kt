package dev.steinhorst.backbuttonhero

import android.os.Looper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
class ServiceToggleSettingsTest {
    private fun withSuspendedService(check: (SwitcherService, Prefs, PadHelper) -> Unit) {
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        val helper = PadHelper(service, {}, {}, {})
        // Exercise service reactions without starting a real privileged input connection.
        ReflectionHelpers.setField(helper, "closed", true)
        helper.setSuspended(true)
        ReflectionHelpers.setField(service, "padHelper", helper)
        ReflectionHelpers.setField(service, "dpadOverlay", DpadOverlay(service, {}, {}))
        ReflectionHelpers.setField(service, "interceptionEnabled", false)
        val prefs = Prefs(service)
        val listener = ReflectionHelpers.getField<() -> Unit>(service, "prefsListener")
        prefs.registerListener(listener)
        try {
            check(service, prefs, helper)
        } finally {
            prefs.unregisterListener(listener)
            service.onDestroy()
        }
    }

    @Test
    fun `removing activation shortcut reactivates service and exits helper pass through`() = withSuspendedService { service, prefs, helper ->
        val before = prefs.snapshot()
        val plan = SettingsChangePlan.create(before, SettingsSection.SETTINGS, SettingsChangeAction.UNBIND)
        assertFalse(ReflectionHelpers.getField(service, "interceptionEnabled"))
        assertTrue(prefs.applySettings(before, plan.after))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ReflectionHelpers.getField(service, "interceptionEnabled"))
        assertFalse(ReflectionHelpers.getField(helper, "suspended"))
        // Subsequent changed values must not toggle it back off.
        prefs.doubleTapMs = 500L
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ReflectionHelpers.getField(service, "interceptionEnabled"))
    }

    @Test
    fun `whole app preset preserves temporary deactivation when a shortcut remains assigned`() = withSuspendedService { service, prefs, helper ->
        prefs.toggleButtons = Prefs.TOGGLE_L1_R1
        val before = prefs.snapshot()
        val plan = SettingsChangePlan.create(before, null, SettingsChangeAction.PRESET)
        assertTrue(prefs.applySettings(before, plan.after))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(ReflectionHelpers.getField(service, "interceptionEnabled"))
        assertTrue(ReflectionHelpers.getField(helper, "suspended"))
    }
}
