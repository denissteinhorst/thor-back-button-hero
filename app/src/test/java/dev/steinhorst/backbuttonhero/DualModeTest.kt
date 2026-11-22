package dev.steinhorst.backbuttonhero

import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.prefs.BindingLayer
import dev.steinhorst.backbuttonhero.prefs.ExceptionControl
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.prefs.SettingsBackup
import dev.steinhorst.backbuttonhero.prefs.SettingsChangeAction
import dev.steinhorst.backbuttonhero.prefs.SettingsChangePlan
import dev.steinhorst.backbuttonhero.prefs.SettingsState
import dev.steinhorst.backbuttonhero.prefs.StickTimings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The dual-mode storage: the Home layer, its one-time copy, the exclusive switch, plans and backup. */
@RunWith(RobolectricTestRunner::class)
class DualModeTest {
    private val prefs = Prefs(ApplicationProvider.getApplicationContext())
    private fun home(button: ComboButton) = HomeLayerButton.of(button)

    @Test
    fun `a Home layer binding is its own key on the same control`() {
        val l1 = home(ShoulderButton.L1)
        assertSame(l1, home(ShoulderButton.L1))
        assertSame(l1, home(l1))
        assertEquals("dual_l1", l1.prefKey)
        assertSame(ShoulderButton.L1, l1.base)
        assertSame(ShoulderButton.L1, ShoulderButton.L1.base)
        assertEquals(ExceptionControl.L1, ExceptionControl.of(l1))
        assertEquals(ExceptionControl.of(StickClickButton.RIGHT_DOUBLE), ExceptionControl.of(home(StickClickButton.RIGHT_DOUBLE)))
        // Back + Home has no Home + Home counterpart; every other binding has one.
        assertFalse(AndroidButton.HOME in Prefs.HOME_LAYER_BASES)
        assertEquals(Prefs.STORED_BUTTONS.size, Prefs.STORED_BUTTONS.map { it.prefKey }.toSet().size)
        assertEquals(Prefs.HOME_LAYER_BASES.size, Prefs.HOME_LAYER_BUTTONS.size)
    }

    @Test
    fun `the two Home modes exclude each other`() {
        prefs.homeAsBack = true
        prefs.dualMode = true
        assertTrue(prefs.dualMode)
        assertFalse(prefs.homeAsBack)
        prefs.homeAsBack = true
        assertFalse(prefs.dualMode)
        assertTrue(prefs.homeAsBack)
        prefs.homeAsBack = false
        assertFalse(prefs.dualMode)
    }

    @Test
    fun `the first switch-on copies the Back layer once, Home's single tap stays Home`() {
        prefs.setShoulderAction(BackGestureButton.SINGLE, Prefs.SHOULDER_BACK)
        prefs.setShoulderAction(BackGestureButton.DOUBLE, Prefs.SHOULDER_SWITCHER)
        prefs.setShoulderAction(ShoulderButton.L2, Prefs.SHOULDER_SCREENSHOT)
        prefs.setLevelTarget(ShoulderButton.L2, Prefs.DISPLAY_BOTTOM)
        prefs.setShoulderAction(StickButton.LEFT_UP, Prefs.SHOULDER_VOLUME_UP)
        prefs.doubleTapMs = 450
        prefs.holdMs = 1500
        prefs.setStickDoubleMs(Stick.LEFT, 250)
        prefs.dualMode = true
        assertEquals(Prefs.SHOULDER_HOME, prefs.shoulderAction(home(BackGestureButton.SINGLE)))
        assertEquals(Prefs.DISPLAY_TOP, prefs.shoulderDisplay(home(BackGestureButton.SINGLE)))
        assertEquals(Prefs.SHOULDER_SWITCHER, prefs.shoulderAction(home(BackGestureButton.DOUBLE)))
        assertEquals(Prefs.SHOULDER_SCREENSHOT, prefs.shoulderAction(home(ShoulderButton.L2)))
        assertEquals(Prefs.DISPLAY_BOTTOM, prefs.levelTarget(home(ShoulderButton.L2)))
        assertEquals(Prefs.SHOULDER_VOLUME_UP, prefs.shoulderAction(home(StickButton.LEFT_UP)))
        assertEquals(450, prefs.homeDoubleTapMs)
        assertEquals(1500, prefs.homeHoldMs)
        assertEquals(250, prefs.stickDoubleMs(Stick.LEFT, home = true))
        // The Back layer is untouched by the copy.
        assertEquals(Prefs.SHOULDER_BACK, prefs.shoulderAction(BackGestureButton.SINGLE))

        // Off keeps the Home layer; on again brings it back instead of copying anew.
        prefs.setShoulderAction(home(ShoulderButton.L2), Prefs.SHOULDER_NOTIFICATIONS)
        prefs.setShoulderAction(ShoulderButton.R1, Prefs.SHOULDER_STATUS)
        prefs.dualMode = false
        assertEquals(Prefs.SHOULDER_NOTIFICATIONS, prefs.shoulderAction(home(ShoulderButton.L2)))
        prefs.dualMode = true
        assertEquals(Prefs.SHOULDER_NOTIFICATIONS, prefs.shoulderAction(home(ShoulderButton.L2)))
        assertEquals("", prefs.shoulderAction(home(ShoulderButton.R1)))
    }

