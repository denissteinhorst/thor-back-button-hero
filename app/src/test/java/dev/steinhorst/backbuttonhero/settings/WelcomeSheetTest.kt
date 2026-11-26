package dev.steinhorst.backbuttonhero.settings

import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.SettingsActivity
import dev.steinhorst.backbuttonhero.input.AndroidButton
import dev.steinhorst.backbuttonhero.input.BackGestureButton
import dev.steinhorst.backbuttonhero.input.DpadDirection
import dev.steinhorst.backbuttonhero.input.FaceButton
import dev.steinhorst.backbuttonhero.input.ShoulderButton
import dev.steinhorst.backbuttonhero.macro.Macro
import dev.steinhorst.backbuttonhero.macro.SavedMacro
import dev.steinhorst.backbuttonhero.prefs.AppException
import dev.steinhorst.backbuttonhero.prefs.BindingSettings
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.prefs.SettingsState
import dev.steinhorst.backbuttonhero.support.Golden
import dev.steinhorst.backbuttonhero.support.ViewTree
import dev.steinhorst.backbuttonhero.support.useEarlierDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-xxhdpi")
class WelcomeSheetTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs get() = Prefs(context)

    private fun open(): SettingsActivity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

    private fun latest() = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun start(): AlertDialog {
        latest().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        return latest()
    }

    private fun choices(dialog: AlertDialog): List<String> = (0 until dialog.listView.adapter.count)
        .map { dialog.listView.adapter.getItem(it).toString().substringBefore('\n') }

    private fun pick(dialog: AlertDialog, label: String) {
        val index = choices(dialog).indexOf(label)
        assertTrue("$label not offered", index >= 0)
        dialog.listView.performItemClick(null, index, index.toLong())
        idle()
    }

    private fun snapshot(name: String) {
        val dialog = ShadowDialog.getLatestDialog() ?: error("No dialog was shown")
        Golden.verify(name, ViewTree.describe(dialog.window!!.decorView))
    }

    @Test
    fun `the defaults set the earlier shortcuts and the pause pair on top of factory settings`() {
        val after = startSetup(SettingsState(), StartSetup.DEFAULTS)

        BackGestureButton.entries.forEach { assertEquals(it.earlierDefault, after.bindings.getValue(it.prefKey).action) }
        DpadDirection.entries.forEach { assertEquals(it.earlierDefault, after.bindings.getValue(it.prefKey).action) }
        assertEquals(Prefs.SHOULDER_FLICK_LEFT, after.bindings.getValue(ShoulderButton.L1.prefKey).action)
        assertEquals(Prefs.SHOULDER_FLICK_RIGHT, after.bindings.getValue(ShoulderButton.R1.prefKey).action)
        assertEquals(Prefs.SHOULDER_SCREENSHOT, after.bindings.getValue(ShoulderButton.L2.prefKey).action)
        assertEquals(Prefs.SHOULDER_CONTROLLER_FOCUS, after.bindings.getValue(ShoulderButton.R2.prefKey).action)
        assertEquals(Prefs.SHOULDER_CLOSE_APPS, after.bindings.getValue(AndroidButton.START.prefKey).action)
        assertEquals(Prefs.SHOULDER_LAST_APP, after.bindings.getValue(AndroidButton.SELECT.prefKey).action)
        assertEquals(Prefs.TOGGLE_START_SELECT, after.toggleButtons)
        assertEquals(500L, after.toggleMs)
        val set = BackGestureButton.entries.map { it.prefKey } + DpadDirection.entries.map { it.prefKey } +
            ShoulderButton.entries.map { it.prefKey } + listOf(AndroidButton.START.prefKey, AndroidButton.SELECT.prefKey)
        after.bindings.filterKeys { it !in set }.forEach { (key, value) -> assertEquals(key, "", value.action) }
    }

    @Test
    fun `a clean start keeps only Back's gestures`() {
        val after = startSetup(SettingsState(), StartSetup.CLEAN)

        val gestures = BackGestureButton.entries.associate { it.prefKey to it.earlierDefault }
        after.bindings.forEach { (key, value) -> assertEquals(key, gestures[key] ?: "", value.action) }
        assertEquals(Prefs.TOGGLE_NONE, after.toggleButtons)
        assertEquals(Prefs.DEFAULT_TOGGLE_HOLD_MS, after.toggleMs)
    }

    @Test
    fun `a start setup resets like the whole-app reset and keeps macros`() {
        val macro = SavedMacro("m1", "Combo", Macro(emptyList()))
        val before = SettingsState(
            holdMs = 1500,
            dualMode = true,
            exceptions = listOf(AppException("com.example.game", "Game")),
            macros = listOf(macro),
            bindings = SettingsState().bindings + (FaceButton.entries.first().prefKey to BindingSettings(Prefs.SHOULDER_HOME)),
        )

        val after = startSetup(before, StartSetup.CLEAN)

        assertEquals(Prefs.DEFAULT_HOLD_MS, after.holdMs)
        assertFalse(after.dualMode)
        assertTrue(after.exceptions.isEmpty())
        assertEquals(listOf(macro), after.macros)
        assertEquals("", after.bindings.getValue(FaceButton.entries.first().prefKey).action)
    }

    @Test
    fun `only settings that differ from the factory count as customized`() {
        assertFalse(SettingsState().isCustomized())
        assertFalse(SettingsState(macros = listOf(SavedMacro("m1", "Combo", Macro(emptyList())))).isCustomized())
        assertTrue(SettingsState(holdMs = 1500).isCustomized())
    }

    @Test
    fun `the first start shows the welcome sheet with every top feature`() {
        val activity = open()

        val sheet = latest()
        assertTrue(sheet.isShowing)
        assertEquals("Let's go", sheet.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        val text = ViewTree.describe(sheet.window!!.decorView)
        WelcomeSheet.FEATURES.forEach { (title, detail) ->
            assertTrue(text.contains(activity.getString(title)))
            assertTrue(text.contains(activity.getString(detail)))
        }
    }

    @Test
    fun `a fresh install chooses between the defaults and a clean start only`() {
        open()

        val setup = start()

        assertEquals(listOf("Load defaults", "Clean start"), choices(setup))
        pick(setup, "Load defaults")
        assertTrue(prefs.welcomeSeen)
        assertEquals(Prefs.SHOULDER_HOME, prefs.shoulderAction(BackGestureButton.SINGLE))
        assertEquals(Prefs.TOGGLE_START_SELECT, prefs.toggleButtons)
    }

    @Test
    fun `customized settings can be kept unchanged`() {
        prefs.useEarlierDefaults()
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_SCREENSHOT)
        open()
        val before = prefs.snapshot()

        val setup = start()

        assertEquals(listOf("Load defaults", "Clean start", "Keep settings"), choices(setup))
        pick(setup, "Keep settings")
        assertTrue(prefs.welcomeSeen)
        assertEquals(before, prefs.snapshot())
    }

    @Test
    fun `a clean start frees the combinations of an update`() {
        prefs.useEarlierDefaults()
        open()

        pick(start(), "Clean start")

        assertEquals(Prefs.SHOULDER_SWAP_APPS, prefs.shoulderAction(BackGestureButton.HOLD))
        assertEquals("", prefs.shoulderAction(ShoulderButton.L1))
        assertEquals("", prefs.shoulderAction(DpadDirection.UP))
        assertEquals(Prefs.TOGGLE_NONE, prefs.toggleButtons)
    }

    @Test
    fun `backing out keeps the welcome for the next start`() {
        open()
        latest().cancel()
        idle()
        assertFalse(prefs.welcomeSeen)

        open()
        start().cancel()
        idle()
        assertFalse(prefs.welcomeSeen)
        assertNotNull(latest())
    }

    @Test
    fun `the welcome does not return once a setup was chosen`() {
        prefs.welcomeSeen = true

        open()

        assertNull(ShadowDialog.getLatestDialog())
    }

    @Test
    fun `welcome sheet`() {
        open()
        snapshot("welcome_sheet_en")
    }

    @Test
    @Config(qualifiers = "de-w800dp-h421dp-xxhdpi")
    fun `welcome sheet in two columns`() {
        open()
        snapshot("welcome_sheet_wide_de")
    }

    @Test
    fun `start setup choice`() {
        prefs.useEarlierDefaults()
        open()
        start()
        snapshot("welcome_setup_customized_en")
    }
}
