package dev.steinhorst.backbuttonhero

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowDialog

/** Behavior of the settings screen's actions and the indicator-appearance popup. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-xxhdpi")
class SettingsActionsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun open(indicatorButton: String? = null): ActivityController<SettingsActivity> {
        val intent = Intent(context, SettingsActivity::class.java)
        indicatorButton?.let { intent.putExtra(SettingsActivity.EXTRA_INDICATOR_BUTTON, it) }
        return Robolectric.buildActivity(SettingsActivity::class.java, intent).setup().also {
            shadowOf(android.os.Looper.getMainLooper()).idle()
        }
    }

    private fun find(root: View, match: (View) -> Boolean): View? {
        if (match(root)) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), match)?.let { return it }
        return null
    }

    private fun clickRow(activity: SettingsActivity, text: String) {
        var view = find(activity.findViewById(android.R.id.content)) { it is TextView && it.text.toString() == text }
            ?: error("No row with text: $text")
        while (!view.isClickable) view = view.parent as View
        view.performClick()
    }

    private fun dialogView(): View =
        (ShadowDialog.getLatestDialog() ?: error("No dialog")).window!!.decorView

    @Test
    fun `toggle buttons chooser offers stick and shoulder choices and persists selection`() {
        val activity = open().get()
        for ((index, expected) in listOf(Prefs.TOGGLE_L1_R1, Prefs.TOGGLE_L2_R2, Prefs.TOGGLE_BOTH).withIndex()) {
            clickRow(activity, activity.getString(R.string.service_toggle_buttons))
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(3, dialog.listView.adapter.count)
            assertEquals(listOf("L1 + R1", "L2 + R2", "L3 + R3")[index], dialog.listView.adapter.getItem(index))
            dialog.listView.performItemClick(null, index, index.toLong())
            assertEquals(expected, Prefs(context).toggleButtons)
            assertFalse(dialog.isShowing)
        }
    }

    @Test
    fun `toggle duration slider stores the selected hold time`() {
        val activity = open().get()
        val root = activity.findViewById<View>(android.R.id.content)
        val slider = find(root) { it is SeekBar && it.max == 29 } as SeekBar
        shadowOf(slider).onSeekBarChangeListener.onProgressChanged(slider, 16, true)
        assertEquals(1700L, Prefs(context).toggleHoldMs)
    }

    @Test
    fun `unbind optionals clears every button after confirming`() {
        val activity = open().get()

        clickRow(activity, activity.getString(R.string.unbind_optionals))
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val prefs = Prefs(context)
        ShoulderButton.entries.forEach { assertEquals("", prefs.shoulderAction(it)) }
        DpadDirection.entries.forEach { assertEquals("", prefs.shoulderAction(it)) }
    }

    @Test
    fun `cancelling unbind optionals leaves every assignment alone`() {
        val activity = open().get()

        clickRow(activity, activity.getString(R.string.unbind_optionals))
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val prefs = Prefs(context)
        assertEquals(Prefs.SHOULDER_CONTROLLER_FOCUS, prefs.shoulderAction(ShoulderButton.R2))
        assertEquals(Prefs.SHOULDER_FLICK_LEFT, prefs.shoulderAction(ShoulderButton.L1))
    }

    @Test
    fun `tapping the pictogram opens the indicator popup for a focus button`() {
        open(indicatorButton = ShoulderButton.R2.prefKey)

        val title = find(dialogView()) { it is TextView && it.text.toString() == "Indicator appearance" }
        assertNotNull(title)
    }

    @Test
    fun `the indicator popup is not opened for a button without the focus action`() {
        open(indicatorButton = ShoulderButton.L1.prefKey)

        assertNull(ShadowDialog.getLatestDialog())
    }

    @Test
    fun `choosing a dot saves the position and keeps the popup open`() {
        open(indicatorButton = ShoulderButton.R2.prefKey)

        find(dialogView()) { it.contentDescription?.toString() == "Top left" }!!.performClick()

        assertEquals(Prefs.FOCUS_POS_TOP_LEFT, Prefs(context).focusPosition(ShoulderButton.R2))
        assertTrue(ShadowDialog.getLatestDialog()!!.isShowing)
    }

    @Test
    fun `with the indicator switched off a dot cannot be chosen`() {
        open(indicatorButton = ShoulderButton.R2.prefKey)

        (find(dialogView()) { it is Switch } as Switch).isChecked = false
        find(dialogView()) { it.contentDescription?.toString() == "Top left" }!!.performClick()

        val prefs = Prefs(context)
        assertFalse(prefs.focusIndicator(ShoulderButton.R2))
        assertEquals(Prefs.FOCUS_POS_CENTER_RIGHT, prefs.focusPosition(ShoulderButton.R2))
    }

    @Test
    fun `the display time slider saves in 100 ms steps from 200 to 1000`() {
        open(indicatorButton = ShoulderButton.R2.prefKey)
        val bar = find(dialogView()) { it is SeekBar } as SeekBar

        assertEquals(8, bar.max)
        shadowOf(bar).onSeekBarChangeListener.onProgressChanged(bar, 8, true)

        assertEquals(1000L, Prefs(context).focusDuration(ShoulderButton.R2))
    }

    @Test
    fun `the row reports the chosen position, or Off when the indicator is hidden`() {
        val prefs = Prefs(context)
        prefs.setFocusPosition(ShoulderButton.R2, Prefs.FOCUS_POS_BOTTOM_LEFT)
        val activity = open().get()
        assertNotNull(find(activity.findViewById(android.R.id.content)) { it is TextView && it.text.toString() == "Bottom left" })

        prefs.setFocusIndicator(ShoulderButton.R2, false)
        val again = open().get()
        assertNotNull(find(again.findViewById(android.R.id.content)) { it is TextView && it.text.toString() == "Off" })
    }
}