    @Test
    fun `Home's gestures read the Home layer`() {
        prefs.dualMode = true
        val gestures = prefs.homeGestures
        prefs.setShoulderAction(home(BackGestureButton.DOUBLE), "")
        prefs.setShoulderAction(home(BackGestureButton.HOLD), Prefs.SHOULDER_STATUS)
        prefs.setShoulderAction(home(BackGestureButton.TAP_HOLD), Prefs.SHOULDER_RECENTS)
        prefs.setShoulderAction(BackGestureButton.DOUBLE, Prefs.SHOULDER_SWITCHER)
        prefs.homeDoubleTapMs = 200
        prefs.homeTapHoldMs = 700
        prefs.homeHoldMs = 1200
        assertFalse(gestures.doubleTapEnabled)
        assertTrue(prefs.doubleTapEnabled)
        assertTrue(gestures.holdEnabled)
        assertTrue(gestures.tapHoldEnabled)
        assertEquals(200, gestures.doubleTapMs)
        assertEquals(700, gestures.tapHoldMs)
        assertEquals(1200, gestures.holdMs)
        assertEquals(Prefs.DEFAULT_DOUBLE_TAP_MS, prefs.doubleTapMs)
    }

    @Test
    fun `a resting Home layer asks nothing of the helper`() {
        prefs.dualMode = true
        prefs.setShoulderAction(home(DpadDirection.UP), Prefs.SHOULDER_STATUS)
        prefs.setShoulderAction(home(StickButton.RIGHT_LEFT), Prefs.SHOULDER_STATUS)
        prefs.setShoulderAction(home(StickClickButton.LEFT_PRESS), Prefs.SHOULDER_STATUS)
        prefs.setShoulderAction(home(FaceButton.A), Prefs.SHOULDER_GYRO)
        assertTrue(prefs.hasDpadAction())
        assertTrue(prefs.hasStickAction(Stick.RIGHT))
        assertFalse(prefs.hasStickAction(Stick.LEFT))
        assertTrue(prefs.hasStickClickAction(Stick.LEFT))
        assertTrue(prefs.hasGyroAction())
        prefs.dualMode = false
        assertFalse(prefs.hasDpadAction())
        assertFalse(prefs.hasStickAction())
        assertFalse(prefs.hasStickClickAction(Stick.LEFT))
        assertFalse(prefs.hasGyroAction())
    }

    @Test
    fun `macros of the Home layer count, and deleting one clears it there too`() {
        val saved = prefs.saveMacro(null, "Combo", Macro(listOf(MacroStep(listOf(MacroInput.Button(MacroButton.A)), 80, 0))))
        prefs.dualMode = true
        prefs.setShoulderAction(home(ShoulderButton.R2), Prefs.SHOULDER_MACRO)
        prefs.setMacroRef(home(ShoulderButton.R2), saved.id)
        assertTrue(prefs.hasMacroAction())
        assertEquals(listOf<ComboButton>(home(ShoulderButton.R2)), prefs.macroUsers(saved.id))
        prefs.deleteMacro(saved.id)
        assertEquals(null, prefs.savedMacro(home(ShoulderButton.R2)))
    }

    @Test
    fun `stick-click timings are per layer`() {
        prefs.setStickDoubleMs(Stick.RIGHT, 400)
        prefs.setStickTapHoldMs(Stick.RIGHT, 1500, home = true)
        assertEquals(400, prefs.stickDoubleMs(Stick.RIGHT))
        assertEquals(Prefs.DEFAULT_DOUBLE_TAP_MS, prefs.stickDoubleMs(Stick.RIGHT, home = true))
        assertEquals(1500, prefs.stickTapHoldMs(Stick.RIGHT, home = true))
        assertEquals(Prefs.DEFAULT_TAP_HOLD_MS, prefs.stickTapHoldMs(Stick.RIGHT))
    }

    private fun bound(): SettingsState = SettingsState().let { factory ->
        factory.copy(
            dualMode = true, dualSeeded = true, homeDoubleMs = 200, homeHoldMs = 1800,
            homeStickTimings = mapOf("lstick" to StickTimings(200, 600), "rstick" to StickTimings(250, 700)),
            stickTimings = mapOf("lstick" to StickTimings(500, 1600), "rstick" to StickTimings(550, 1700)),
            bindings = factory.bindings.mapValues { (_, value) -> value.copy(action = Prefs.SHOULDER_STATUS) },
        )
    }

    private fun actions(state: SettingsState, buttons: List<ComboButton>) = buttons.map { state.bindings.getValue(it.prefKey).action }.toSet()

