package dev.steinhorst.backbuttonhero

import dev.steinhorst.backbuttonhero.support.startOnBasic
import dev.steinhorst.backbuttonhero.support.useEarlierDefaults
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
    @org.junit.Before
    fun earlierDefaults() {
        Prefs(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()).useEarlierDefaults()
    }

    private fun open() = Robolectric.buildActivity(SettingsActivity::class.java).setup(startOnBasic()).get()
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
    fun `every page unbinds its own buttons and the settings page unbinds every optional button`() {
        val activity = open()
        val prefs = Prefs(activity)
        val pages = mapOf(
            SettingsSection.BASIC to AndroidButton.entries + BackGestureButton.entries,
            SettingsSection.FACE to FaceButton.entries,
            SettingsSection.SHOULDERS to ShoulderButton.entries,
            SettingsSection.STICKS to StickButton.entries + StickClickButton.entries,
            SettingsSection.DPAD to DpadDirection.entries,
        )
        val all = pages.values.flatten()
        fun bindAll() = all.forEach { prefs.setShoulderAction(it, Prefs.SHOULDER_SCREENSHOT) }
        fun unbind(section: SettingsSection) {
            selectSettingsPage(activity, section)
            var view = find(activity.findViewById(android.R.id.content)) {
                it.isShown && it is TextView && it.text.toString() == activity.getString(R.string.settings_unbind)
            }!!
            while (!view.isClickable) view = view.parent as View
            view.performClick()
            accept()
        }
        pages.forEach { (section, buttons) ->
            bindAll()
            unbind(section)
            all.forEach { button ->
                assertEquals("$section ${button.prefKey}", if (button in buttons) "" else Prefs.SHOULDER_SCREENSHOT, prefs.shoulderAction(button))
            }
        }
        bindAll()
        prefs.toggleButtons = Prefs.TOGGLE_L1_R1
        unbind(SettingsSection.SETTINGS)
        // The whole-app Unbind leaves the primary Back gestures alone, like it always has.
        all.forEach { button ->
            assertEquals(button.prefKey, if (button is BackGestureButton) Prefs.SHOULDER_SCREENSHOT else "", prefs.shoulderAction(button))
        }
        assertEquals(Prefs.TOGGLE_NONE, prefs.toggleButtons)
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
    fun `selecting not set requires confirmation and hides only the hold duration`() {
        val activity = open()
        val prefs = Prefs(activity)
        prefs.toggleButtons = Prefs.TOGGLE_L2_R2
        selectSettingsPage(activity, SettingsSection.SETTINGS)
        click(activity, R.string.service_toggle_buttons)
        dialog().listView.performItemClick(null, 5, 5)
        assertEquals(Prefs.TOGGLE_L2_R2, prefs.toggleButtons)
        assertTrue(text(dialog().window!!.decorView).contains(activity.getString(R.string.settings_unbind_reactivates)))
        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Prefs.TOGGLE_L2_R2, prefs.toggleButtons)
        click(activity, R.string.service_toggle_buttons)
        dialog().listView.performItemClick(null, 5, 5)
        accept()
        assertEquals(Prefs.TOGGLE_NONE, prefs.toggleButtons)
        val slider = find(activity.findViewById(android.R.id.content)) { it is SeekBar && it.max == 29 }!!
        assertFalse(slider.isShown)
        assertEquals(300L, prefs.toggleHoldMs)
        click(activity, R.string.service_toggle_buttons)
        dialog().listView.performItemClick(null, 0, 0)
        assertTrue(slider.isShown)
        assertEquals(Prefs.TOGGLE_L1_R1, prefs.toggleButtons)
    }

    @Test
    fun `stale dialog asks again with current values before any write`() {
        val activity = open()
        val prefs = Prefs(activity)
        prefs.setShoulderAction(BackGestureButton.SINGLE, Prefs.SHOULDER_HOME)
        click(activity, R.string.settings_unbind)
        prefs.setShoulderAction(BackGestureButton.DOUBLE, Prefs.SHOULDER_SCREENSHOT)
        accept()
        // The preview was made before the second assignment, so nothing was written yet.
        assertEquals(Prefs.SHOULDER_HOME, prefs.shoulderAction(BackGestureButton.SINGLE))
        assertTrue(dialog().isShowing)
        accept()
        assertEquals("", prefs.shoulderAction(BackGestureButton.SINGLE))
        assertEquals("", prefs.shoulderAction(BackGestureButton.DOUBLE))
    }

    @Test
    fun `load defaults previews the defaults and applies them in one step`() {
        val activity = open()
        val prefs = Prefs(activity)
        prefs.setShoulderAction(FaceButton.A, Prefs.SHOULDER_SCREENSHOT)
        selectSettingsPage(activity, SettingsSection.SETTINGS)
        val expected = startSetup(prefs.snapshot(), StartSetup.DEFAULTS)

        click(activity, R.string.settings_load_defaults)
        assertEquals(activity.getString(R.string.settings_load_defaults), find(dialog().window!!.decorView) {
            it is TextView && it.text.toString() == activity.getString(R.string.settings_load_defaults)
        }?.let { (it as TextView).text.toString() })
        text(dialog().window!!.decorView).let { preview ->
            assertTrue(preview, preview.contains("Hold button(s): L2 + R2 → Start + Select"))
            // Face buttons and Home/Start/Select belong to the preview like every other binding.
            assertTrue(preview, preview.contains("Back + Start: Not set → Close Apps"))
            assertTrue(preview, preview.contains("Back + A: Take screenshot"))
        }
        accept()

        assertEquals(expected, prefs.snapshot())
        assertEquals(Prefs.TOGGLE_START_SELECT, prefs.toggleButtons)
        assertEquals("", prefs.shoulderAction(FaceButton.A))

        click(activity, R.string.settings_load_defaults)
        assertTrue(text(dialog().window!!.decorView).contains(activity.getString(R.string.settings_already_defaults)))
    }

    @Test
    fun `a stale defaults preview is made again from the current settings`() {
        val activity = open()
        val prefs = Prefs(activity)
        selectSettingsPage(activity, SettingsSection.SETTINGS)
        click(activity, R.string.settings_load_defaults)
        prefs.setShoulderAction(FaceButton.B, Prefs.SHOULDER_SCREENSHOT)
        accept()
        assertEquals(Prefs.SHOULDER_SCREENSHOT, prefs.shoulderAction(FaceButton.B))
        assertTrue(dialog().isShowing)
        accept()
        assertEquals(startSetup(prefs.snapshot(), StartSetup.DEFAULTS), prefs.snapshot())
    }
}
