package dev.steinhorst.backbuttonhero

import dev.steinhorst.backbuttonhero.support.selectSettingsPage

import android.app.Dialog
import dev.steinhorst.backbuttonhero.support.Golden
import dev.steinhorst.backbuttonhero.support.ViewTree
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-xxhdpi")
class SettingsDialogSnapshotTest {

    private fun buildActivity() = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

    private fun latestDialog(): Dialog =
        ShadowDialog.getLatestDialog() ?: error("No dialog was shown")

    private fun snapshotDialog(name: String) {
        val dialog = latestDialog()
        val decorView = dialog.window?.decorView ?: error("Dialog has no window")
        Golden.verify(name, ViewTree.describe(decorView))
    }

    @Test
    fun `single-tap action chooser dialog`() {
        val activity = buildActivity()
        clickRowWithText(activity, activity.getString(R.string.single_title))
        snapshotDialog("dialog_single_action_chooser")
    }

    @Test
    fun `reset settings confirmation dialog`() {
        val activity = buildActivity()
        selectSettingsPage(activity, SettingsSection.SETTINGS)
        clickRowWithText(activity, activity.getString(R.string.reset_settings))
        snapshotDialog("dialog_reset_settings_confirm")
    }

    @Test
    fun `repair service explanation dialog`() {
        val activity = buildActivity()
        selectSettingsPage(activity, SettingsSection.HELP)
        clickRowWithText(activity, activity.getString(R.string.repair_service))
        snapshotDialog("dialog_repair_service")
    }

    @Test
    fun `shoulder unbind previews only that page`() {
        val activity = buildActivity()
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        clickRowWithText(activity, activity.getString(R.string.settings_unbind))
        snapshotDialog("dialog_unbind_shoulders")
    }

    @Test
    fun `preset previews current and factory values`() {
        val activity = buildActivity()
        Prefs(activity).doubleTapMs = 500L
        clickRowWithText(activity, activity.getString(R.string.settings_preset))
        snapshotDialog("dialog_preset_basic")
    }

    private fun clickRowWithText(activity: SettingsActivity, text: String) {
        val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        val target = findTextView(content, text) ?: error("No row found with text: $text")
        var view: android.view.View = target
        while (!view.isClickable) {
            view = view.parent as? android.view.View ?: error("No clickable ancestor for: $text")
        }
        view.performClick()
    }

    private fun findTextView(root: android.view.View, text: String): android.view.View? {
        if (root is android.widget.TextView && root.text.toString() == text) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findTextView(root.getChildAt(i), text)?.let { return it }
            }
        }
        return null
    }
}
