package dev.steinhorst.backbuttonhero

import android.app.AlertDialog
import android.content.Context
import android.os.Looper
import android.text.Spanned
import android.text.style.ImageSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.input.DpadDirection
import dev.steinhorst.backbuttonhero.input.FaceButton
import dev.steinhorst.backbuttonhero.input.HomeLayerButton
import dev.steinhorst.backbuttonhero.input.ShoulderButton
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.settings.OverviewControl
import dev.steinhorst.backbuttonhero.settings.SettingsSection
import dev.steinhorst.backbuttonhero.support.Golden
import dev.steinhorst.backbuttonhero.support.ViewTree
import dev.steinhorst.backbuttonhero.support.selectSettingsPage
import dev.steinhorst.backbuttonhero.support.useEarlierDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** Dual mode's settings: the switch, the Home | Back columns, the overview's layer switch and the layered Unbind. */
@RunWith(RobolectricTestRunner::class)
class DualModeSettingsTest {
    private val prefs = Prefs(ApplicationProvider.getApplicationContext<Context>())

    @Before
    fun bindings() {
        prefs.useEarlierDefaults()
        prefs.dualMode = true
        // The two sides differ in what they show below the action, so their cards differ in height.
        prefs.setShoulderAction(HomeLayerButton.of(ShoulderButton.L1), Prefs.SHOULDER_RECORD_VIDEO)
        prefs.setShoulderAction(HomeLayerButton.of(FaceButton.A), Prefs.SHOULDER_APP_CONTROL)
        prefs.setShoulderAction(HomeLayerButton.of(DpadDirection.UP), "")
    }

    private fun open() = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible().get()
    private fun root(activity: SettingsActivity) = activity.findViewById<View>(android.R.id.content)

