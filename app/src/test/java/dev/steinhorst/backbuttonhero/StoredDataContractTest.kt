package dev.steinhorst.backbuttonhero

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.prefs.AppException
import dev.steinhorst.backbuttonhero.prefs.BindingLayer
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.prefs.SettingsBackup
import dev.steinhorst.backbuttonhero.prefs.SettingsChangeAction
import dev.steinhorst.backbuttonhero.prefs.SettingsChangePlan
import dev.steinhorst.backbuttonhero.prefs.SettingsState
import dev.steinhorst.backbuttonhero.prefs.StickTimings
import java.io.File
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Freezes what the app stores, byte for byte: the raw preference map after a fresh install, after
 * migrating synthesized older stores, for a fully populated configuration, after every settings plan
 * and after dual-mode seeding, plus the backup file. The fixtures in `src/test/resources/storage/`
 * were recorded once on the original production code (refactoring baseline a032d080db33) and are
 * frozen; a difference means stored data changed. The older stores are synthesized from the
 * migration code, not exports of real installs.
 *
 * Set UPDATE_STORAGE_FIXTURES=1 to re-record, only for an explicitly approved stored-data change.
 */
@RunWith(RobolectricTestRunner::class)
class StoredDataContractTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val raw: SharedPreferences get() = context.getSharedPreferences("back_button_hero", Context.MODE_PRIVATE)

    @Before
    fun clear() {
        raw.edit().clear().commit()
    }

    /** Every stored entry as `key<TAB>type<TAB>value`, sorted; string sets sorted inside. */
    private fun dump(): String = raw.all.toSortedMap().entries.joinToString("") { (key, value) ->
        val (type, text) = when (value) {
            is String -> "string" to value
            is Int -> "int" to value.toString()
            is Long -> "long" to value.toString()
            is Boolean -> "boolean" to value.toString()
            is Float -> "float" to value.toString()
            is Set<*> -> "set" to value.map { it.toString() }.sorted().joinToString(",")
            else -> "unknown" to value.toString()
        }
        "$key\t$type\t${text.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")}\n"
    }

    private fun verify(name: String, actual: String) {
        val file = File("src/test/resources/storage/$name")
        if (System.getenv("UPDATE_STORAGE_FIXTURES") == "1") {
            file.parentFile?.mkdirs()
            file.writeText(actual)
            return
        }
        if (!file.exists()) fail("Storage fixture $file is missing; it is frozen evidence and is never regenerated to pass.")
        assertEquals("stored data differs from the frozen fixture $name", file.readText(), actual)
    }

    private val macro = Macro(listOf(MacroStep(listOf(MacroInput.Button(MacroButton.A)), 80, 120)))

    /** Every binding assigned, cycling through every stored action and an app, with non-default options. */
    private fun populated(base: SettingsState): SettingsState {
        val actions = (Prefs.KNOWN_ACTIONS.sorted() + "com.example.game")
        val bindings = base.bindings.toSortedMap().entries.mapIndexed { i, (key, old) ->
            key to old.copy(
                action = actions[i % actions.size],
                display = Prefs.DISPLAY_TOP,
                targetOverride = Prefs.TARGET_BOTH,
                focusTarget = Prefs.FOCUS_MODAL,
                indicator = false,
                duration = 700L,
                position = Prefs.FOCUS_POS_TOP_LEFT,
                appMode = Prefs.APP_RESTART,
                appScreen = Prefs.DISPLAY_TOP,
                appConfirm = true,
                switchAppFollow = false,
                closeConfirm = false,
                standbyConfirm = true,
                recordMic = true,
                recordQuality = Prefs.RECORD_LARGE,
                recordIndicator = false,
                recordPosition = Prefs.FOCUS_POS_BOTTOM_LEFT,
                recordPtt = true,
                recordPttKey = 96,
                recordDuck = Prefs.DUCK_DEVICE,
            )
        }.toMap()
        return base.copy(
            doubleMs = 450L, tapHoldMs = 1200L, holdMs = 1500L, allApps = true, controller = false,
            toggleButtons = Prefs.TOGGLE_START_SELECT, toggleMs = 900L, bindings = bindings,
            exceptions = listOf(AppException("com.example.game", "Game", allowed = 5L), AppException("org.example.emu", "Emu", active = false)),
            stickTimings = base.stickTimings.mapValues { StickTimings(350L, 1100L) },
            macros = listOf(SavedMacro("m1", "Tap A", macro)),
            homeDoubleMs = 320L, homeTapHoldMs = 1300L, homeHoldMs = 1400L,
            homeStickTimings = base.homeStickTimings.mapValues { StickTimings(360L, 1150L) },
        )
    }

    private fun populate(prefs: Prefs): SettingsState {
        val before = prefs.snapshot()
        val after = populated(before)
        assertTrue(prefs.applySettings(before, after))
        return after
    }

    @Test
    fun `a fresh install stores exactly the frozen entries and a second construction changes nothing`() {
        Prefs(context)
        val fresh = dump()
        verify("fresh.txt", fresh)
        Prefs(context)
        assertEquals(fresh, dump())
    }

    @Test
    fun `a synthesized pre-1-1 store migrates to the frozen entries, once`() {
        raw.edit()
            .putString("single_action", "back")
            .putBoolean("double_enabled", false)
            .putBoolean("tap_hold_enabled", true)
            .putString("toggle_buttons", "l3")
            .putLong("toggle_hold_ms", 1700L)
            .putString("${ShoulderButton.R2.prefKey}_package", "@swap_screens")
            .putString("app_exceptions", "com.example.game\t15\tGame\ttrue\nnot a valid line")
            .putLong("hold_ms", 1300L)
            .commit()
        Prefs(context)
        val migrated = dump()
        verify("legacy-v1.txt", migrated)
        Prefs(context)
        assertEquals(migrated, dump())
    }

    @Test
    fun `a store with some migrations done keeps them and runs only the rest`() {
        raw.edit()
            .putBoolean("navigation_flick_defaults_v1", true)
            .putString("${ShoulderButton.L1.prefKey}_package", Prefs.SHOULDER_FLICK_LEFT)
            .putString("${ShoulderButton.R1.prefKey}_package", Prefs.SHOULDER_FLICK_RIGHT)
            .putBoolean("back_gestures_v1", true)
            .putString("${BackGestureButton.SINGLE.prefKey}_package", "")
            .putString("${DpadDirection.UP.prefKey}_package", "")
            .putBoolean("exception_sticks_v2", true)
            .putString("app_exceptions", "com.example.game\t3\tGame\tfalse")
            .putString("gyro_calibration_v1", "device calibration data")
            .commit()
        Prefs(context)
        verify("mixed-flags.txt", dump())
    }

    @Test
    fun `a fully populated configuration stores exactly the frozen entries and reads back`() {
        val prefs = Prefs(context)
        val after = populate(prefs)
        verify("populated.txt", dump())
        assertEquals(after, Prefs(context).snapshot())
    }

    @Test
    fun `every page and whole-app plan stores exactly the frozen entries`() {
        val out = StringBuilder()
        val plans = SettingsSection.entries.map<SettingsSection, SettingsSection?> { it } + null
        for (action in listOf(SettingsChangeAction.PRESET, SettingsChangeAction.UNBIND)) {
            for (section in plans) {
                for (layers in listOf(setOf(BindingLayer.BACK), setOf(BindingLayer.HOME), setOf(BindingLayer.BACK, BindingLayer.HOME))) {
                    clear()
                    val prefs = Prefs(context)
                    raw.edit().putString("gyro_calibration_v1", "device calibration data").putBoolean("welcome_v2_seen", true).commit()
                    val before = populate(prefs)
                    val plan = SettingsChangePlan.create(before, section, action, layers)
                    assertTrue(prefs.applySettings(plan.before, plan.after))
                    out.append("## $action ${section ?: "WHOLE_APP"} ${layers.sorted().joinToString("+")}\n").append(dump())
                }
            }
        }
        clear()
        val prefs = Prefs(context)
        val before = populate(prefs)
        val plan = SettingsChangePlan.create(before, null, SettingsChangeAction.DEFAULTS)
        assertTrue(prefs.applySettings(plan.before, plan.after))
        out.append("## DEFAULTS WHOLE_APP\n").append(dump())
        verify("plans.txt", out.toString())
    }

    @Test
    fun `the first dual-mode activation seeds the Home layer into the frozen entries`() {
        val prefs = Prefs(context)
        populate(prefs)
        val before = prefs.snapshot()
        val after = before.seededHomeLayer().copy(dualMode = true)
        assertTrue(prefs.applySettings(before, after))
        verify("dual-seeded.txt", dump())
    }

    @Test
    fun `backups are byte-identical at a fixed time and read back to the exported settings`() {
        val prefs = Prefs(context)
        val now = Instant.parse("2026-01-02T03:04:05Z")
        val fresh = prefs.snapshot()
        verify("backup-fresh.json", SettingsBackup.write(fresh, "2.0.0", 11L, now))
        val after = populate(prefs)
        val text = SettingsBackup.write(after, "2.0.0", 11L, now)
        verify("backup-populated.json", text)
        assertEquals("back-button-hero-backup_v2.0.0_2026-01-02.json", SettingsBackup.fileName("2.0.0", LocalDate.of(2026, 1, 2)))
        val read = SettingsBackup.read(text)
        assertTrue("the backup must be accepted: $read", read is SettingsBackup.Result.Loaded)
        assertEquals(after, (read as SettingsBackup.Result.Loaded).state)
    }

    @Test
    fun `device calibration and the welcome state never enter a backup and survive every reset`() {
        val prefs = Prefs(context)
        raw.edit().putString("gyro_calibration_v1", "CALIBRATION-MARKER").putBoolean("welcome_v2_seen", true).commit()
        val after = populate(prefs)
        assertFalse(SettingsBackup.write(after, "2.0.0", 11L, Instant.EPOCH).contains("CALIBRATION-MARKER"))
        val plan = SettingsChangePlan.create(after, null, SettingsChangeAction.PRESET, setOf(BindingLayer.BACK, BindingLayer.HOME))
        assertTrue(prefs.applySettings(plan.before, plan.after))
        assertEquals("CALIBRATION-MARKER", raw.getString("gyro_calibration_v1", null))
        assertTrue(raw.getBoolean("welcome_v2_seen", false))
    }

    @Test
    fun `a stale preview writes nothing and a no-op plan succeeds without writing`() {
        val prefs = Prefs(context)
        val before = prefs.snapshot()
        val stored = dump()
        assertFalse(prefs.applySettings(before.copy(holdMs = before.holdMs + 1), populated(before)))
        assertEquals(stored, dump())
        assertTrue(prefs.applySettings(before, before))
        assertEquals(stored, dump())
    }

    @Test
    fun `one accepted plan is one write that every listener sees complete, from another instance too`() {
        val writer = Prefs(context)
        val observer = Prefs(context)
        val before = writer.snapshot()
        val after = populated(before)
        val seen = mutableListOf<SettingsState>()
        val listener = { seen += observer.snapshot() }
        observer.registerListener(listener)
        assertTrue(writer.applySettings(before, after))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue("listeners must fire", seen.isNotEmpty())
        seen.forEach { assertEquals("a listener saw a partial configuration", after, it) }
        assertEquals(after, observer.snapshot())
        observer.unregisterListener(listener)
        val count = seen.size
        assertTrue(writer.applySettings(after, before))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals("an unregistered listener must not fire", count, seen.size)
    }

    @Test
    fun `direct writes, unknown raw data, both Home modes and macro deletion store exactly the frozen entries`() {
        val out = StringBuilder()
        raw.edit()
            .putString("unknown_future_key", "kept")
            .putInt("unknown_future_int", 7)
            .putStringSet("unknown_future_set", setOf("b", "a"))
            .putString("${ShoulderButton.L2.prefKey}_package", "@not_an_action")
            .putString("${ShoulderButton.R2.prefKey}_package", "")
            .commit()
        val prefs = Prefs(context)
        out.append("## constructed over unknown data\n").append(dump())
        val state = prefs.snapshot()
        out.append("## read without write\n").append(dump())
        out.append("## effective state\n").append(state.toString()).append("\n")
        assertTrue(prefs.applySettings(state, state.copy(holdMs = 1500L)))
        out.append("## unrelated write\n").append(dump())
        for (homeAsBack in listOf(true, false)) {
            val before = prefs.snapshot()
            assertTrue(prefs.applySettings(before, before.copy(homeAsBack = homeAsBack)))
            out.append("## homeAsBack $homeAsBack\n").append(dump())
        }
        val first = prefs.saveMacro(null, "  First  ", macro)
        val second = prefs.saveMacro(null, "", macro)
        prefs.saveMacro(first.id, "First renamed", macro)
        prefs.renameMacro(second.id, "Second")
        prefs.setMacroRef(ShoulderButton.L1, first.id)
        prefs.setMacroRef(ShoulderButton.R1, first.id)
        prefs.setMacroRef(ShoulderButton.L2, second.id)
        out.append("## macros saved and referenced\n").append(dump())
        prefs.deleteMacro(first.id)
        out.append("## referenced macro deleted\n").append(dump())
        prefs.setMacroRef(ShoulderButton.L2, null)
        out.append("## reference cleared\n").append(dump())
        verify("edge-cases.txt", out.toString())
    }

    @Test
    fun `a stored value of the wrong type fails exactly as before`() {
        val out = StringBuilder()
        for ((key, put) in listOf<Pair<String, (SharedPreferences.Editor) -> Unit>>(
            "hold_ms" to { it.putInt("hold_ms", 5) },
            "${ShoulderButton.L1.prefKey}_package" to { it.putBoolean("${ShoulderButton.L1.prefKey}_package", true) },
            "back_gestures_v1" to { it.putString("back_gestures_v1", "true") },
        )) {
            clear()
            Prefs(context)
            raw.edit().also(put).commit()
            val outcome = try {
                Prefs(context).snapshot()
                "snapshot ok"
            } catch (e: RuntimeException) {
                e.javaClass.name
            }
            out.append("$key\t$outcome\n")
        }
        verify("wrong-types.txt", out.toString())
    }
}
