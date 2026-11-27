package dev.steinhorst.backbuttonhero.prefs

import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.input.ShoulderButton
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Freezes every stored action sentinel as a hand-written literal, independent of the constants that
 * produce them, so a later typed codec is checked against what installs actually hold.
 */
@RunWith(RobolectricTestRunner::class)
class ActionSentinelContractTest {

    private val stored = listOf(
        "@navigation_flick_left", "@navigation_flick_right", "@home", "@back", "@switcher", "@swap_apps",
        "@recents", "@screenshot", "@quick_settings", "@notifications", "@internet", "@bluetooth",
        "@night_light", "@extra_dim", "@display_settings", "@sound_settings", "@location_settings",
        "@cast_settings", "@dnd_settings", "@battery_saver", "@volume_up", "@volume_down", "@brightness_up",
        "@brightness_down", "@controller_focus", "@switch_active_app", "@app_control", "@close_apps",
        "@close_background_apps", "@power_menu", "@standby", "@volume_mute", "@record_video", "@macro",
        "@media_play_pause", "@media_next", "@media_previous", "@status_overlay", "@last_app", "@gyro",
        "@gyro_calibrate", "@app_ask", "@app_favorites", "@recents_focused",
        // AYN controls
        "@vibration", "@direct_power", "@force_landscape", "@virtual_mouse", "@do_not_disturb",
        "@mute_microphone", "@performance", "@fan", "@refresh_rate", "@stick_light", "@handle", "@display",
    )

    private fun prefs() = Prefs(ApplicationProvider.getApplicationContext())

    @Test
    fun `the frozen sentinels are exactly the known actions`() {
        assertEquals(stored.size, stored.toSet().size)
        assertEquals(stored.toSet(), Prefs.KNOWN_ACTIONS)
    }

    @Test
    fun `every sentinel is stored and read back unchanged and is no app`() {
        val prefs = prefs()
        for (action in stored) {
            prefs.setShoulderAction(ShoulderButton.L1, action)
            assertEquals(action, prefs.shoulderAction(ShoulderButton.L1))
            assertFalse(action, Prefs.isShoulderApp(action))
        }
    }

    @Test
    fun `an unknown sentinel is stored raw and read back unchanged`() {
        val prefs = prefs()
        prefs.setShoulderAction(ShoulderButton.L1, "@not_yet_known")
        assertEquals("@not_yet_known", prefs.shoulderAction(ShoulderButton.L1))
        assertFalse(Prefs.isShoulderApp("@not_yet_known"))
        assertTrue(Prefs.isShoulderApp("com.example.game"))
        assertFalse(Prefs.isShoulderApp(""))
    }

    @Test
    fun `every sentinel survives a backup round trip on a macro button`() {
        val factory = SettingsState()
        for (action in stored) {
            val state = factory.copy(bindings = factory.bindings + ("l1" to factory.bindings.getValue("l1").copy(action = action)))
            val text = SettingsBackup.write(state, "1.0.0", 1, Instant.parse("2026-11-23T00:00:00Z"))
            val loaded = (SettingsBackup.read(text) as SettingsBackup.Result.Loaded).state
            assertEquals(action, action, loaded.bindings.getValue("l1").action)
        }
    }

    @Test
    fun `a backup drops an unknown sentinel back to the default`() {
        val factory = SettingsState()
        val state = factory.copy(bindings = factory.bindings + ("l1" to factory.bindings.getValue("l1").copy(action = "@not_yet_known")))
        val text = SettingsBackup.write(state, "1.0.0", 1, Instant.parse("2026-11-23T00:00:00Z"))
        val loaded = (SettingsBackup.read(text) as SettingsBackup.Result.Loaded).state
        assertEquals(factory.bindings.getValue("l1").action, loaded.bindings.getValue("l1").action)
    }
}
