package dev.steinhorst.backbuttonhero

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.support.selectSettingsPage
import org.junit.Assert.*
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
class SettingsNavigationTest {
    private fun open() = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible()

    private fun layout(activity: SettingsActivity, width: Int = 1000, height: Int = 600) {
        val root = activity.findViewById<View>(android.R.id.content)
        repeat(2) {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(activity.dp(width), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(activity.dp(height), View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, activity.dp(width), activity.dp(height))
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun root(activity: SettingsActivity) = activity.findViewById<View>(android.R.id.content)
    private fun page(activity: SettingsActivity, section: SettingsSection): ScrollView =
        root(activity).findViewWithTag("settings_page_${section.name}")

    private fun find(view: View, match: (View) -> Boolean): View? {
        if (match(view)) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), match)?.let { return it }
        return null
    }

    @Test
    fun `navigation is lazy reuses pages and does not change preferences`() {
        val activity = open().get()
        val prefs = Prefs(activity)
        var writes = 0
        val listener = { writes++; Unit }
        prefs.registerListener(listener)
        layout(activity)
        val basic = page(activity, SettingsSection.BASIC)
        assertNull(root(activity).findViewWithTag<View>("settings_page_SHOULDERS"))
        SettingsSection.entries.forEach {
            selectSettingsPage(activity, it)
            assertNotNull(page(activity, it))
            assertNotNull(find(page(activity, it)) { v -> v is TextView && v.text.toString() == activity.getString(it.hintRes) })
        }
        selectSettingsPage(activity, SettingsSection.BASIC)
        assertSame(basic, page(activity, SettingsSection.BASIC))
        assertEquals(0, writes)
        prefs.unregisterListener(listener)
    }

    @Test
    @Config(qualifiers = "en-w1000dp-h600dp-xxhdpi")
    fun `wide navigation focus does not select until confirmed and help has no page actions`() {
        val activity = open().get()
        layout(activity)
        val button = root(activity).findViewWithTag<View>("settings_nav_DPAD")
        assertNotNull(button)
        button.requestFocus()
        assertNotNull(page(activity, SettingsSection.BASIC))
        assertNull(root(activity).findViewWithTag<View>("settings_page_DPAD"))
        button.performClick()
        assertTrue(button.isSelected)
        val actions = page(activity, SettingsSection.DPAD).findViewWithTag<ViewGroup>("settings_page_actions")
        val topBar = root(activity).findViewWithTag<ViewGroup>("settings_top_bar")
        val warning = topBar.findViewWithTag<TextView>("settings_service_warning")
        assertNotNull(warning)
        assertEquals(activity.serviceStatusText(ServiceStatus.OFF), warning.text.toString())
        assertEquals(android.view.Gravity.END, warning.gravity and android.view.Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
        assertNotNull(actions)
        assertNull(find(topBar) { it is TextView && it.text.toString() == activity.getString(R.string.settings_preset) })
        assertNull(find(topBar) { it is TextView && it.text.toString() == activity.getString(R.string.settings_unbind) })
        val navigation = root(activity).findViewWithTag<View>("settings_navigation")
        val content = root(activity) as ViewGroup
        val colors = listOf(topBar.background, navigation.background, content.getChildAt(0).background)
            .map { (it as android.graphics.drawable.ColorDrawable).color }
        assertEquals(3, colors.toSet().size)
        selectSettingsPage(activity, SettingsSection.HELP)
        listOf(R.string.settings_preset, R.string.settings_unbind).forEach { label ->
            assertNull(find(root(activity)) { it is TextView && it.text.toString() == activity.getString(label) })
        }
    }

    @Test
    fun `compact menu cancels without navigation and selects all six pages`() {
        val activity = open().get()
        layout(activity, 380)
        val menu = root(activity).findViewWithTag<View>("settings_menu")
        assertEquals(View.VISIBLE, menu.visibility)
        menu.performClick()
        val dialog = ShadowDialog.getLatestDialog()
        dialog.cancel()
        assertNotNull(page(activity, SettingsSection.BASIC))
        SettingsSection.entries.forEach {
            selectSettingsPage(activity, it)
            assertFalse(ShadowDialog.getLatestDialog().isShowing)
            assertNotNull(page(activity, it))
        }
    }

    @Test
    fun `active page survives recreation but every menu selection resets content to top`() {
        val controller = open()
        var activity = controller.get()
        layout(activity, 1000, 400)
        page(activity, SettingsSection.BASIC).scrollTo(0, activity.dp(120))
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        layout(activity, 1000, 400)
        page(activity, SettingsSection.SHOULDERS).scrollTo(0, activity.dp(90))
        controller.recreate()
        activity = controller.get()
        layout(activity, 1000, 400)
        assertEquals(activity.dp(90), page(activity, SettingsSection.SHOULDERS).scrollY)
        selectSettingsPage(activity, SettingsSection.BASIC)
        layout(activity, 1000, 400)
        assertEquals(0, page(activity, SettingsSection.BASIC).scrollY)
        page(activity, SettingsSection.BASIC).scrollTo(0, activity.dp(80))
        selectSettingsPage(activity, SettingsSection.BASIC)
        layout(activity, 380, 400)
        assertNotNull(page(activity, SettingsSection.BASIC))
        assertEquals(0, page(activity, SettingsSection.BASIC).scrollY)
    }

    @Test
    fun `cached switches reflect changes made while away without writing them back`() {
        val controller = open()
        val activity = controller.get()
        val prefs = Prefs(activity)
        val double = find(page(activity, SettingsSection.BASIC)) {
            it is Switch && it.contentDescription == activity.getString(R.string.double_enabled)
        } as Switch
        controller.pause()
        prefs.doubleTapEnabled = false
        controller.resume()
        assertFalse(double.isChecked)
        assertFalse(prefs.doubleTapEnabled)
    }

    @Test
    fun `reset invalidates both visited and unvisited pages and keeps current destination`() {
        val activity = open().get()
        Prefs(activity).apply {
            doubleTapEnabled = false
            setShoulderAction(ShoulderButton.L1, "")
        }
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        selectSettingsPage(activity, SettingsSection.SETTINGS)
        var reset = find(root(activity)) { it is TextView && it.text.toString() == activity.getString(R.string.reset_settings) }!!
        while (!reset.isClickable) reset = reset.parent as View
        reset.performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(Prefs(activity).doubleTapEnabled)
        assertNotNull(page(activity, SettingsSection.SETTINGS))
        selectSettingsPage(activity, SettingsSection.BASIC)
        assertTrue((find(page(activity, SettingsSection.BASIC)) {
            it is Switch && it.contentDescription == activity.getString(R.string.double_enabled)
        } as Switch).isChecked)
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        assertNotNull(find(page(activity, SettingsSection.SHOULDERS)) {
            it is TextView && it.text.toString() == activity.getString(R.string.shoulder_flick_left)
        })
        selectSettingsPage(activity, SettingsSection.DPAD)
        assertNotNull(find(page(activity, SettingsSection.DPAD)) {
            it is TextView && it.text.toString() == activity.getString(R.string.shoulder_brightness_up)
        })
    }

    @Test
    fun `indicator intent opens an unvisited D-pad page and its popup`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Prefs(context).setShoulderAction(DpadDirection.UP, Prefs.SHOULDER_CONTROLLER_FOCUS)
        val intent = Intent(context, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_INDICATOR_BUTTON, DpadDirection.UP.prefKey)
        val activity = Robolectric.buildActivity(SettingsActivity::class.java, intent).setup().visible().get()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(page(activity, SettingsSection.DPAD))
        assertTrue(ShadowDialog.getLatestDialog().isShowing)
    }

    @Test
    @Config(qualifiers = "en-w320dp-h640dp-xxhdpi")
    fun `compact indicator grid fits the dialog and preserves touch target sizes`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_INDICATOR_BUTTON, ShoulderButton.R2.prefKey)
        val activity = Robolectric.buildActivity(SettingsActivity::class.java, intent).setup().visible().get()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog()
        val decor = dialog.window!!.decorView as ViewGroup
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(dialog.window!!.attributes.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(activity.dp(560), View.MeasureSpec.AT_MOST),
        )
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        listOf("Top left", "Top center", "Top right", "Center left", "Center", "Center right",
            "Bottom left", "Bottom center", "Bottom right").forEach { label ->
            val cell = find(decor) { it.contentDescription?.toString().let { value -> value == label || value?.startsWith("$label,") == true } }!!
            assertTrue("$label width", cell.width >= activity.dp(48))
            assertTrue("$label height", cell.height >= activity.dp(48))
            val bounds = android.graphics.Rect()
            cell.getDrawingRect(bounds)
            decor.offsetDescendantRectToMyCoords(cell, bounds)
            assertTrue("$label stays inside dialog", bounds.left >= 0 && bounds.right <= decor.width)
        }
    }

    @Test
    @Config(qualifiers = "en-w1000dp-h600dp-xxhdpi")
    fun `main and nested chooser chevrons share the same trailing edge`() {
        val activity = open().get()
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        layout(activity)
        val edges = mutableListOf<Int>()
        fun collect(view: View) {
            if (view.tag == "settings_chooser_chevron" && view.isShown) {
                val location = IntArray(2)
                view.getLocationInWindow(location)
                edges += location[0] + view.width
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
        }
        collect(root(activity))
        assertTrue(edges.size >= 6)
        assertEquals(1, edges.toSet().size)
    }

    @Test
    fun `range sliders and text share edges before their control at both widths`() {
        val activity = open().get()
        fun left(view: View): Int = IntArray(2).also { view.getLocationInWindow(it) }[0]
        fun check(control: View) {
            val row = control.parent as ViewGroup
            val slider = find(row.parent as View) { it is android.widget.SeekBar } as android.widget.SeekBar
            val column = slider.parent as ViewGroup
            val label = column.getChildAt(0)
            val hint = column.getChildAt(2)
            val text = row.getChildAt(0)
            val end = left(control) - activity.dp(12)
            assertEquals(end, left(text) + text.width - text.paddingEnd)
            listOf(label, slider, hint).forEach {
                assertEquals(left(label), left(it))
                assertEquals(end, left(it) + it.width)
            }
            assertEquals(0, slider.paddingStart)
            assertEquals(0, slider.paddingEnd)
            assertEquals(0, slider.thumbOffset)
        }
        for (width in listOf(1000, 380)) {
            selectSettingsPage(activity, SettingsSection.BASIC)
            layout(activity, width)
            listOf(R.string.double_enabled, R.string.tap_hold_enabled, R.string.hold_enabled).forEach { title ->
                check(find(root(activity)) {
                    it is Switch && it.contentDescription == activity.getString(title)
                }!!)
            }
            selectSettingsPage(activity, SettingsSection.SETTINGS)
            layout(activity, width)
            val title = find(root(activity)) { it is TextView && it.text.toString() == activity.getString(R.string.service_toggle_buttons) }!!
            val row = title.parent.parent as View
            check(row.findViewWithTag("settings_chooser_chevron"))
        }
    }

}
