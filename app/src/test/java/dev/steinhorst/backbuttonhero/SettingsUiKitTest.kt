package dev.steinhorst.backbuttonhero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// xxhdpi pins density to 3.0 so the dp() assertion below has a concrete expected value.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "xxhdpi")
class SettingsUiKitTest {

    private fun activity() =
        Robolectric.buildActivity(SettingsActivity::class.java).create().get()

    @Test
    fun `dp converts dip to pixels at the pinned xxhdpi density of 3`() {
        val activity = activity()
        // JUnit 4 requires a delta for float comparisons - assertEquals(float, float) is deprecated.
        assertEquals(3.0f, activity.resources.displayMetrics.density, 0.001f)
        assertEquals(48, activity.dp(16))
        assertEquals(3, activity.dp(1))
        assertEquals(0, activity.dp(0))
    }

    @Test
    fun `isAccessibilityServiceEnabled is false when no service is enabled`() {
        val activity = activity()
        assertFalse(activity.isAccessibilityServiceEnabled())
    }

    @Test
    fun `currentServiceStatus is OFF when the accessibility service is disabled`() {
        val activity = activity()
        assertEquals(ServiceStatus.OFF, activity.currentServiceStatus())
    }

    @Test
    fun `serviceStatusText for ON reports the service is on`() {
        val activity = activity()
        assertEquals(
            activity.getString(R.string.status_on),
            activity.serviceStatusText(ServiceStatus.ON),
        )
    }

    @Test
    fun `serviceStatusText for UNRESPONSIVE reports it is enabled but not responding`() {
        val activity = activity()
        assertEquals(
            activity.getString(R.string.status_unresponsive),
            activity.serviceStatusText(ServiceStatus.UNRESPONSIVE),
        )
    }

    @Test
    fun `serviceStatusText for OFF includes the app name`() {
        val activity = activity()
        val expected = activity.getString(R.string.status_off, activity.getString(R.string.app_name))
        assertEquals(expected, activity.serviceStatusText(ServiceStatus.OFF))
    }
}
