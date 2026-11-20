package dev.steinhorst.backbuttonhero

import dev.steinhorst.backbuttonhero.support.useEarlierDefaults
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
    @org.junit.Before
    fun earlierDefaults() {
        Prefs(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()).useEarlierDefaults()
    }

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
        // A short pill in the bar; the full sentence lives in the blocker card and on the Help page.
        assertEquals(activity.serviceChipText(ServiceStatus.OFF), warning.text.toString())
        assertEquals(activity.serviceStatusText(ServiceStatus.OFF), warning.contentDescription.toString())
        assertEquals(1, warning.maxLines)
        assertNotNull(actions)
        assertNull(find(topBar) { it is TextView && it.text.toString() == activity.getString(R.string.settings_unbind) })
        val navigation = root(activity).findViewWithTag<View>("settings_navigation")
        val content = root(activity) as ViewGroup
        val colors = listOf(topBar.background, navigation.background, content.getChildAt(0).background)
            .map { (it as android.graphics.drawable.ColorDrawable).color }
        assertEquals(3, colors.toSet().size)
        selectSettingsPage(activity, SettingsSection.HELP)
        listOf(R.string.settings_unbind).forEach { label ->
            assertNull(find(root(activity)) { it is TextView && it.text.toString() == activity.getString(label) })
        }
    }

    @Test
    fun `decorative app icon sits directly before the page title in both layouts`() {
        listOf(380, 1000).forEach { width ->
            val activity = open().get()
            layout(activity, width)
            val icon = root(activity).findViewWithTag<android.widget.ImageView>("settings_app_icon")
            assertNotNull(icon)
            assertEquals(View.VISIBLE, icon.visibility)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, icon.importantForAccessibility)
            val row = icon.parent as ViewGroup
            val title = row.getChildAt(row.indexOfChild(icon) + 1) as TextView
            assertEquals(activity.getString(SettingsSection.BASIC.titleRes), title.text.toString())
            selectSettingsPage(activity, SettingsSection.HELP)
            assertEquals(activity.getString(SettingsSection.HELP.titleRes), title.text.toString())
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
            it is TextView && it.text.toString() == activity.getString(R.string.group_app_switcher)
        } as TextView
        controller.pause()
        prefs.doubleTapEnabled = false
        controller.resume()
        assertEquals(activity.getString(R.string.shoulder_none), double.text.toString())
        assertFalse(prefs.doubleTapEnabled)
    }

    @Test
    fun `the both-screens switch lives on the app switcher action, not on the Settings page`() {
        val controller = open()
        val activity = controller.get()
        val prefs = Prefs(activity)
        fun switch(section: SettingsSection) = find(page(activity, section)) {
            it is Switch && it.contentDescription == activity.getString(R.string.switcher_all_apps)
        } as Switch?
        // The factory double press opens the switcher, so its row carries the switch.
        val onRow = switch(SettingsSection.BASIC)!!
        assertTrue(onRow.isShown)
        assertFalse(onRow.isChecked)
        onRow.performClick()
        assertTrue(prefs.switcherShowsAllApps)
        selectSettingsPage(activity, SettingsSection.SETTINGS)
        assertNull(switch(SettingsSection.SETTINGS))
        // Another action hides it; the preference itself stays as it was.
        selectSettingsPage(activity, SettingsSection.BASIC)
        controller.pause()
        prefs.setShoulderAction(BackGestureButton.DOUBLE, Prefs.SHOULDER_HOME)
        controller.resume()
        assertFalse(onRow.isShown)
        assertTrue(prefs.switcherShowsAllApps)
        // Any row can take the action and gets its own switch, kept in step with the preference.
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_SWITCHER)
        controller.pause()
        controller.resume()
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        assertTrue(switch(SettingsSection.SHOULDERS)!!.isChecked)
    }

    @Test
    fun `an applied change invalidates both visited and unvisited pages and keeps current destination`() {
        val activity = open().get()
        Prefs(activity).apply {
            toggleButtons = Prefs.TOGGLE_L2_R2
            setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_FLICK_LEFT)
        }
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        selectSettingsPage(activity, SettingsSection.SETTINGS)
        var unbind = find(root(activity)) { it.isShown && it is TextView && it.text.toString() == activity.getString(R.string.settings_unbind) }!!
        while (!unbind.isClickable) unbind = unbind.parent as View
        unbind.performClick()
        ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Prefs.TOGGLE_NONE, Prefs(activity).toggleButtons)
        assertNotNull(page(activity, SettingsSection.SETTINGS))
        // The settings page unbinds the whole app; the cached shoulder page must show that, not its old L1.
        selectSettingsPage(activity, SettingsSection.SHOULDERS)
        val shoulders = page(activity, SettingsSection.SHOULDERS)
        assertNull(find(shoulders) { it is TextView && it.text.toString() == activity.getString(R.string.group_flick) })
        assertNotNull(find(shoulders) { it.contentDescription?.toString() == "L1 action: Not set. Double-tap to change." })
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
            // The three timing sliders (shown while their gesture has an action) share one set of edges.
            val sliders = mutableListOf<android.widget.SeekBar>()
            fun collectSliders(view: View) {
                if (view is android.widget.SeekBar && view.isShown) sliders += view
                if (view is ViewGroup) for (i in 0 until view.childCount) collectSliders(view.getChildAt(i))
            }
            collectSliders(page(activity, SettingsSection.BASIC))
            assertEquals(3, sliders.size)
            sliders.forEach { slider ->
                val column = slider.parent as ViewGroup
                val label = column.getChildAt(0)
                val hint = column.getChildAt(2)
                assertEquals(left(label), left(slider))
                assertEquals(left(label), left(hint))
                assertEquals(left(label) + label.width, left(slider) + slider.width)
                assertEquals(0, slider.paddingStart)
                assertEquals(0, slider.paddingEnd)
            }
            selectSettingsPage(activity, SettingsSection.SETTINGS)
            layout(activity, width)
            val title = find(root(activity)) { it is TextView && it.text.toString() == activity.getString(R.string.service_toggle_buttons) }!!
            val row = title.parent.parent as View
            check(row.findViewWithTag("settings_chooser_chevron"))
        }
    }

    /** A finger landing on [view]; the press is cancelled so only the window-level reaction is tested. */
    private fun touch(activity: SettingsActivity, view: View) {
        val at = IntArray(2).also(view::getLocationOnScreen)
        val x = at[0] + view.width / 2f
        val y = at[1] + view.height / 2f
        listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_CANCEL).forEach { action ->
            val event = android.view.MotionEvent.obtain(0L, 0L, action, x, y, 0)
            activity.dispatchTouchEvent(event)
            event.recycle()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun navigationWidth(activity: SettingsActivity) =
        (root(activity).findViewWithTag<View>("settings_navigation").parent as View).width

    @Test
    @Config(qualifiers = "en-w1000dp-h600dp-xxhdpi")
    fun `chevron collapses the wide panel to an icon rail and remembers it`() {
        Prefs(ApplicationProvider.getApplicationContext()).navigationCollapsed = false
        val controller = open()
        var activity = controller.get()
        layout(activity)
        val toggle = root(activity).findViewWithTag<android.widget.ImageButton>("settings_nav_toggle")
        val row = toggle.parent as ViewGroup
        assertEquals(View.VISIBLE, toggle.visibility)
        assertSame(root(activity).findViewWithTag<View>("settings_app_icon"), row.getChildAt(row.indexOfChild(toggle) + 2))
        assertEquals("Collapse menu", toggle.contentDescription)
        assertEquals(activity.dp(240), navigationWidth(activity))
        toggle.performClick()
        layout(activity)
        assertTrue(Prefs(activity).navigationCollapsed)
        assertEquals("Expand menu", toggle.contentDescription)
        assertEquals(activity.dp(76), navigationWidth(activity))
        val dpad = root(activity).findViewWithTag<Button>("settings_nav_DPAD")
        assertEquals("", dpad.text.toString())
        assertEquals(activity.getString(R.string.nav_dpad), dpad.contentDescription)
        assertEquals(activity.getString(R.string.nav_dpad), dpad.tooltipText)
        // The icon still fits the rail without being clipped.
        assertTrue(dpad.measuredWidth <= activity.dp(44))
        controller.recreate()
        activity = controller.get()
        layout(activity)
        assertEquals(activity.dp(76), navigationWidth(activity))
        activity.findViewById<View>(android.R.id.content).findViewWithTag<View>("settings_nav_toggle").performClick()
        layout(activity)
        assertFalse(Prefs(activity).navigationCollapsed)
        assertEquals(activity.dp(240), navigationWidth(activity))
        assertEquals(activity.getString(R.string.nav_dpad), root(activity).findViewWithTag<Button>("settings_nav_DPAD").text.toString())
    }

    @Test
    @Config(qualifiers = "en-w1000dp-h600dp-xxhdpi")
    fun `the wide panel starts as an icon rail`() {
        val activity = open().get()
        layout(activity)
        assertEquals(activity.dp(76), navigationWidth(activity))
        assertEquals("Expand menu", root(activity).findViewWithTag<View>("settings_nav_toggle").contentDescription)
    }

    @Test
    @Config(qualifiers = "en-w1000dp-h600dp-xxhdpi")
    fun `a rail icon opens the page and lays the full panel over it until the page is touched`() {
        Prefs(ApplicationProvider.getApplicationContext()).navigationCollapsed = true
        val activity = open().get()
        layout(activity)
        val pageLeft = IntArray(2).also(page(activity, SettingsSection.BASIC)::getLocationOnScreen)[0]
        root(activity).findViewWithTag<View>("settings_nav_SHOULDERS").performClick()
        layout(activity)
        assertNotNull(page(activity, SettingsSection.SHOULDERS))
        assertEquals(activity.dp(240), navigationWidth(activity))
        // The page does not move; the panel covers it.
        assertEquals(pageLeft, IntArray(2).also(page(activity, SettingsSection.SHOULDERS)::getLocationOnScreen)[0])
        assertTrue(Prefs(activity).navigationCollapsed)
        // Further entries keep the panel open, and so does touching the panel itself.
        val help = root(activity).findViewWithTag<Button>("settings_nav_HELP")
        touch(activity, help)
        help.performClick()
        layout(activity)
        assertNotNull(page(activity, SettingsSection.HELP))
        assertEquals(activity.getString(R.string.nav_help), help.text.toString())
        touch(activity, page(activity, SettingsSection.HELP))
        layout(activity)
        assertEquals(activity.dp(76), navigationWidth(activity))
        assertEquals("", help.text.toString())
        assertTrue(Prefs(activity).navigationCollapsed)
    }

    @Test
    @Config(qualifiers = "en-w1000dp-h600dp-xxhdpi")
    fun `the chevron pins a peeking panel open and controller focus in the page closes a peek`() {
        Prefs(ApplicationProvider.getApplicationContext()).navigationCollapsed = true
        val controller = open()
        val activity = controller.get()
        // An enabled service lifts the blocker, which would otherwise keep focus out of the page.
        android.provider.Settings.Secure.putString(
            activity.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "${activity.packageName}/${SwitcherService::class.java.name}",
        )
        controller.pause().resume()
        layout(activity)
        val toggle = root(activity).findViewWithTag<View>("settings_nav_toggle")
        root(activity).findViewWithTag<View>("settings_nav_FACE").performClick()
        layout(activity)
        assertEquals(activity.dp(240), navigationWidth(activity))
        val target = find(page(activity, SettingsSection.FACE)) { it.isFocusable && it.isShown && it !is ScrollView }!!
        assertTrue(target.requestFocus())
        layout(activity)
        assertEquals(activity.dp(76), navigationWidth(activity))

        root(activity).findViewWithTag<View>("settings_nav_DPAD").performClick()
        layout(activity)
        touch(activity, toggle)
        toggle.performClick()
        layout(activity)
        assertFalse(Prefs(activity).navigationCollapsed)
        assertEquals(activity.dp(240), navigationWidth(activity))
        touch(activity, page(activity, SettingsSection.DPAD))
        layout(activity)
        assertEquals(activity.dp(240), navigationWidth(activity))
    }

    @Test
    fun `compact layout has no chevron and its menu never collapses`() {
        Prefs(ApplicationProvider.getApplicationContext()).navigationCollapsed = true
        val activity = open().get()
        layout(activity, 380)
        assertEquals(View.GONE, root(activity).findViewWithTag<View>("settings_nav_toggle").visibility)
        root(activity).findViewWithTag<View>("settings_menu").performClick()
        val entry = ShadowDialog.getLatestDialog()!!.window!!.decorView.findViewWithTag<Button>("settings_nav_STICKS")
        assertEquals(activity.getString(R.string.nav_sticks), entry.text.toString())
        entry.performClick()
        assertNotNull(page(activity, SettingsSection.STICKS))
    }
}
