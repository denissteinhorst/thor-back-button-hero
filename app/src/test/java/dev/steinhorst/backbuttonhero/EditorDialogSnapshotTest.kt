package dev.steinhorst.backbuttonhero

import android.app.Dialog
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.input.ShoulderButton
import dev.steinhorst.backbuttonhero.macro.Macro
import dev.steinhorst.backbuttonhero.macro.MacroButton
import dev.steinhorst.backbuttonhero.macro.MacroEditor
import dev.steinhorst.backbuttonhero.macro.MacroInput
import dev.steinhorst.backbuttonhero.macro.MacroLibraryDialog
import dev.steinhorst.backbuttonhero.macro.MacroStep
import dev.steinhorst.backbuttonhero.macro.SavedMacro
import dev.steinhorst.backbuttonhero.pad.GyroSettings
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.support.Golden
import dev.steinhorst.backbuttonhero.support.ViewTree
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Freezes the editor dialogs opened from the settings: the macro editor and library, and the gyro setup. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-xxhdpi")
class EditorDialogSnapshotTest {

    private val prefs get() = Prefs(ApplicationProvider.getApplicationContext<Context>())
    private fun activity() = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

    private val three = Macro(listOf(
        MacroStep(listOf(MacroInput.Button(MacroButton.A)), 80, 120),
        MacroStep(listOf(MacroInput.Button(MacroButton.B), MacroInput.Button(MacroButton.X)), 400, 300),
        MacroStep(listOf(MacroInput.Button(MacroButton.Y)), 60, 0),
    ))

    private fun snapshot(name: String, dialog: Dialog) {
        ShadowLooper.idleMainLooper()
        Golden.verify(name, ViewTree.describe(checkNotNull(dialog.window).decorView))
        dialog.dismiss()
    }

    private fun editor(name: String, initial: SavedMacro?) {
        val activity = activity()
        snapshot(name, MacroEditor(activity, "L1", initial, "Macro L1", { prefs.macroLibrary }, { false }) { _, _, _ -> }.show())
    }

    @Test
    fun `macro editor, nothing recorded`() = editor("macro_editor_empty_en_light", null)

    @Test
    fun `macro editor with three steps`() = editor("macro_editor_three_en_light", SavedMacro("m1", "Combo", three))

    @Test
    @Config(qualifiers = "en-night-xxhdpi")
    fun `macro editor with three steps, dark`() = editor("macro_editor_three_en_dark", SavedMacro("m1", "Combo", three))

    @Test
    @Config(qualifiers = "de-xxhdpi")
    fun `macro editor with three steps, German`() = editor("macro_editor_three_de_light", SavedMacro("m1", "Combo", three))

    @Test
    fun `macro library`() {
        val activity = activity()
        val first = prefs.saveMacro(null, "Combo", three)
        prefs.saveMacro(null, "Tap A", Macro(listOf(MacroStep(listOf(MacroInput.Button(MacroButton.A)), 80, 0))))
        snapshot("macro_library_en_light", MacroLibraryDialog(activity, prefs, first.id, {}, {}, {}).show())
    }

    private fun gyro(name: String) {
        val activity = activity()
        val dialog = GyroSettingsDialog(activity, ShoulderButton.L1.prefKey, GyroSettings(), { _, _ -> }, {})
        dialog.show {}
        snapshot(name, checkNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog()))
    }

    @Test
    fun `gyro setup`() = gyro("gyro_setup_en_light")

    @Test
    @Config(qualifiers = "en-night-xxhdpi")
    fun `gyro setup, dark`() = gyro("gyro_setup_en_dark")

    @Test
    @Config(qualifiers = "de-xxhdpi")
    fun `gyro setup, German`() = gyro("gyro_setup_de_light")
}
