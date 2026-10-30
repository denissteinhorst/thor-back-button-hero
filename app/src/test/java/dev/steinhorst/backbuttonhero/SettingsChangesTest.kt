package dev.steinhorst.backbuttonhero

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SettingsChangesTest {
    private val prefs = Prefs(ApplicationProvider.getApplicationContext())

    private fun customized(): SettingsState = SettingsState().let { factory ->
        factory.copy(
            singleAction = Prefs.SINGLE_BACK, doubleEnabled = false, doubleMs = 550,
            tapHoldEnabled = false, tapHoldMs = 1700, holdEnabled = false, holdMs = 1900,
            allApps = true, controller = false, toggleButtons = Prefs.TOGGLE_L1_R1, toggleMs = 1300,
            bindings = factory.bindings.mapValues { (_, value) -> value.copy(
                action = "com.example.game", display = Prefs.DISPLAY_TOP,
                targetOverride = Prefs.DISPLAY_BOTTOM, focusTarget = Prefs.DISPLAY_BOTTOM,
                indicator = false, duration = 900, position = Prefs.FOCUS_POS_TOP_LEFT,
            ) },
        )
    }

    @Test
    fun `factory plan agrees with fresh installs and existing full reset`() {
        assertEquals(SettingsState(), prefs.snapshot())
        assertTrue(prefs.applySettings(prefs.snapshot(), customized()))
        prefs.resetToDefaults()
        assertEquals(SettingsState(), prefs.snapshot())
    }

    @Test
    fun `all page presets change only their own values and restore every auxiliary field`() {
        val before = customized()
        val factory = SettingsState()
        SettingsSection.entries.forEach { section ->
            val plan = SettingsChangePlan.create(before, section, SettingsChangeAction.PRESET)
            val after = plan.after
            when (section) {
                SettingsSection.BASIC -> assertEquals(before.copy(
                    singleAction = factory.singleAction, doubleEnabled = factory.doubleEnabled,
                    doubleMs = factory.doubleMs, tapHoldEnabled = factory.tapHoldEnabled,
                    tapHoldMs = factory.tapHoldMs, holdEnabled = factory.holdEnabled, holdMs = factory.holdMs,
                ), after)
                SettingsSection.SHOULDERS, SettingsSection.DPAD -> {
                    val keys = if (section == SettingsSection.SHOULDERS) ShoulderButton.entries.map { it.prefKey }
                    else DpadDirection.entries.map { it.prefKey }
                    assertEquals(before.copy(bindings = before.bindings.mapValues { (key, value) ->
                        if (key in keys) factory.bindings.getValue(key) else value
                    }), after)
                }
                SettingsSection.SETTINGS -> assertEquals(before.copy(
                    allApps = factory.allApps, controller = factory.controller,
                    toggleButtons = factory.toggleButtons, toggleMs = factory.toggleMs,
                ), after)
                SettingsSection.HELP, SettingsSection.EXCEPTIONS -> assertTrue(plan.isEmpty)
            }
            assertTrue(prefs.applySettings(prefs.snapshot(), before))
            assertTrue(prefs.applySettings(plan.before, after))
            assertEquals(after, prefs.snapshot())
        }
    }

    @Test
    fun `all page unbinds preserve timings targets and other pages`() {
        val before = customized()
        SettingsSection.entries.forEach { section ->
            val plan = SettingsChangePlan.create(before, section, SettingsChangeAction.UNBIND)
            val keys = when (section) {
                SettingsSection.SHOULDERS -> ShoulderButton.entries.map { it.prefKey }
                SettingsSection.DPAD -> DpadDirection.entries.map { it.prefKey }
                else -> emptyList()
            }
            val expected = when (section) {
                SettingsSection.BASIC -> before.copy(singleAction = Prefs.SINGLE_BACK,
                    doubleEnabled = false, tapHoldEnabled = false, holdEnabled = false)
                SettingsSection.SETTINGS -> before.copy(toggleButtons = Prefs.TOGGLE_NONE)
                else -> before.copy(bindings = before.bindings.mapValues { (key, value) ->
                    if (key in keys) value.copy(action = "") else value
                })
            }
            assertEquals(expected, plan.after)
            assertEquals(section == SettingsSection.SETTINGS, plan.removesToggle)
        }
    }

    @Test
    fun `whole app unbind removes nine optional bindings without changing primary gestures`() {
        val before = customized()
        val plan = SettingsChangePlan.create(before, null, SettingsChangeAction.UNBIND)
        assertTrue(plan.removesToggle)
        assertEquals(before.copy(toggleButtons = Prefs.TOGGLE_NONE,
            bindings = before.bindings.mapValues { it.value.copy(action = "") }), plan.after)
        assertTrue(prefs.applySettings(prefs.snapshot(), before))
        assertTrue(prefs.applySettings(before, plan.after))
        assertEquals(plan.after, Prefs(ApplicationProvider.getApplicationContext()).snapshot())
    }

    @Test
    fun `whole app preset is atomic and does not disturb migration markers`() {
        val before = customized()
        prefs.applySettings(prefs.snapshot(), before)
        val observed = mutableListOf<SettingsState>()
        val listener = { observed.add(prefs.snapshot()); Unit }
        prefs.registerListener(listener)
        val plan = SettingsChangePlan.create(before, null, SettingsChangeAction.PRESET)
        assertEquals(SettingsState(), plan.after)
        assertTrue(prefs.applySettings(before, plan.after))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(observed.isNotEmpty())
        assertTrue(observed.all { it == plan.after })
        prefs.unregisterListener(listener)
        assertEquals(plan.after, Prefs(ApplicationProvider.getApplicationContext()).snapshot())
    }

    @Test
    fun `empty plans cause no writes and stale plans cannot overwrite new choices`() {
        val before = prefs.snapshot()
        var writes = 0
        val listener = { writes++; Unit }
        prefs.registerListener(listener)
        assertTrue(SettingsChangePlan.create(before, null, SettingsChangeAction.PRESET).isEmpty)
        assertTrue(prefs.applySettings(before, before))
        assertEquals(0, writes)
        prefs.singleAction = Prefs.SINGLE_BACK
        assertFalse(prefs.applySettings(before, customized()))
        assertEquals(Prefs.SINGLE_BACK, prefs.singleAction)
        prefs.unregisterListener(listener)
    }

    @Test
    fun `preset clears target overrides and preserves distinct screenshot and level defaults`() {
        val before = prefs.snapshot()
        prefs.setLevelTarget(ShoulderButton.L1, Prefs.TARGET_BOTH)
        val current = prefs.snapshot()
        val plan = SettingsChangePlan.create(current, SettingsSection.SHOULDERS, SettingsChangeAction.PRESET)
        assertFalse(plan.isEmpty)
        assertTrue(prefs.applySettings(current, plan.after))
        assertEquals(before, prefs.snapshot())
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_SCREENSHOT)
        assertEquals(Prefs.DISPLAY_TOP, prefs.levelTarget(ShoulderButton.L1))
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_VOLUME_UP)
        assertEquals(Prefs.TARGET_BOTH, prefs.levelTarget(ShoulderButton.L1))
    }
}
