package dev.steinhorst.backbuttonhero

import dev.steinhorst.backbuttonhero.support.selectSettingsPage

import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.support.Golden
import dev.steinhorst.backbuttonhero.support.ViewTree
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-xxhdpi")
class ShoulderRowSnapshotTest {

    /** Starts from L2 and R2 unassigned, as before they got factory shortcuts, unless [factory]. */
    private fun snapshotWith(name: String, factory: Boolean = false, configure: Prefs.() -> Unit) {
        Prefs(ApplicationProvider.getApplicationContext()).apply {
            if (!factory) {
                setShoulderAction(ShoulderButton.L2, "")
                setShoulderAction(ShoulderButton.R2, "")
            }
            configure()
        }
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        selectSettingsPage(activity, if (name.startsWith("dpad")) SettingsSection.DPAD else SettingsSection.SHOULDERS)
        val root = activity.findViewById<android.view.View>(android.R.id.content)
        Golden.verify(name, ViewTree.describe(root))
    }

    @Test
    fun `all four shoulder buttons unassigned`() = snapshotWith("shoulder_all_unassigned") {
        ShoulderButton.entries.forEach { setShoulderAction(it, "") }
    }

    @Test
    fun `L1 assigned to a settings sentinel shows the top-screen toggle`() =
        snapshotWith("shoulder_l1_sentinel_with_toggle") {
            setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_HOME)
            setShoulderDisplay(ShoulderButton.L1, Prefs.DISPLAY_TOP)
        }

    @Test
    fun `R1 assigned to the screenshot hides the top-screen toggle and shows the screen row, top by default`() =
        snapshotWith("shoulder_r1_screenshot_no_toggle") {
            setShoulderAction(ShoulderButton.R1, Prefs.SHOULDER_SCREENSHOT)
        }

    @Test
    fun `L2 assigned to launch an app shows the top-screen toggle`() =
        snapshotWith("shoulder_l2_app_with_toggle") {
            setShoulderAction(ShoulderButton.L2, "com.android.chrome")
        }

    @Test
    fun `L2 assigned to a volume action shows the screen row instead of the top-screen toggle`() =
        snapshotWith("shoulder_l2_volume_with_target") {
            setShoulderAction(ShoulderButton.L2, Prefs.SHOULDER_VOLUME_UP)
            setLevelTarget(ShoulderButton.L2, Prefs.DISPLAY_TOP)
        }

    @Test
    fun `all D-pad directions unassigned hides the screen rows and the root-service warning`() =
        snapshotWith("dpad_all_unassigned") {
            DpadDirection.entries.forEach { setShoulderAction(it, "") }
        }

    @Test
    fun `defaults - L1 and R1 are the navigation-flick sentinels, no toggle shown`() =
        snapshotWith("shoulder_defaults_flick_no_toggle", factory = true) {
            // no-op: constructing Prefs already applies the factory defaults
        }

    @Test
    fun `D-pad focus action groups its two child options`() = snapshotWith("dpad_focus_options") {
        setShoulderAction(DpadDirection.UP, Prefs.SHOULDER_CONTROLLER_FOCUS)
        setFocusTarget(DpadDirection.UP, Prefs.DISPLAY_BOTTOM)
    }
}
