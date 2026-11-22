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
import dev.steinhorst.backbuttonhero.actions.Ayn
import dev.steinhorst.backbuttonhero.input.DpadDirection
import dev.steinhorst.backbuttonhero.input.ShoulderButton
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.settings.SettingsSection
import dev.steinhorst.backbuttonhero.settings.dp
import dev.steinhorst.backbuttonhero.support.selectSettingsPage
import dev.steinhorst.backbuttonhero.support.useEarlierDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    @org.junit.Before
    fun earlierDefaults() {
        Prefs(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()).apply {
            useEarlierDefaults()
            // The 2.0 welcome would otherwise be the latest dialog of every fresh start.
            welcomeSeen = true
        }
    }


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
    fun `toggle buttons chooser offers stick, shoulder, Start and Back choices and persists selection`() {
        val activity = open().get()
        selectSettingsPage(activity, SettingsSection.SETTINGS)
        for ((index, expected) in listOf(Prefs.TOGGLE_L1_R1, Prefs.TOGGLE_L2_R2, Prefs.TOGGLE_BOTH,
            Prefs.TOGGLE_START_SELECT, Prefs.TOGGLE_BACK_HOME).withIndex()) {
            clickRow(activity, activity.getString(R.string.service_toggle_buttons))
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertEquals(6, dialog.listView.adapter.count)
            assertEquals(listOf("L1 + R1", "L2 + R2", "L3 + R3", "Start + Select", "Back + Home")[index], dialog.listView.adapter.getItem(index))
            dialog.listView.performItemClick(null, index, index.toLong())
            assertEquals(expected, Prefs(context).toggleButtons)
            assertFalse(dialog.isShowing)
        }
    }

    @Test
    fun `toggle duration slider stores the selected hold time`() {
        val activity = open().get()
        selectSettingsPage(activity, SettingsSection.SETTINGS)
        val root = activity.findViewById<View>(android.R.id.content)
        val slider = find(root) { it is SeekBar && it.max == 29 } as SeekBar
        shadowOf(slider).onSeekBarChangeListener.onProgressChanged(slider, 16, true)
        assertEquals(1700L, Prefs(context).toggleHoldMs)
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
        assertEquals(0, bar.paddingStart)
        assertEquals(0, bar.paddingEnd)
        assertEquals(0, bar.thumbOffset)
        shadowOf(bar).onSeekBarChangeListener.onProgressChanged(bar, 8, true)

        assertEquals(1000L, Prefs(context).focusDuration(ShoulderButton.R2))
    }

    @Test
    fun `the row reports the chosen position, or Off when the indicator is hidden`() {
        val prefs = Prefs(context)
        prefs.setFocusPosition(ShoulderButton.R2, Prefs.FOCUS_POS_BOTTOM_LEFT)
        val activity = open().get()
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        assertNotNull(find(activity.findViewById(android.R.id.content)) { it is TextView && it.text.toString() == "Bottom left" })

        prefs.setFocusIndicator(ShoulderButton.R2, false)
        val again = open().get()
        selectSettingsPage(again, SettingsSection.SHOULDERS)
        assertNotNull(find(again.findViewById(android.R.id.content)) { it is TextView && it.text.toString() == "Off" })
    }

    @Test
    fun `action list says below each entry what it does`() {
        val activity = open().get()
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        clickRow(activity, "L1")
        val adapter = ShadowAlertDialog.getLatestAlertDialog().listView.adapter
        val items = (0 until adapter.count).filter { adapter.isEnabled(it) }.map { adapter.getItem(it).toString() }
        fun entry(name: Int) = items.first { it.substringBefore('\n') == activity.getString(name) }
        // "Not set" does nothing, so it stays a single line; everything else carries its description.
        assertEquals(activity.getString(R.string.shoulder_none), items.first())
        assertTrue(items.drop(1).all { '\n' in it })
        assertEquals(
            "${activity.getString(R.string.shoulder_app_control)}\n${activity.getString(R.string.action_desc_app_control)}",
            entry(R.string.shoulder_app_control),
        )
        listOf(R.string.group_media, R.string.shoulder_status, R.string.ayn_refresh_rate, R.string.ayn_stick_light,
            R.string.ayn_virtual_mouse, R.string.ayn_do_not_disturb, R.string.ayn_mute_microphone).forEach { entry(it) }
    }

    @Test
    fun `restart joins the app-control modes and asks before it can be assigned`() {
        val activity = open().get()
        val prefs = Prefs(context)
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        chooseAction(activity, "L1", R.string.shoulder_app_control)
        clickRow(activity, activity.getString(R.string.app_control_minimize))
        val modes = ShadowAlertDialog.getLatestAlertDialog()
        val restart = (0 until modes.listView.adapter.count).first { modes.listView.adapter.getItem(it).toString() == activity.getString(R.string.app_control_restart) }
        modes.listView.performItemClick(null, restart, restart.toLong())
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(Prefs.APP_MINIMIZE, prefs.appMode(ShoulderButton.L1))
        val warning = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(activity.getString(R.string.app_control_restart_use), warning.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        warning.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(Prefs.APP_RESTART, prefs.appMode(ShoulderButton.L1))
    }

    private fun chooseAction(activity: SettingsActivity, title: String, actionRes: Int) {
        clickRow(activity, title)
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val adapter = dialog.listView.adapter
        val index = (0 until adapter.count).first { adapter.getItem(it).toString().substringBefore('\n') == activity.getString(actionRes) }
        dialog.listView.performItemClick(null, index, index.toLong())
    }

    @Test
    fun `switch active app offers a persistent follow toggle on shoulder and D-pad hotkeys`() {
        val activity = open().get()
        val prefs = Prefs(context)
        for ((section, title, button) in listOf(
            Triple(SettingsSection.SHOULDERS, "L1", ShoulderButton.L1),
            Triple(SettingsSection.DPAD, activity.getString(R.string.dpad_up), DpadDirection.UP),
        )) {
            selectSettingsPage(activity, section)
            chooseAction(activity, title, R.string.shoulder_switch_app)
            assertEquals(Prefs.SHOULDER_SWITCH_APP, prefs.shoulderAction(button))
            val root = activity.findViewById<View>(android.R.id.content)
            val label = find(root) { it is TextView && it.text.toString() == activity.getString(R.string.switch_app_follow_focus) }!!
            var row: View = label
            while (!row.isClickable) row = row.parent as View
            val toggle = find(row) { it is Switch } as Switch
            assertTrue(toggle.isChecked)
            row.performClick()
            assertFalse(prefs.switchAppFollowFocus(button))
            val group = row.parent.parent as View
            chooseAction(activity, title, R.string.shoulder_screenshot)
            assertEquals(View.GONE, group.visibility)
            chooseAction(activity, title, R.string.shoulder_switch_app)
            assertEquals(View.VISIBLE, group.visibility)
            assertFalse(toggle.isChecked)
        }
    }

    @Test
    fun `Home picks its screen from the same top and bottom list as every screen row`() {
        val prefs = Prefs(context)
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_HOME)
        val activity = open().get()
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        val root = activity.findViewById<View>(android.R.id.content)
        val screen = find(root) { it.isShown && it.contentDescription?.toString() == "L1 screen: Bottom screen. Double-tap to change." }!!
        screen.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(listOf("Top screen", "Bottom screen"), (0 until dialog.listView.adapter.count).map { dialog.listView.adapter.getItem(it).toString() })
        dialog.listView.performItemClick(null, 0, 0L)
        assertEquals(Prefs.DISPLAY_TOP, prefs.shoulderDisplay(ShoulderButton.L1))
        assertEquals("L1 screen: Top screen. Double-tap to change.", screen.contentDescription.toString())
    }

    @Test
    fun `screen-like grouped options read as a screen row, other groups keep the option row`() {
        val prefs = Prefs(context)
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_RECENTS)
        prefs.setShoulderAction(ShoulderButton.R1, Ayn.Group.DISPLAY.action)
        prefs.setShoulderAction(ShoulderButton.L2, Ayn.Group.FAN.action)
        val activity = open().get()
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        val root = activity.findViewById<View>(android.R.id.content)
        fun shown(text: String) = find(root) { it.isShown && it.contentDescription?.toString() == text }
        assertNotNull(shown("L1 screen: Top screen. Double-tap to change."))
        assertNotNull(shown("R1 screen: Top screen. Double-tap to change."))
        assertNotNull(shown(activity.getString(R.string.ayn_option_accessibility, "L2", "Ask")))
    }

    @Test
    fun `focus subtree hides for other actions and is reused when focus is selected again`() {
        val activity = open().get()
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        val root = activity.findViewById<View>(android.R.id.content)
        val group = root.findViewWithTag<View>("settings_focus_options")
        assertNotNull(group)
        assertEquals(View.VISIBLE, group.visibility)
        val target = group.findViewWithTag<View>("settings_focus_target")
        assertTrue(target.minimumHeight >= activity.dp(48))
        chooseAction(activity, "R2", R.string.shoulder_screenshot)
        assertEquals(View.GONE, group.visibility)
        // Every screen row of R2 reads "R2 screen:"; the shown one is the screenshot's.
        val screen = find(root) { it.isShown && it.contentDescription?.toString()?.startsWith("R2 screen:") == true }!!
        assertEquals(View.VISIBLE, screen.visibility)
        val subtree = screen.parent.parent as View
        assertEquals("settings_sub_options", subtree.tag)
        assertEquals(View.VISIBLE, subtree.visibility)
        screen.performClick()
        val screenDialog = ShadowAlertDialog.getLatestAlertDialog()
        screenDialog.listView.performItemClick(null, 1, 1L)
        assertEquals(Prefs.DISPLAY_BOTTOM, Prefs(context).levelTarget(ShoulderButton.R2))
        chooseAction(activity, "R2", R.string.shoulder_controller_focus)
        assertSame(group, root.findViewWithTag<View>("settings_focus_options"))
        assertEquals(View.VISIBLE, group.visibility)
        assertEquals(View.GONE, screen.visibility)
        assertEquals(View.GONE, subtree.visibility)
    }

    @Test
    fun `D-pad focus subtree edits only its own focus target and indicator`() {
        val activity = open().get()
        val prefs = Prefs(context)
        prefs.setShoulderAction(DpadDirection.UP, Prefs.SHOULDER_CONTROLLER_FOCUS)
        selectSettingsPage(activity, SettingsSection.DPAD)
        val group = activity.findViewById<View>(android.R.id.content).findViewWithTag<View>("settings_focus_options")
        val screenBefore = prefs.levelTarget(DpadDirection.UP)
        group.findViewWithTag<View>("settings_focus_target").performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.listView.performItemClick(null, 1, 1)
        assertEquals(Prefs.DISPLAY_BOTTOM, prefs.focusTarget(DpadDirection.UP))
        assertEquals(screenBefore, prefs.levelTarget(DpadDirection.UP))
        assertEquals(Prefs.FOCUS_TOGGLE, prefs.focusTarget(ShoulderButton.R2))
        group.findViewWithTag<View>("settings_focus_indicator").performClick()
        find(dialogView()) { it.contentDescription?.toString() == "Top left" }!!.performClick()
        assertEquals(Prefs.FOCUS_POS_TOP_LEFT, prefs.focusPosition(DpadDirection.UP))
        assertEquals(Prefs.DEFAULT_FOCUS_POSITION, prefs.focusPosition(ShoulderButton.R2))
    }
}
