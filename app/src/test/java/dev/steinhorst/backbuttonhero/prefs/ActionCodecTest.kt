package dev.steinhorst.backbuttonhero.prefs

import dev.steinhorst.backbuttonhero.actions.Ayn
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ActionCodecTest {

    // Written out by hand, not derived from the enum, so a changed sentinel in the code fails here.
    private val table: Map<Action, String> = mapOf(
        Action.Builtin.FLICK_LEFT to "@navigation_flick_left", Action.Builtin.FLICK_RIGHT to "@navigation_flick_right",
        Action.Builtin.HOME to "@home", Action.Builtin.BACK to "@back", Action.Builtin.SWITCHER to "@switcher",
        Action.Builtin.SWAP_APPS to "@swap_apps", Action.Builtin.RECENTS to "@recents",
        Action.Builtin.SCREENSHOT to "@screenshot", Action.Builtin.QUICK_SETTINGS to "@quick_settings",
        Action.Builtin.NOTIFICATIONS to "@notifications", Action.Builtin.INTERNET to "@internet",
        Action.Builtin.BLUETOOTH to "@bluetooth", Action.Builtin.NIGHT_LIGHT to "@night_light",
        Action.Builtin.EXTRA_DIM to "@extra_dim", Action.Builtin.DISPLAY_SETTINGS to "@display_settings",
        Action.Builtin.SOUND_SETTINGS to "@sound_settings", Action.Builtin.LOCATION_SETTINGS to "@location_settings",
        Action.Builtin.CAST_SETTINGS to "@cast_settings", Action.Builtin.DND_SETTINGS to "@dnd_settings",
        Action.Builtin.BATTERY_SAVER to "@battery_saver", Action.Builtin.VOLUME_UP to "@volume_up",
        Action.Builtin.VOLUME_DOWN to "@volume_down", Action.Builtin.VOLUME_MUTE to "@volume_mute",
        Action.Builtin.BRIGHTNESS_UP to "@brightness_up", Action.Builtin.BRIGHTNESS_DOWN to "@brightness_down",
        Action.Builtin.CONTROLLER_FOCUS to "@controller_focus", Action.Builtin.SWITCH_ACTIVE_APP to "@switch_active_app",
        Action.Builtin.LAST_APP to "@last_app", Action.Builtin.APP_CONTROL to "@app_control",
        Action.Builtin.CLOSE_APPS to "@close_apps", Action.Builtin.CLOSE_BACKGROUND_APPS to "@close_background_apps",
        Action.Builtin.POWER_MENU to "@power_menu", Action.Builtin.STANDBY to "@standby",
        Action.Builtin.RECORD_VIDEO to "@record_video", Action.Builtin.MACRO to "@macro",
        Action.Builtin.MEDIA_PLAY_PAUSE to "@media_play_pause", Action.Builtin.MEDIA_NEXT to "@media_next",
        Action.Builtin.MEDIA_PREVIOUS to "@media_previous", Action.Builtin.STATUS_OVERLAY to "@status_overlay",
        Action.Builtin.GYRO to "@gyro", Action.Builtin.GYRO_CALIBRATE to "@gyro_calibrate",
        Action.Builtin.APP_ASK to "@app_ask", Action.Builtin.APP_FAVORITES to "@app_favorites",
        Action.Builtin.RECENTS_FOCUSED to "@recents_focused",
        Action.AynChoice(Ayn.Group.PERFORMANCE) to "@performance", Action.AynChoice(Ayn.Group.FAN) to "@fan",
        Action.AynChoice(Ayn.Group.HANDLE) to "@handle", Action.AynChoice(Ayn.Group.DISPLAY) to "@display",
        Action.AynChoice(Ayn.Group.REFRESH_RATE) to "@refresh_rate", Action.AynChoice(Ayn.Group.STICK_LIGHT) to "@stick_light",
        Action.AynSwitch(Ayn.Toggle.DIRECT_POWER) to "@direct_power", Action.AynSwitch(Ayn.Toggle.VIBRATION) to "@vibration",
        Action.AynSwitch(Ayn.Toggle.FORCE_LANDSCAPE) to "@force_landscape",
        Action.AynSwitch(Ayn.Toggle.VIRTUAL_MOUSE) to "@virtual_mouse",
        Action.AynSwitch(Ayn.Toggle.DO_NOT_DISTURB) to "@do_not_disturb",
        Action.AynSwitch(Ayn.Toggle.MUTE_MICROPHONE) to "@mute_microphone",
    )

    @Test
    fun `every known action has exactly its frozen string both ways`() {
        assertEquals(table.size, table.values.toSet().size)
        assertEquals(Prefs.KNOWN_ACTIONS, table.values.toSet())
        for ((action, raw) in table) {
            assertEquals(raw, ActionCodec.encode(action))
            assertEquals(action, ActionCodec.decode(raw))
        }
        val typed = Action.Builtin.entries.size + Ayn.Group.entries.size + Ayn.Toggle.entries.size
        assertEquals(typed, table.size)
    }

    @Test
    fun `empty, package and unknown values decode like today's string checks and encode back unchanged`() {
        assertEquals(Action.Unassigned, ActionCodec.decode(""))
        assertEquals(Action.LaunchApp("com.example.game"), ActionCodec.decode("com.example.game"))
        // Local reads never validated package names; only the privileged boundary does.
        assertEquals(Action.LaunchApp("not a package"), ActionCodec.decode("not a package"))
        assertEquals(Action.Unknown("@swap_screens"), ActionCodec.decode("@swap_screens"))
        assertEquals(Action.Unknown("@"), ActionCodec.decode("@"))
        for (raw in listOf("", "com.example.game", "not a package", "@swap_screens", "@", "@Home", " @home")) {
            assertEquals(raw, ActionCodec.encode(ActionCodec.decode(raw)))
            assertEquals(Prefs.isShoulderApp(raw), ActionCodec.decode(raw) is Action.LaunchApp)
        }
    }

    @Test
    fun `every stored action string of the storage fixtures round trips byte exactly`() {
        val values = File("src/test/resources/storage").listFiles { f -> f.name.endsWith(".txt") }!!.flatMap { file ->
            file.readLines().mapNotNull { line ->
                val parts = line.split('\t')
                parts.takeIf { it.size >= 3 && it[0].endsWith("_package") && it[1] == "string" }?.get(2)
            }
        }
        assertTrue(values.isNotEmpty())
        for (raw in values) assertEquals(raw, ActionCodec.encode(ActionCodec.decode(raw)))
    }

    @Test
    fun `every action of the backup fixtures round trips byte exactly`() {
        for (name in listOf("backup-fresh.json", "backup-populated.json")) {
            val bindings = JSONObject(File("src/test/resources/storage/$name").readText()).getJSONObject("settings").getJSONObject("bindings")
            val keys = bindings.keys().asSequence().toList()
            assertTrue(keys.isNotEmpty())
            for (key in keys) {
                val raw = bindings.getJSONObject(key).optString("action")
                assertEquals(raw, ActionCodec.encode(ActionCodec.decode(raw)))
            }
        }
    }
}
