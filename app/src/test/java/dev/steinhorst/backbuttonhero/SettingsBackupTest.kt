package dev.steinhorst.backbuttonhero

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class SettingsBackupTest {
    private fun customized(): SettingsState = SettingsState().let { factory ->
        factory.copy(
            doubleMs = 450, tapHoldMs = 1_400, holdMs = 1_700, allApps = true, controller = false,
            toggleButtons = Prefs.TOGGLE_L1_R1, toggleMs = 1_300,
            bindings = factory.bindings.mapValues { (key, value) ->
                when (key) {
                    "back_double" -> value.copy(action = Prefs.SHOULDER_SWITCHER)
                    "l1" -> value.copy(action = "com.example.game", display = Prefs.DISPLAY_TOP)
                    "dpad_up" -> value.copy(action = Prefs.SHOULDER_BRIGHTNESS_UP, targetOverride = Prefs.DISPLAY_BOTTOM)
                    "r2" -> value.copy(action = Prefs.SHOULDER_CONTROLLER_FOCUS, focusTarget = Prefs.DISPLAY_TOP,
                        indicator = false, duration = 900, position = Prefs.FOCUS_POS_TOP_LEFT)
                    "l2" -> value.copy(action = Ayn.Group.FAN.action, aynOption = "smart")
                    "lstick_press" -> value.copy(action = Prefs.SHOULDER_APP_CONTROL, appMode = Prefs.APP_FORCE_CLOSE,
                        appScreen = Prefs.DISPLAY_TOP, appConfirm = true)
                    else -> value
                }
            },
            stickTimings = mapOf("lstick" to StickTimings(200, 600), "rstick" to StickTimings(500, 1_900)),
            exceptions = listOf(AppException("com.example.game", "Example Game", 0b1010, true),
                AppException("com.example.off", "Off", ExceptionControl.ALL, false)),
        )
    }

    private fun loaded(text: String) = (SettingsBackup.read(text) as SettingsBackup.Result.Loaded)

    @Test
    fun `everything survives a round trip`() {
        val state = customized()
        val text = SettingsBackup.write(state, "1.5.0", 15, Instant.parse("2026-11-14T10:00:00Z"))
        val result = loaded(text)
        assertEquals(state, result.state)
        assertEquals(SettingsBackup.Info("1.5.0", SettingsBackup.FORMAT_VERSION, "2026-11-14T10:00:00Z"), result.info)
    }

    @Test
    fun `an unknown AYN option in a backup is dropped`() {
        val text = """{"format":"${SettingsBackup.FORMAT}","settings":{"bindings":{"l1":{"action":"@fan","aynOption":"turbo"}}}}"""
        assertEquals(null, loaded(text).state.bindings.getValue("l1").aynOption)
    }

    @Test
    fun `stick columns round trip, and an older backup keeps the sticks on`() {
        val state = SettingsState(exceptions = listOf(AppException("com.a", "A", ExceptionControl.BEFORE_STICKS or ExceptionControl.RIGHT_STICK_UP.bit or ExceptionControl.LEFT_STICK_TAP_HOLD.bit)))
        val back = (SettingsBackup.read(SettingsBackup.write(state, "1.5.0", 15)) as SettingsBackup.Result.Loaded).state
        assertEquals(state.exceptions, back.exceptions)
        val old = """{"format":"${SettingsBackup.FORMAT}","settings":{"exceptions":[{"package":"com.a","allowed":5}]}}"""
        assertEquals(5 or (ExceptionControl.STICK_BITS shl 12), loaded(old).state.exceptions.single().allowed)
    }

    @Test
    fun `the factory state round trips too, and writing is stable`() {
        val text = SettingsBackup.write(SettingsState(), "1.5.0", 15)
        assertEquals(SettingsState(), loaded(text).state)
        val a = SettingsBackup.write(customized(), "1.5.0", 15, Instant.EPOCH)
        assertEquals(a, SettingsBackup.write(loaded(a).state, "1.5.0", 15, Instant.EPOCH))
    }

    @Test
    fun `the file name carries the app version and the date`() {
        assertEquals("back-button-hero-backup_v1.5.0_2026-11-14.json", SettingsBackup.fileName("1.5.0", LocalDate.of(2026, 11, 14)))
        assertEquals("back-button-hero-backup_v1.5.0-beta_2026-01-02.json", SettingsBackup.fileName("1.5.0-beta", LocalDate.of(2026, 1, 2)))
        assertEquals("back-button-hero-backup_vunknown_2026-01-02.json", SettingsBackup.fileName("../?*", LocalDate.of(2026, 1, 2)))
        assertTrue(SettingsBackup.fileName("1.5.0").matches(Regex("back-button-hero-backup_v1\\.5\\.0_\\d{4}-\\d{2}-\\d{2}\\.json")))
    }

    @Test
    fun `anything that is not a backup is refused`() {
        listOf("", "   ", "not json", "[1,2]", "{", "null").forEach {
            val result = SettingsBackup.read(it)
            assertTrue(it, result is SettingsBackup.Result.Rejected)
        }
        assertEquals(SettingsBackup.Rejection.NOT_JSON, (SettingsBackup.read("nope") as SettingsBackup.Result.Rejected).reason)
        assertEquals(SettingsBackup.Rejection.NOT_A_BACKUP, (SettingsBackup.read("{}") as SettingsBackup.Result.Rejected).reason)
        assertEquals(SettingsBackup.Rejection.NOT_A_BACKUP, (SettingsBackup.read("""{"format":"something else"}""") as SettingsBackup.Result.Rejected).reason)
        assertEquals(SettingsBackup.Rejection.NOT_A_BACKUP, (SettingsBackup.read("""{"format":5}""") as SettingsBackup.Result.Rejected).reason)
        assertEquals(SettingsBackup.Rejection.TOO_LARGE,
            (SettingsBackup.read(" ".repeat(SettingsBackup.MAX_BYTES + 1)) as SettingsBackup.Result.Rejected).reason)
    }

    @Test
    fun `a backup with nothing in it is the factory state`() {
        assertEquals(SettingsState(), loaded("""{"format":"${SettingsBackup.FORMAT}"}""").state)
        assertEquals(SettingsState(), loaded("""{"format":"${SettingsBackup.FORMAT}","settings":{}}""").state)
        assertEquals(SettingsState(), loaded("""{"format":"${SettingsBackup.FORMAT}","settings":5,"app":"x","formatVersion":"2"}""").state)
    }

    @Test
    fun `unknown fields are ignored and missing ones keep their factory value`() {
        val text = """{"format":"${SettingsBackup.FORMAT}","formatVersion":1,"futureThing":{"a":1},
            "settings":{"extra":true,"backTimings":{"doubleMs":400,"surprise":1},
            "bindings":{"l1":{"action":"@home","newOption":"x"},"not_a_button":{"action":"@home"}}}}"""
        val state = loaded(text).state
        assertEquals(400L, state.doubleMs)
        assertEquals(SettingsState().tapHoldMs, state.tapHoldMs)
        assertEquals(Prefs.SHOULDER_HOME, state.bindings.getValue("l1").action)
        assertEquals(SettingsState().bindings.getValue("r1"), state.bindings.getValue("r1"))
        assertFalse(state.bindings.containsKey("not_a_button"))
    }

    @Test
    fun `invalid values are replaced or clamped one by one and never fail the import`() {
        val text = """{"format":"${SettingsBackup.FORMAT}","settings":{
            "backTimings":{"doubleMs":99999,"tapHoldMs":-5,"holdMs":"1000"},
            "switcher":{"showsAllApps":"yes","controllerNavigation":1},
            "activationShortcut":{"buttons":"x_y","holdMs":1},
            "stickTimings":{"lstick":{"doubleMs":0,"tapHoldMs":1e9},"rstick":"broken"},
            "bindings":{
              "l1":{"action":"@does_not_exist","display":"middle","target":"nowhere","focusTarget":7,
                    "focusIndicator":"no","focusDurationMs":5,"focusPosition":"up","appMode":"vaporize",
                    "appScreen":"all","appConfirm":"true"},
              "l2":{"action":"rm -rf /; evil"},
              "r1":{"action":"com.valid.package","focusDurationMs":99999},
              "r2":"nonsense",
              "dpad_up":null},
            "exceptions":[{"package":"bad name","name":"x"},{"package":"com.ok.app"},{"name":"no package"},5,null,
                          {"package":"com.ok.app","name":"duplicate"},
                          {"package":"com.two.app","name":"  Two\u0000\n ","allowed":999999,"active":"maybe"}]}}"""
        val state = loaded(text).state
        val factory = SettingsState()
        assertEquals(600L, state.doubleMs)
        assertEquals(500L, state.tapHoldMs)
        assertEquals(factory.holdMs, state.holdMs)
        assertEquals(factory.allApps, state.allApps)
        assertEquals(factory.controller, state.controller)
        assertEquals(factory.toggleButtons, state.toggleButtons)
        assertEquals(100L, state.toggleMs)
        assertEquals(StickTimings(150, 2_000), state.stickTimings.getValue("lstick"))
        assertEquals(factory.stickTimings.getValue("rstick"), state.stickTimings.getValue("rstick"))
        assertEquals(factory.bindings.getValue("l1"), state.bindings.getValue("l1").copy(duration = factory.bindings.getValue("l1").duration))
        assertEquals(Prefs.MIN_FOCUS_DURATION_MS, state.bindings.getValue("l1").duration)
        assertEquals(factory.bindings.getValue("l2"), state.bindings.getValue("l2"))
        assertEquals("com.valid.package", state.bindings.getValue("r1").action)
        assertEquals(Prefs.MAX_FOCUS_DURATION_MS, state.bindings.getValue("r1").duration)
        assertEquals(factory.bindings.getValue("r2"), state.bindings.getValue("r2"))
        assertEquals(factory.bindings.getValue("dpad_up"), state.bindings.getValue("dpad_up"))
        assertEquals(listOf("com.ok.app", "com.two.app"), state.exceptions.map { it.packageName })
        assertEquals("com.ok.app", state.exceptions[0].name)
        assertEquals("Two", state.exceptions[1].name)
        // Undefined bits are dropped, like when the app reads its own stored exceptions; a backup
        // from before the sticks has no stick field and keeps their shortcuts on.
        assertEquals((999999 and ExceptionControl.BEFORE_STICKS) or (ExceptionControl.STICK_BITS shl 12), state.exceptions[1].allowed)
        assertTrue(state.exceptions[1].active)
        // Whatever came out is a state the app can store as it is.
        assertNotNull(SettingsBackup.write(state, "1.5.0", 15))
    }

    @Test
    fun `a huge exception list is capped`() {
        val entries = (0 until 3_000).joinToString(",") { """{"package":"com.app$it"}""" }
        val state = loaded("""{"format":"${SettingsBackup.FORMAT}","settings":{"exceptions":[$entries]}}""").state
        assertEquals(1_000, state.exceptions.size)
    }

    @Test
    fun `every action the app offers is accepted and a stored package is too`() {
        val bindings = Prefs.KNOWN_ACTIONS.mapIndexed { i, action ->
            val key = SettingsState().bindings.keys.sorted()[i % SettingsState().bindings.size]
            key to action
        }
        bindings.forEach { (key, action) ->
            val text = """{"format":"${SettingsBackup.FORMAT}","settings":{"bindings":{"$key":{"action":"$action"}}}}"""
            assertEquals(action, loaded(text).state.bindings.getValue(key).action)
        }
    }

    @Test
    fun `the known actions list covers every shoulder action constant`() {
        val constants = Prefs.Companion::class.java.declaredFields.plus(Prefs::class.java.declaredFields)
            .filter { it.name.startsWith("SHOULDER_") && it.type == String::class.java }
            .mapNotNull { runCatching { it.isAccessible = true; it.get(if (java.lang.reflect.Modifier.isStatic(it.modifiers)) null else Prefs.Companion) as String }.getOrNull() }
        val all = Prefs::class.java.declaredFields.filter { it.name.startsWith("SHOULDER_") }
            .map { it.apply { isAccessible = true }.get(null) as String }
        assertTrue(all.isNotEmpty())
        assertEquals(emptyList<String>(), (all + constants).filter { it !in Prefs.KNOWN_ACTIONS })
    }

    @Test
    fun `backups made by other versions are told apart`() {
        fun info(version: String?, format: Int = SettingsBackup.FORMAT_VERSION) = SettingsBackup.Info(version, format, null)
        val c = SettingsBackup::compatibility
        assertEquals(SettingsBackup.Compatibility.SAME, c(info("1.5.0"), "1.5.0"))
        assertEquals(SettingsBackup.Compatibility.SAME, c(info("v1.5"), "1.5.0"))
        assertEquals(SettingsBackup.Compatibility.OLDER, c(info("1.4.9"), "1.5.0"))
        assertEquals(SettingsBackup.Compatibility.OLDER, c(info("0.9.0"), "1.0.0"))
        assertEquals(SettingsBackup.Compatibility.NEWER, c(info("1.6.0"), "1.5.0"))
        assertEquals(SettingsBackup.Compatibility.NEWER, c(info("2.0.0"), "1.9.9"))
        assertEquals(SettingsBackup.Compatibility.NEWER, c(info("1.0.0", SettingsBackup.FORMAT_VERSION + 1), "1.5.0"))
        assertEquals(SettingsBackup.Compatibility.UNKNOWN, c(info(null), "1.5.0"))
        assertEquals(SettingsBackup.Compatibility.UNKNOWN, c(info("garbage"), "1.5.0"))
        assertEquals(SettingsBackup.Compatibility.UNKNOWN, c(info("1.5.0"), "garbage"))
    }

    @Test
    fun `the written file is plain JSON with the format marker, version and date up front`() {
        val json = JSONObject(SettingsBackup.write(customized(), "1.5.0", 15, Instant.parse("2026-11-14T10:00:00Z")))
        assertEquals(SettingsBackup.FORMAT, json.getString("format"))
        assertEquals(1, json.getInt("formatVersion"))
        assertEquals("1.5.0", json.getJSONObject("app").getString("version"))
        assertEquals(15, json.getJSONObject("app").getInt("versionCode"))
        assertEquals("2026-11-14T10:00:00Z", json.getString("createdAt"))
        assertTrue(json.getJSONObject("settings").getJSONObject("bindings").has("back_single"))
        assertTrue(json.getJSONObject("settings").has("exceptions"))
    }
}
