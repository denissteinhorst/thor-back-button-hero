package dev.steinhorst.backbuttonhero

import dev.steinhorst.backbuttonhero.prefs.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AynTest {
    private fun cmd(group: Ayn.Group, key: String) = group.options.first { it.key == key }.commands

    @Test
    fun `one action per tile, all known to the backup and none an app package`() {
        val all = Ayn.Group.values().map { it.action } + Ayn.Toggle.values().map { it.action }
        assertEquals(all.size, all.toSet().size)
        assertEquals(Ayn.ACTIONS, all.toSet())
        assertTrue(Prefs.KNOWN_ACTIONS.containsAll(all))
        assertTrue(all.none { Prefs.isShoulderApp(it) })
    }

    @Test
    fun `lookups resolve each kind of action`() {
        assertEquals(Ayn.Group.FAN, Ayn.groupOf("@fan"))
        assertEquals(Ayn.Toggle.VIBRATION, Ayn.toggleOf("@vibration"))
        assertNull(Ayn.groupOf("@vibration"))
        assertNull(Ayn.toggleOf("@home"))
    }

    @Test
    fun `options are valid per group, the modal only where there is one`() {
        assertTrue(Ayn.Group.FAN.isValid(Ayn.MODAL))
        assertFalse(Ayn.Group.DISPLAY.isValid(Ayn.MODAL))
        assertFalse(Ayn.Group.FAN.isValid("top"))
        assertFalse(Ayn.Group.DISPLAY.isValid("both"))
        assertEquals(Ayn.MODAL, Ayn.Group.PERFORMANCE.defaultOption)
        assertEquals("top", Ayn.Group.DISPLAY.defaultOption)
        assertTrue(Ayn.OPTION_KEYS.containsAll(listOf("modal", "smart", "xbox", "bottom")))
    }

    @Test
    fun `option commands write the values the AYN assistant writes`() {
        assertEquals("settings put system performance_mode 2", cmd(Ayn.Group.PERFORMANCE, "high"))
        assertEquals("settings put system fan_mode 0", cmd(Ayn.Group.FAN, "off"))
        assertEquals("settings put system fan_mode 4", cmd(Ayn.Group.FAN, "smart"))
        assertTrue(cmd(Ayn.Group.HANDLE, "xbox").endsWith("setprop persist.sys.gamepad.type 1"))
        assertTrue(cmd(Ayn.Group.HANDLE, "ban").contains("no_create_gamepad_button_layout 1"))
    }

    @Test
    fun `commands fit what pservice accepts`() {
        assertTrue(Ayn.Group.values().flatMap { it.options }.all { it.commands.length < 250 })
        listOf(Ayn.MODE_DUAL, Ayn.MODE_TOP, Ayn.MODE_BOTTOM).forEach { assertTrue(Ayn.displayCommands(it).length < 300) }
    }

    @Test
    fun `flipping a screen never leaves both dark`() {
        val d = Ayn.MODE_DUAL; val t = Ayn.MODE_TOP; val b = Ayn.MODE_BOTTOM
        assertEquals(b, Ayn.displayTarget("top", d))
        assertEquals(d, Ayn.displayTarget("top", b))
        assertEquals(d, Ayn.displayTarget("bottom", t))
        assertEquals(t, Ayn.displayTarget("bottom", d))
        // Turning off the only lit screen lights the other instead.
        assertEquals(b, Ayn.displayTarget("top", t))
        assertEquals(t, Ayn.displayTarget("bottom", b))
    }

    @Test
    fun `screen modes write the mode and the matching controller route`() {
        assertTrue(Ayn.displayCommands(Ayn.MODE_DUAL).startsWith("settings put system dual_screen_display_mode 0 && settings put system enable_system_key_focus_lock 0"))
        assertTrue(Ayn.displayCommands(Ayn.MODE_TOP).let { it.contains("dual_screen_display_mode 1") && it.contains("screen_focus_lock 1") })
        assertTrue(Ayn.displayCommands(Ayn.MODE_BOTTOM).let { it.contains("dual_screen_display_mode 2") && it.contains("persist.sys.input.dispaly 2") })
    }

    @Test
    fun `toggle command writes zero or one`() {
        assertEquals("settings put system vibrate_on 1", Ayn.toggleCommand(Ayn.Toggle.VIBRATION, true))
        assertEquals("settings put system is_charging_separation 0", Ayn.toggleCommand(Ayn.Toggle.DIRECT_POWER, false))
    }

    @Test
    fun `refresh rate writes minimum and peak together, as AYN's tile does`() {
        assertEquals("settings put system min_refresh_rate 120.0 && settings put system peak_refresh_rate 120.0", cmd(Ayn.Group.REFRESH_RATE, "120"))
        assertEquals("settings put system min_refresh_rate 60.0 && settings put system peak_refresh_rate 60.0", cmd(Ayn.Group.REFRESH_RATE, "60"))
        assertEquals(Ayn.REFRESH_TOGGLE, Ayn.Group.REFRESH_RATE.defaultOption)
    }

    @Test
    fun `refresh toggle flips between the two rates`() {
        assertEquals(120, Ayn.refreshTarget(60f))
        assertEquals(60, Ayn.refreshTarget(120f))
        assertEquals(60, Ayn.refreshTarget(240f))
    }

    @Test
    fun `stick lights flip one side alone and both together`() {
        assertEquals(true to false, Ayn.lightTarget(Ayn.STICKS_LEFT, "0,0"))
        assertEquals(false to true, Ayn.lightTarget(Ayn.STICKS_RIGHT, "0,0"))
        assertEquals(false to true, Ayn.lightTarget(Ayn.STICKS_LEFT, "1,1"))
        // Both: any lit stick means both go dark; only an all-dark pair lights up.
        assertEquals(false to false, Ayn.lightTarget(Ayn.STICKS_BOTH, "1,0"))
        assertEquals(true to true, Ayn.lightTarget(Ayn.STICKS_BOTH, "0,0"))
        assertEquals(true to true, Ayn.lightTarget(Ayn.STICKS_BOTH, null))
        assertEquals(Ayn.STICKS_BOTH, Ayn.Group.STICK_LIGHT.defaultOption)
        assertEquals("settings put system joystick_light_enabled 1,0", Ayn.lightCommand(true to false))
    }

    @Test
    fun `Do Not Disturb and the microphone go through Android's services`() {
        assertEquals("cmd notification set_dnd priority", Ayn.toggleCommand(Ayn.Toggle.DO_NOT_DISTURB, true))
        assertEquals("cmd notification set_dnd off", Ayn.toggleCommand(Ayn.Toggle.DO_NOT_DISTURB, false))
        assertTrue(Ayn.toggleCommand(Ayn.Toggle.MUTE_MICROPHONE, true).startsWith("cmd sensor_privacy enable "))
        assertTrue(Ayn.toggleCommand(Ayn.Toggle.MUTE_MICROPHONE, false).endsWith(" microphone"))
        assertEquals("settings put system global_gamepad_to_mouse_mode 1", Ayn.toggleCommand(Ayn.Toggle.VIRTUAL_MOUSE, true))
    }

    @Test
    fun `microphone state is read from the sensor privacy dump`() {
        fun dump(sensor: Int, state: Int) = """
            SENSOR PRIVACY MANAGER STATE (dumpsys sensor_privacy)
            {
              users={
                user_id=0
                sensors={
                  sensor=$sensor
                  toggles={
                    toggle_type=1
                    state_type=$state
                    last_change=26368671
                  }
                }
              }
            }
        """.trimIndent()
        assertTrue(Ayn.microphoneBlocked(dump(1, 1)))
        assertFalse(Ayn.microphoneBlocked(dump(1, 2)))
        // The camera (sensor 2) says nothing about the microphone.
        assertFalse(Ayn.microphoneBlocked(dump(2, 1)))
        assertFalse(Ayn.microphoneBlocked("{\n  storage_implementation=x\n}"))
        assertFalse(Ayn.microphoneBlocked(null))
        // pservice hands back one line, so the command strips all whitespace; the parser must cope.
        assertTrue(Ayn.microphoneBlocked(dump(1, 1).filterNot(Char::isWhitespace)))
        assertFalse(Ayn.microphoneBlocked(dump(10, 1).filterNot(Char::isWhitespace)))
        assertTrue(Ayn.MICROPHONE_STATE_COMMAND.contains("tr -d"))
    }
}
