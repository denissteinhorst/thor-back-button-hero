package dev.steinhorst.backbuttonhero

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.actions.Ayn
import dev.steinhorst.backbuttonhero.input.ShoulderButton
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.support.selectSettingsPage
import dev.steinhorst.backbuttonhero.support.useEarlierDefaults
import java.text.Collator
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/** The hotkey action list: its order and the search that also reaches into groups and AYN tiles. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-xxhdpi")
class ActionChooserTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun earlierDefaults() {
        Prefs(context).useEarlierDefaults()
    }

    private val order = Collator.getInstance(Locale.GERMAN)
    private fun entry(value: String, label: String, option: String? = null) =
        ActionChooser.Entry(value, label, null, option)

    private fun labels(rows: List<ActionChooser.Row>) = rows.map {
        when (it) {
            is ActionChooser.Row.Item -> it.entry.label
            ActionChooser.Row.Divider -> "---"
            ActionChooser.Row.NoResults -> "(none)"
        }
    }

    @Test
    fun `without a search Not set leads, then a rule, then everything from A to Z`() {
        val actions = listOf(entry("@z", "Zurück"), entry("", "Nicht belegt"), entry("@ae", "Ändern"), entry("@app", "App starten…"), entry("@b", "bluetooth"))
        assertEquals(
            listOf("Nicht belegt", "---", "Ändern", "App starten…", "bluetooth", "Zurück"),
            labels(ActionChooser.rows(actions, listOf(entry("@g", "G › Up", "up")), "  ", order)),
        )
        assertEquals(listOf("A", "B"), labels(ActionChooser.rows(listOf(entry("@b", "B"), entry("@a", "A")), emptyList(), "", order)))
    }

    @Test
    fun `a search matches every word against group and option names and says when nothing matches`() {
        val actions = listOf(entry("@volume", "Volume"), entry("@brightness", "Brightness"), entry("@home", "Home"))
        val options = listOf(
            entry("@volume", "Volume › Up", "up"),
            entry("@volume", "Volume › Mute", "mute"),
            entry("@brightness", "Brightness › Up", "bup"),
        )
        assertEquals(listOf("Brightness › Up", "Volume › Up"), labels(ActionChooser.rows(actions, options, "UP", order)))
        assertEquals(listOf("Volume", "Volume › Mute", "Volume › Up"), labels(ActionChooser.rows(actions, options, " vol ", order)))
        assertEquals(listOf("Volume › Up"), labels(ActionChooser.rows(actions, options, "up  volume", order)))
        assertEquals(listOf("(none)"), labels(ActionChooser.rows(actions, options, "xyz", order)))
    }

    @Test
    fun `a search also finds keywords and descriptions, but options skip their group's description`() {
        val actions = listOf(
            ActionChooser.Entry("@volume", "Volume", "Changes the volume or mutes it."),
            ActionChooser.Entry("@shot", "Take screenshot", "Saves the screen.", keywords = "picture"),
        )
        val options = listOf(
            ActionChooser.Entry("@volume", "Volume › Up", "Changes the volume or mutes it.", "up", "louder"),
            ActionChooser.Entry("@volume", "Volume › Down", "Changes the volume or mutes it.", "down", "quieter"),
        )
        assertEquals(listOf("Volume › Up"), labels(ActionChooser.rows(actions, options, "louder", order)))
        assertEquals(listOf("Take screenshot"), labels(ActionChooser.rows(actions, options, "pic", order)))
        assertEquals(listOf("Volume"), labels(ActionChooser.rows(actions, options, "mutes", order)))
    }

    @Test
    @Config(qualifiers = "de-xxhdpi")
    fun `searching lauter finds raising the volume`() {
        val (activity, dialog) = openChooser("L1")
        search(dialog).setText("lauter")
        val raise = activity.getString(R.string.action_search_option, activity.getString(R.string.group_volume), activity.getString(R.string.group_up))
        assertEquals(listOf(raise), items(dialog.listView))
    }

    @Test
    @Config(qualifiers = "de-xxhdpi")
    fun `gaming slang leads to the matching action`() {
        val (activity, dialog) = openChooser("L1")
        fun hits(query: String): List<String> {
            search(dialog).setText(query)
            return items(dialog.listView)
        }
        assertTrue(activity.getString(R.string.ayn_performance) in hits("tdp"))
        assertTrue(activity.getString(R.string.ayn_vibration) in hits("Rumble"))
        assertTrue(activity.getString(R.string.ayn_direct_power) in hits("bypass"))
        assertTrue(activity.getString(R.string.shoulder_macro) in hits("autofire"))
        assertTrue(activity.getString(R.string.action_search_option, activity.getString(R.string.ayn_performance), activity.getString(R.string.ayn_performance_high)) in hits("beast"))
    }

    private fun find(root: View, match: (View) -> Boolean): View? {
        if (match(root)) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), match)?.let { return it }
        return null
    }

    private fun openChooser(row: String): Pair<SettingsActivity, AlertDialog> {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        var view = checkNotNull(find(activity.findViewById(android.R.id.content)) { it is TextView && it.text.toString() == row })
        while (!view.isClickable) view = view.parent as View
        view.performClick()
        return activity to ShadowAlertDialog.getLatestAlertDialog() as AlertDialog
    }

    private fun items(list: ListView) = (0 until list.adapter.count).map { list.adapter.getItem(it).toString().substringBefore('\n') }

    private fun search(dialog: AlertDialog): EditText = dialog.window!!.decorView.findViewWithTag(ActionChooser.SEARCH_TAG)

    @Test
    fun `the list opens sorted with a disabled rule below Not set and the current action checked`() {
        val (activity, dialog) = openChooser("L1")
        val list = dialog.listView
        val names = items(list)
        assertEquals(activity.getString(R.string.shoulder_none), names[0])
        assertFalse(list.adapter.isEnabled(1))
        val rest = names.drop(2)
        assertEquals(rest.sortedWith(Collator.getInstance(Locale.ENGLISH)), rest)
        Prefs(context).setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_VOLUME_DOWN)
        val (_, again) = openChooser("L1")
        assertEquals(activity.getString(R.string.group_volume), items(again.listView)[again.listView.checkedItemPosition])
        assertFalse(search(again).hasFocus())
    }

    @Test
    fun `picking a group option from the search stores it and the X brings the full list back`() {
        val (activity, dialog) = openChooser("L1")
        val full = items(dialog.listView)
        val clear = dialog.window!!.decorView.findViewWithTag<View>(ActionChooser.CLEAR_TAG)
        assertEquals(View.GONE, clear.visibility)

        search(dialog).setText(activity.getString(R.string.group_mute))
        val hits = items(dialog.listView)
        val mute = activity.getString(R.string.action_search_option, activity.getString(R.string.group_volume), activity.getString(R.string.group_mute))
        assertTrue(mute in hits)
        assertEquals(View.VISIBLE, clear.visibility)

        clear.performClick()
        assertEquals("", search(dialog).text.toString())
        assertTrue(search(dialog).hasFocus())
        assertEquals(full, items(dialog.listView))
        assertEquals(View.GONE, clear.visibility)

        search(dialog).setText(activity.getString(R.string.group_mute))
        val index = items(dialog.listView).indexOf(mute)
        dialog.listView.performItemClick(null, index, index.toLong())
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(Prefs.SHOULDER_VOLUME_MUTE, Prefs(context).shoulderAction(ShoulderButton.L1))
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `picking an AYN option from the search stores the tile and its option`() {
        val (activity, dialog) = openChooser("L1")
        search(dialog).setText(activity.getString(R.string.ayn_performance_high))
        val label = activity.getString(R.string.action_search_option, activity.getString(R.string.ayn_performance), activity.getString(R.string.ayn_performance_high))
        val index = items(dialog.listView).indexOf(label)
        dialog.listView.performItemClick(null, index, index.toLong())
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val prefs = Prefs(context)
        assertEquals(Ayn.Group.PERFORMANCE.action, prefs.shoulderAction(ShoulderButton.L1))
        assertEquals("high", prefs.aynOption(ShoulderButton.L1, Ayn.Group.PERFORMANCE))
    }

    @Test
    fun `scrolling the results drops the field's focus`() {
        val (_, dialog) = openChooser("L1")
        val field = search(dialog)
        field.requestFocus()
        assertTrue(field.hasFocus())
        val list = dialog.listView
        shadowOf(list).onScrollListener.onScrollStateChanged(list, android.widget.AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL)
        assertFalse(field.hasFocus())
    }

    @Test
    fun `a search without hits shows a disabled notice`() {
        val (activity, dialog) = openChooser("L1")
        search(dialog).setText("zzzz-nothing")
        assertEquals(listOf(activity.getString(R.string.action_search_empty)), items(dialog.listView))
        assertFalse(dialog.listView.adapter.isEnabled(0))
    }
}