    @Test
    fun `Unbind reaches only the chosen layers`() {
        val before = bound()
        val homeShoulders = ShoulderButton.entries.map(::home)
        val homeOnly = SettingsChangePlan.create(before, SettingsSection.SHOULDERS, SettingsChangeAction.UNBIND, setOf(BindingLayer.HOME))
        assertEquals(setOf(""), actions(homeOnly.after, homeShoulders))
        assertEquals(setOf(Prefs.SHOULDER_STATUS), actions(homeOnly.after, ShoulderButton.entries))
        assertEquals(setOf(BindingLayer.HOME), homeOnly.layers)
        val backOnly = SettingsChangePlan.create(before, SettingsSection.SHOULDERS, SettingsChangeAction.UNBIND)
        assertEquals(setOf(Prefs.SHOULDER_STATUS), actions(backOnly.after, homeShoulders))
        assertEquals(setOf(""), actions(backOnly.after, ShoulderButton.entries))
        val both = SettingsChangePlan.create(before, SettingsSection.BASIC, SettingsChangeAction.UNBIND, BindingLayer.entries.toSet())
        assertEquals(setOf(""), actions(both.after, BackGestureButton.entries + BackGestureButton.entries.map(::home) +
            AndroidButton.entries + listOf(AndroidButton.START, AndroidButton.SELECT).map(::home)))
        // Unbind keeps every timing.
        assertEquals(before.homeDoubleMs, both.after.homeDoubleMs)
        assertEquals(before.homeHoldMs, both.after.homeHoldMs)
        assertEquals(before.copy(bindings = both.after.bindings), both.after)
    }

    @Test
    fun `page presets reset the chosen layer's timings, the whole-app reset ends dual mode`() {
        val before = bound()
        val sticks = SettingsChangePlan.create(before, SettingsSection.STICKS, SettingsChangeAction.PRESET, setOf(BindingLayer.HOME))
        assertEquals(SettingsState().homeStickTimings, sticks.after.homeStickTimings)
        assertEquals(before.stickTimings, sticks.after.stickTimings)
        val basic = SettingsChangePlan.create(before, SettingsSection.BASIC, SettingsChangeAction.PRESET, setOf(BindingLayer.HOME))
        assertEquals(Prefs.DEFAULT_DOUBLE_TAP_MS, basic.after.homeDoubleMs)
        assertEquals(before.doubleMs, basic.after.doubleMs)
        assertEquals(SettingsState(), SettingsChangePlan.create(before, null, SettingsChangeAction.PRESET).after)
        assertTrue(SettingsChangePlan.hasLayers(null))
        assertFalse(SettingsChangePlan.hasLayers(SettingsSection.SETTINGS))
        assertFalse(SettingsChangePlan.hasLayers(SettingsSection.EXCEPTIONS))
    }

    @Test
    fun `the state survives the preferences and a backup`() {
        val state = bound().copy(homeAsBack = false)
        assertTrue(prefs.applySettings(prefs.snapshot(), state))
        assertEquals(state, prefs.snapshot())
        val text = SettingsBackup.write(state, "1.6.0", 16)
        assertEquals(state, (SettingsBackup.read(text) as SettingsBackup.Result.Loaded).state)
    }

    @Test
    fun `a backup without dual mode reads as unseeded, one claiming both Home modes keeps dual mode`() {
        val old = """{"format":"thor-back-button-hero-backup","formatVersion":1,"settings":{"homeAsBack":true}}"""
        val state = (SettingsBackup.read(old) as SettingsBackup.Result.Loaded).state
        assertTrue(state.homeAsBack)
        assertFalse(state.dualMode)
        assertFalse(state.dualSeeded)
        val both = """{"format":"thor-back-button-hero-backup","formatVersion":1,
            "settings":{"homeAsBack":true,"dualMode":{"enabled":true,"timings":{"doubleMs":5000}}}}"""
        val read = (SettingsBackup.read(both) as SettingsBackup.Result.Loaded).state
        assertTrue(read.dualMode)
        assertFalse(read.homeAsBack)
        assertTrue(read.dualSeeded)
        assertEquals(600, read.homeDoubleMs)
    }

    @Test
    fun `seeding is a pure copy of the Back layer`() {
        val before = bound().copy(dualSeeded = false, doubleMs = 333)
        val seeded = before.seededHomeLayer()
        assertTrue(seeded.dualSeeded)
        assertEquals(333, seeded.homeDoubleMs)
        assertEquals(SettingsState.HOME_SINGLE_TAP, seeded.bindings.getValue(home(BackGestureButton.SINGLE).prefKey))
        assertNotSame(before.bindings, seeded.bindings)
        Prefs.HOME_LAYER_BASES.filter { it != BackGestureButton.SINGLE }.forEach {
            assertEquals(before.bindings.getValue(it.prefKey), seeded.bindings.getValue(home(it).prefKey))
        }
    }
}
