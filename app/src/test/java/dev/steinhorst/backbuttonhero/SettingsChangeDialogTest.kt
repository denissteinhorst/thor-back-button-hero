package dev.steinhorst.backbuttonhero

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import dev.steinhorst.backbuttonhero.support.selectSettingsPage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-xxhdpi")
class SettingsChangeDialogTest {
    private fun open() = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
    private fun find(root: View, match: (View) -> Boolean): View? {
        if (match(root)) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), match)?.let { return it }
        return null
    }
    private fun click(activity: SettingsActivity, res: Int) {
        var view = find(activity.findViewById(android.R.id.content)) {
            it is TextView && it.text.toString() == activity.getString(res)
        }!!
        while (!view.isClickable) view = view.parent as View
        view.performClick()
    }
    private fun dialog() = ShadowAlertDialog.getLatestAlertDialog()
    private fun text(view: View): String = buildString {
        if (view is TextView) append(view.text).append('\n')
        if (view is ViewGroup) for (i in 0 until view.childCount) append(text(view.getChildAt(i)))
    }
    private fun accept() {
        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `unbind preview names current action and target and cancel changes nothing`() {
        val activity = open()
        val prefs = Prefs(activity)
        prefs.setLevelTarget(ShoulderButton.L2, Prefs.TARGET_BOTH)
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        val before = prefs.snapshot()
        click(activity, R.string.settings_unbind)
        val preview = text(dialog().window!!.decorView)
        assertTrue(preview.contains("Back + L2: Take screenshot (Both screens) → Not set"))
        assertFalse(preview.contains("D-pad up:"))
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, prefs.snapshot())
    }

    @Test
    fun `preset lists only actual changes and applies the preview`() {
        val activity = open()
        val prefs = Prefs(activity)
        prefs.doubleTapMs = 500
        click(activity, R.string.settings_preset)
        val preview = text(dialog().window!!.decorView)
        assertTrue(preview.contains("Double-press timing: 500 ms → 300 ms"))
        assertFalse(preview.contains("Screen-swap hold duration:"))
        accept()
        assertEquals(300L, prefs.doubleTapMs)
        click(activity, R.string.settings_preset)
        assertTrue(text(dialog().window!!.decorView).contains(activity.getString(R.string.settings_already_preset)))
        assertNull(dialog().getButton(AlertDialog.BUTTON_NEGATIVE).text.takeIf { it.isNotEmpty() })
    }

    @Test
    fun `selecting not set requires confirmation and disables only the hold duration`() {
        val activity = open()
        val prefs = Prefs(activity)
        selectSettingsPage(activity, SettingsSection.SETTINGS)
        click(activity, R.string.service_toggle_buttons)
        dialog().listView.performItemClick(null, 3, 3)
        assertEquals(Prefs.DEFAULT_TOGGLE_BUTTONS, prefs.toggleButtons)
        assertTrue(text(dialog().window!!.decorView).contains(activity.getString(R.string.settings_unbind_reactivates)))
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Prefs.DEFAULT_TOGGLE_BUTTONS, prefs.toggleButtons)
        click(activity, R.string.service_toggle_buttons)
        dialog().listView.performItemClick(null, 3, 3)
        accept()
        assertEquals(Prefs.TOGGLE_NONE, prefs.toggleButtons)
        val slider = find(activity.findViewById(android.R.id.content)) { it is SeekBar && it.max == 29 }!!
        assertFalse(slider.isEnabled)
        assertEquals(300L, prefs.toggleHoldMs)
        click(activity, R.string.service_toggle_buttons)
        dialog().listView.performItemClick(null, 0, 0)
        assertTrue(slider.isEnabled)
        assertEquals(Prefs.TOGGLE_L1_R1, prefs.toggleButtons)
    }

    @Test
    fun `global unbind preview includes activation and all eight buttons but no primary gestures`() {
        val activity = open()
        val prefs = Prefs(activity)
        selectSettingsPage(activity, SettingsSection.SETTINGS)
        click(activity, R.string.unbind_optionals)
        val preview = text(dialog().window!!.decorView)
        assertTrue(preview.contains(activity.getString(R.string.settings_entire_app)))
        assertTrue(preview.contains("Hold button(s): L2 + R2 → Not set"))
        assertTrue(preview.contains("Back + D-pad up:"))
        assertFalse(preview.contains("Single press:"))
        val before = prefs.snapshot()
        accept()
        assertEquals(SettingsChangePlan.create(before, null, SettingsChangeAction.UNBIND).after, prefs.snapshot())
        click(activity, R.string.unbind_optionals)
        assertTrue(text(dialog().window!!.decorView).contains(activity.getString(R.string.settings_already_unbound)))
    }

    @Test
    fun `stale dialog asks again with current values before any write`() {
        val activity = open()
        val prefs = Prefs(activity)
        prefs.doubleTapMs = 500
        click(activity, R.string.settings_preset)
        prefs.doubleTapMs = 550
        accept()
        assertEquals(550L, prefs.doubleTapMs)
        assertTrue(dialog().isShowing)
        assertTrue(text(dialog().window!!.decorView).contains("550 ms → 300 ms"))
        accept()
        assertEquals(300L, prefs.doubleTapMs)
    }
}