    private fun all(view: View, match: (View) -> Boolean): List<View> =
        (if (match(view)) listOf(view) else emptyList()) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it), match) } else emptyList()

    private fun text(view: View, value: String) = all(view) { it is TextView && it.isShown && it.text.toString() == value }.firstOrNull()

    private fun snapshot(name: String) {
        val activity = open()
        val root = root(activity)
        listOf(SettingsSection.OVERVIEW, SettingsSection.BASIC, SettingsSection.FACE, SettingsSection.SHOULDERS,
            SettingsSection.STICKS, SettingsSection.DPAD).forEach { section ->
            selectSettingsPage(activity, section)
            Golden.verify("dual_${name}_${section.name.lowercase()}", ViewTree.describe(root))
        }
    }

    @Config(qualifiers = "en-xxhdpi")
    @Test
    fun `pages in English, light theme`() = snapshot("en_light")

    @Config(qualifiers = "de-xxhdpi")
    @Test
    fun `pages in German, light theme`() = snapshot("de_light")

    @Config(qualifiers = "en-w820dp-h460dp-night-xxhdpi")
    @Test
    fun `wide pages in English, dark theme`() = snapshot("wide_en_dark")

    @Test
    fun `every binding pair starts on one line, Back + Home alone and blocked`() {
        val activity = open()
        selectSettingsPage(activity, SettingsSection.BASIC)
        val page = root(activity).findViewWithTag<View>("settings_page_BASIC")
        assertNotNull(page.findViewWithTag<View>("settings_dual_header"))
        val rows = all(page) { it.tag == "settings_dual_row" }.map { it as LinearLayout }
        // Four gestures, then Home, Start and Select.
        assertEquals(7, rows.size)
        rows.forEach { row ->
            val (home, rule, back) = (0 until row.childCount).map(row::getChildAt)
            assertEquals(home.top, back.top)
            assertEquals(row.height, rule.height)
        }
        val homeRow = rows[4]
        assertEquals(0, (homeRow.getChildAt(0) as ViewGroup).childCount)
        assertNotNull(text(homeRow, activity.getString(R.string.dual_mode_combo_blocked)))
    }

    @Test
    fun `section headings are centred over both columns, and only in dual mode`() {
        fun heading(activity: SettingsActivity) = checkNotNull(text(root(activity), activity.getString(R.string.single_section_title))) as TextView
        val dual = open()
        selectSettingsPage(dual, SettingsSection.BASIC)
        assertEquals(Gravity.CENTER_HORIZONTAL, heading(dual).gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
        prefs.dualMode = false
        val single = open()
        selectSettingsPage(single, SettingsSection.BASIC)
        assertEquals(Gravity.START, heading(single).gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
    }

    @Test
    fun `the overview's layer tabs are named like the column heads`() {
        val activity = open()
        val tabs = checkNotNull(root(activity).findViewWithTag<ViewGroup>("settings_overview_layers"))
        assertEquals(activity.getString(R.string.dual_column_home), tabs.findViewWithTag<View>("settings_tab_0").contentDescription)
        assertEquals(activity.getString(R.string.dual_column_back), tabs.findViewWithTag<View>("settings_tab_1").contentDescription)
        assertEquals(1, (tabs.findViewWithTag<TextView>("settings_tab_0").text as Spanned).getSpans(0, 1, ImageSpan::class.java).size)
    }

    @Test
    fun `the switch turns Home as Back off and the pages back to one column`() {
        prefs.dualMode = false
        prefs.homeAsBack = true
        val activity = open()
        selectSettingsPage(activity, SettingsSection.SETTINGS)
        val toggle = checkNotNull(text(root(activity), activity.getString(R.string.dual_mode)))
        generateSequence(toggle) { it.parent as? View }.first { it.isClickable }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(prefs.dualMode)
        assertFalse(prefs.homeAsBack)
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        assertEquals(4, all(root(activity)) { it.tag == "settings_dual_row" && it.isShown }.size)
        selectSettingsPage(activity, SettingsSection.SETTINGS)
        val asBack = checkNotNull(text(root(activity), activity.getString(R.string.home_as_back)))
        generateSequence(asBack) { it.parent as? View }.first { it.isClickable }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(prefs.dualMode)
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        assertTrue(all(root(activity)) { it.tag == "settings_dual_row" && it.isShown }.isEmpty())
        // Its bindings rest, untouched.
        assertEquals(Prefs.SHOULDER_RECORD_VIDEO, prefs.shoulderAction(HomeLayerButton.of(ShoulderButton.L1)))
    }

    @Test
    fun `Unbind asks for the layers and clears only those`() {
        val activity = open()
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        val unbind = checkNotNull(text(root(activity), activity.getString(R.string.settings_unbind)))
        generateSequence(unbind) { it.parent as? View }.first { it.isClickable }.performClick()
        val chooser = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals(3, chooser.listView.adapter.count)
        assertEquals(activity.getString(R.string.unbind_layer_home), chooser.listView.adapter.getItem(0))
        shadowOf(chooser.listView).performItemClick(0)
        val preview = ShadowDialog.getLatestDialog() as AlertDialog
        preview.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("", prefs.shoulderAction(HomeLayerButton.of(ShoulderButton.L1)))
        assertEquals(Prefs.SHOULDER_FLICK_LEFT, prefs.shoulderAction(ShoulderButton.L1))
    }

    @Test
    fun `the overview switches between the layers`() {
        prefs.setShoulderAction(HomeLayerButton.of(ShoulderButton.L2), Prefs.SHOULDER_NOTIFICATIONS)
        val activity = open()
        val root = root(activity)
        val tabs = checkNotNull(root.findViewWithTag<ViewGroup>("settings_overview_layers"))
        val overview = root.findViewWithTag<View>("settings_overview")
        assertNotNull(text(overview, activity.getString(R.string.overview_home_dual_mode)))
        assertNull(text(overview, activity.getString(R.string.overview_action_notifications)))
        tabs.findViewWithTag<View>("settings_tab_0").performClick()
        assertNotNull(text(overview, activity.getString(R.string.overview_action_notifications)))
        // Home holds its own gestures there; Back has none in the Home layer.
        assertNull(overview.findViewWithTag<View>("settings_overview_${OverviewControl.BACK.name}"))
        assertNotNull(overview.findViewWithTag<View>("settings_overview_${OverviewControl.HOME.name}"))
    }
}
