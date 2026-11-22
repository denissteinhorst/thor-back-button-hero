package dev.steinhorst.backbuttonhero

import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.input.ShoulderButton
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.settings.ActionChooser
import dev.steinhorst.backbuttonhero.settings.SettingsSection
import dev.steinhorst.backbuttonhero.support.selectSettingsPage
import dev.steinhorst.backbuttonhero.support.useEarlierDefaults
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/** A freshly chosen action leads through its options, one dialog after the other. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-xxhdpi")
class OptionGuideTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs get() = Prefs(context)

    @Before
    fun earlierDefaults() {
        prefs.useEarlierDefaults()
    }

    private fun find(root: View, match: (View) -> Boolean): View? {
        if (match(root)) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), match)?.let { return it }
        return null
    }

    /** The action list, holding the focus as a shown dialog does on the device. */
    private fun openChooser(activity: SettingsActivity, row: String): AlertDialog {
        controllers.getValue(activity).windowFocusChanged(false)
        var view = checkNotNull(find(activity.findViewById(android.R.id.content)) { it is TextView && it.text.toString() == row })
        while (!view.isClickable) view = view.parent as View
        view.performClick()
        return ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
    }

    private fun pick(dialog: AlertDialog, label: String) {
        val list = dialog.listView
        val index = (0 until list.adapter.count).first { list.adapter.getItem(it).toString().substringBefore('\n') == label }
        list.performItemClick(null, index, index.toLong())
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** A dialog took the focus and the page has it back once it closes; the walk judges the dialog then. */
    private fun focusReturns(activity: SettingsActivity) {
        controllers.getValue(activity).windowFocusChanged(false)
        controllers.getValue(activity).windowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
    }

    private fun latest() = ShadowAlertDialog.getLatestAlertDialog() as AlertDialog

    /** The button the dialog edits, above its title. */
    private fun heading(dialog: AlertDialog): Pair<String, String> {
        val title = dialog.window!!.decorView.findViewWithTag<ViewGroup>("settings_binding_title")
        return (title.getChildAt(0) as TextView).text.toString() to (title.getChildAt(1) as TextView).text.toString()
    }

    private fun confirm(dialog: AlertDialog): String = dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString()

    private val controllers = mutableMapOf<SettingsActivity, ActivityController<SettingsActivity>>()

    private fun open(): SettingsActivity {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        controllers[activity] = controller
        shadowOf(Looper.getMainLooper()).idle()
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        controller.windowFocusChanged(true)
        return activity
    }

    @Test
    fun `a group asks its option, then its screen, and the confirm button takes the preselected answer`() {
        val activity = open()
        pick(openChooser(activity, "L2"), activity.getString(R.string.group_volume))
        focusReturns(activity)
        val option = latest()
        // The function joins the path above, so it stays while the steps below it change.
        assertEquals("L2 › Volume" to activity.getString(R.string.ayn_option_title), heading(option))
        assertEquals(activity.getString(R.string.guide_next), confirm(option))
        assertEquals(activity.getString(android.R.string.cancel), option.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
        option.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        focusReturns(activity)
        val screen = latest()
        assertEquals("L2 › Volume" to activity.getString(R.string.level_target_dialog_title), heading(screen))
        assertEquals(activity.getString(android.R.string.ok), confirm(screen))
        screen.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        focusReturns(activity)
        assertSame(screen, latest())
        assertEquals(Prefs.SHOULDER_VOLUME_UP, prefs.shoulderAction(ShoulderButton.L2))
        assertEquals(Prefs.TARGET_BOTH, prefs.levelTarget(ShoulderButton.L2))
    }

    @Test
    fun `an option found by the search is not asked again, only the screen follows`() {
        val activity = open()
        val chooser = openChooser(activity, "L2")
        chooser.window!!.decorView.findViewWithTag<EditText>(ActionChooser.SEARCH_TAG).setText("volume")
        pick(chooser, activity.getString(R.string.action_search_option, activity.getString(R.string.group_volume), activity.getString(R.string.group_down)))
        focusReturns(activity)
        val screen = latest()
        assertEquals("L2 › Volume" to activity.getString(R.string.level_target_dialog_title), heading(screen))
        assertEquals(activity.getString(android.R.string.ok), confirm(screen))
        pick(screen, activity.getString(R.string.level_target_bottom))
        focusReturns(activity)
        assertSame(screen, latest())
        assertEquals(Prefs.SHOULDER_VOLUME_DOWN, prefs.shoulderAction(ShoulderButton.L2))
        assertEquals(Prefs.DISPLAY_BOTTOM, prefs.levelTarget(ShoulderButton.L2))
    }

    @Test
    fun `cancelling a step ends the walk and keeps the action`() {
        val activity = open()
        pick(openChooser(activity, "L2"), activity.getString(R.string.group_volume))
        focusReturns(activity)
        val option = latest()
        option.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        focusReturns(activity)
        assertSame(option, latest())
        assertEquals(Prefs.SHOULDER_VOLUME_UP, prefs.shoulderAction(ShoulderButton.L2))
    }

    @Test
    fun `a single option dialog opened on its own confirms with OK`() {
        prefs.setShoulderAction(ShoulderButton.L2, Prefs.SHOULDER_VOLUME_UP)
        val activity = open()
        val view = checkNotNull(find(activity.findViewById(android.R.id.content)) {
            it.contentDescription?.toString() == activity.getString(R.string.level_target_accessibility, "L2", activity.getString(R.string.level_target_both))
        })
        view.performClick()
        val screen = latest()
        assertEquals(activity.getString(android.R.string.ok), confirm(screen))
        screen.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(Prefs.TARGET_BOTH, prefs.levelTarget(ShoulderButton.L2))
    }
}
