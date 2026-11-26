package dev.steinhorst.backbuttonhero.settings

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.SettingsActivity
import dev.steinhorst.backbuttonhero.input.AndroidButton
import dev.steinhorst.backbuttonhero.input.BackGestureButton
import dev.steinhorst.backbuttonhero.input.ComboButton
import dev.steinhorst.backbuttonhero.input.DpadDirection
import dev.steinhorst.backbuttonhero.input.FaceButton
import dev.steinhorst.backbuttonhero.input.ShoulderButton
import dev.steinhorst.backbuttonhero.input.Stick
import dev.steinhorst.backbuttonhero.input.StickButton
import dev.steinhorst.backbuttonhero.input.StickClickButton
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.support.Golden
import dev.steinhorst.backbuttonhero.support.ViewTree
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
import org.robolectric.annotation.Config

// The Thor's top screen in landscape, 1920 x 1080 px at 369 dpi, leaves the app 833 x 421 dp.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w833dp-h421dp-xxhdpi")
class BindingsOverviewTest {
    private val prefs get() = Prefs(ApplicationProvider.getApplicationContext<Context>())

    private fun open(width: Int = 833, height: Int = 421): SettingsActivity {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible().get()
        layout(activity, width, height)
        return activity
    }

    private fun layout(activity: SettingsActivity, width: Int, height: Int) {
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
    private fun overview(activity: SettingsActivity) = root(activity).findViewWithTag<BindingsOverview>("settings_overview")
    private fun group(activity: SettingsActivity, control: OverviewControl): ViewGroup? =
        overview(activity).findViewWithTag("settings_overview_${control.name}")
    private fun groups(activity: SettingsActivity) = overview(activity).let { view ->
        (0 until view.childCount).map(view::getChildAt).filter { it is LinearLayout && it.tag != "settings_overview_toggle" }
    }
    /** Each line of a label as it reads from left to right, its cells joined by " | ". */
    private fun lines(view: ViewGroup) = (0 until view.childCount).map(view::getChildAt).filter { it.visibility == View.VISIBLE }.map { line ->
        if (line is TextView) line.text.toString()
        else (line as ViewGroup).let { row -> (0 until row.childCount).joinToString(" | ") { (row.getChildAt(it) as TextView).text.toString() } }
    }
    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun assignEverything() = prefs.apply {
        val buttons: List<ComboButton> = BackGestureButton.entries + AndroidButton.entries + FaceButton.entries +
            ShoulderButton.entries + DpadDirection.entries + StickButton.entries + StickClickButton.entries
        buttons.forEach { setShoulderAction(it, Prefs.SHOULDER_SCREENSHOT) }
        setShoulderAction(DpadDirection.UP, Prefs.SHOULDER_VOLUME_UP)
    }

    @Test
    fun `the app opens on the overview, the first page under the logo and its rule`() {
        val activity = open()
        assertNotNull(root(activity).findViewWithTag<View>("settings_page_OVERVIEW"))
        val column = root(activity).findViewWithTag<View>("settings_nav_OVERVIEW").parent as ViewGroup
        assertSame(root(activity).findViewWithTag("settings_nav_logo"), column.getChildAt(0))
        assertSame(root(activity).findViewWithTag("settings_nav_divider"), column.getChildAt(1))
        assertSame(root(activity).findViewWithTag("settings_nav_OVERVIEW"), column.getChildAt(2))
        assertSame(root(activity).findViewWithTag("settings_nav_BASIC"), column.getChildAt(3))
        assertEquals("Overview", activity.getString(SettingsSection.OVERVIEW.titleRes))
    }

    @Test
    fun `only assigned controls get a label and nothing assigned shows a hint`() {
        var activity = open()
        assertTrue(groups(activity).isEmpty())
        val empty = page(activity).let { findText(it, activity.getString(R.string.overview_empty)) }!!
        assertEquals(View.VISIBLE, empty.visibility)

        prefs.useEarlierDefaults()
        activity = open()
        assertEquals(View.GONE, findText(page(activity), activity.getString(R.string.overview_empty))!!.visibility)
        val present = groups(activity).map { (it.tag as String).removePrefix("settings_overview_") }.toSet()
        assertEquals(setOf("L2", "L1", "R2", "R1", "DPAD", "BACK"), present)
        // Labels left of the drawing are mirrored: the control's name sits next to its line.
        assertEquals(listOf("Screenshot | L2"), lines(group(activity, OverviewControl.L2)!!))
        assertEquals(listOf("Flick left | L1"), lines(group(activity, OverviewControl.L1)!!))
        assertEquals(listOf("R2 | Controller focus"), lines(group(activity, OverviewControl.R2)!!))
        val dpad = lines(group(activity, OverviewControl.DPAD)!!)
        assertEquals("D-pad", dpad.first())
        assertEquals(listOf(listOf("↑", "↓"), listOf("←", "→")), dpad.drop(1).map { line -> line.split(" | ").filterIndexed { i, _ -> i % 2 == 1 } })
        assertEquals(listOf("Back", "Press", "2× press", "Tap + hold", "Hold"), lines(group(activity, OverviewControl.BACK)!!).map { it.substringBefore(" | ") })
        assertNull(group(activity, OverviewControl.FACE))
    }

    private fun area(activity: SettingsActivity, area: OverviewArea): View =
        overview(activity).findViewWithTag("settings_overview_area_${area.name}")

    @Test
    fun `the drawing's parts open their pages and say what they do, while the labels only read`() {
        prefs.useEarlierDefaults()
        prefs.setShoulderAction(FaceButton.B, Prefs.SHOULDER_SCREENSHOT)
        val activity = open()
        assertEquals("L2: Screenshot; L1: Flick left. Double-tap to change.", area(activity, OverviewArea.SHOULDERS_LEFT).contentDescription)
        assertEquals("Face buttons: B: Screenshot. Double-tap to change.", area(activity, OverviewArea.FACE).contentDescription)
        // Unassigned parts open their pages too, which is where they get something to do.
        assertEquals("Home: Not set. Double-tap to change.", area(activity, OverviewArea.HOME).contentDescription)
        group(activity, OverviewControl.L2)!!.let { label ->
            assertFalse(label.isClickable)
            assertFalse(label.isFocusable)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, label.importantForAccessibility)
        }
        OverviewArea.entries.forEach { part ->
            dev.steinhorst.backbuttonhero.support.selectSettingsPage(activity, SettingsSection.OVERVIEW)
            layout(activity, 833, 421)
            val view = area(activity, part)
            assertTrue(view.isFocusable)
            // Even the small keys get a finger-sized target.
            assertTrue("$part target", view.width >= activity.dp(44) && view.height >= activity.dp(44))
            view.performClick()
            assertNotNull(root(activity).findViewWithTag<View>("settings_page_${part.section.name}"))
            assertEquals(activity.getString(part.section.titleRes), findTitle(activity))
            part.stick?.let { assertEquals("$part tab", it, activity.javaClass.getDeclaredField("stickTab").apply { isAccessible = true }.get(activity)) }
        }
    }

    @Test
    fun `home shows its shortcut or, in its Back mode, that mode`() {
        prefs.setShoulderAction(AndroidButton.HOME, Prefs.SHOULDER_SCREENSHOT)
        var activity = open()
        // Left of the drawing a label reads mirrored, the control's name next to its line.
        assertEquals(listOf("Screenshot | Home"), lines(group(activity, OverviewControl.HOME)!!))
        // Back mode blocks the shortcut, so the mode takes its place.
        prefs.homeAsBack = true
        activity = open()
        assertEquals(listOf("Back button mode | Home"), lines(group(activity, OverviewControl.HOME)!!))
        assertEquals("Home: Back button mode. Double-tap to change.", area(activity, OverviewArea.HOME).contentDescription)
        prefs.homeAsBack = false
        prefs.setShoulderAction(AndroidButton.HOME, "")
        assertNull(group(open(), OverviewControl.HOME))
    }

    @Test
    fun `a binding changed elsewhere shows up when the overview comes back`() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible()
        val activity = controller.get()
        assertTrue(groups(activity).isEmpty())
        controller.pause()
        prefs.setShoulderAction(FaceButton.A, Prefs.SHOULDER_VOLUME_UP)
        controller.resume()
        layout(activity, 833, 421)
        assertEquals(listOf("Face buttons", "A | Volume +"), lines(group(activity, OverviewControl.FACE)!!))
    }

    @Test
    fun `with every control assigned the labels stay beside the drawing without overlapping`() {
        assignEverything()
        prefs.toggleButtons = Prefs.TOGGLE_L2_R2
        run {
            val activity = open()
            // Robolectric never reaches pservice; the Thor does, so its warning above the page stays away there.
            ((page(activity).parent.parent.parent as ViewGroup).getChildAt(0)).visibility = View.GONE
            layout(activity, 833, 421)
            val overview = overview(activity)
            val views = groups(activity)
            assertEquals(OverviewControl.entries.size, views.size)
            // Without the header the whole picture fits the Thor's top screen, so nothing needs scrolling.
            (page(activity) as ViewGroup).let { assertTrue("fits without scrolling", it.getChildAt(0).height <= it.height) }
            dev.steinhorst.backbuttonhero.support.Golden.verify(
                "overview_all_assigned", dev.steinhorst.backbuttonhero.support.ViewTree.describe(overview),
            )
            // The drawing never shrinks below 250 dp, centred between the two label columns.
            val drawingLeft = (overview.width - activity.dp(250)) / 2
            val drawingRight = overview.width - drawingLeft
            OverviewControl.entries.forEach { control ->
                val view = group(activity, control)!!
                assertTrue("$control inside", view.left >= 0 && view.right <= overview.width && view.top >= 0 && view.bottom <= overview.height)
                if (control.right) assertTrue("$control right of drawing", view.left >= drawingRight)
                else assertTrue("$control left of drawing", view.right <= drawingLeft)
                // Every row reads on one line.
                textViews(view).filter { it.isShown }.forEach { assertEquals("$control ${it.text}", 1, it.lineCount) }
            }
            listOf(true, false).forEach { right ->
                val side = OverviewControl.entries.filter { it.right == right }.map { group(activity, it)!! }.sortedBy { it.top }
                side.zipWithNext().forEach { (upper, lower) -> assertTrue("${upper.tag} over ${lower.tag}", upper.bottom <= lower.top) }
                // Labels keep the order of their controls from top to bottom, so leader lines never cross.
                assertEquals(OverviewControl.entries.filter { it.right == right }.sortedBy { it.y }.map { "settings_overview_${it.name}" }, side.map { it.tag })
            }
            // Mirrored: every right label's first row sits level with its left counterpart's, so the lines match.
            fun firstRow(view: ViewGroup) = view.top + view.paddingTop + view.getChildAt(0).height / 2
            OverviewControl.entries.filter { !it.right }.forEach { control ->
                assertEquals("$control / ${control.mirror}", firstRow(group(activity, control)!!), firstRow(group(activity, control.mirror)!!))
            }
            // The on/off shortcut sits centred under the drawing, clear of every label.
            val toggle = overview.findViewWithTag<ViewGroup>("settings_overview_toggle")
            assertEquals(listOf("Activate / deactivate", "Hold L2 + R2"), textViews(toggle).map { it.text.toString() })
            assertEquals("Activate / deactivate: Hold L2 + R2", toggle.contentDescription)
            assertTrue(kotlin.math.abs((toggle.left + toggle.right) / 2 - overview.width / 2) <= 1)
            assertTrue(toggle.top > area(activity, OverviewArea.HOME).bottom && toggle.bottom <= overview.height)
            views.forEach { label ->
                assertFalse("${label.tag} clear of the toggle", android.graphics.Rect(label.left, label.top, label.right, label.bottom)
                    .intersect(toggle.left, toggle.top, toggle.right, toggle.bottom))
            }
        }
        // Without a shortcut nothing shows under the drawing.
        prefs.toggleButtons = Prefs.TOGGLE_NONE
        assertEquals(View.GONE, overview(open()).findViewWithTag<View>("settings_overview_toggle").visibility)
    }

    /** An installed app whose name is too long for two directions to share a line. */
    private fun longApp(): String {
        val app = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(app.packageManager).installPackage(android.content.pm.PackageInfo().apply {
            packageName = "com.example.retro"
            applicationInfo = android.content.pm.ApplicationInfo().apply {
                packageName = "com.example.retro"
                nonLocalizedLabel = "Retro Arcade Collection"
            }
        })
        return "com.example.retro"
    }

    @Test
    // Only real text measurement knows that the names are too long to pair up.
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `long stick names take a line per direction while the page has room`() {
        val app = longApp()
        StickButton.entries.filter { it.stick == Stick.LEFT }.forEach { prefs.setShoulderAction(it, app) }
        val activity = open()
        val stacked = lines(group(activity, OverviewControl.LEFT_STICK)!!)
        assertEquals(listOf("Left stick", "Open Retro Arcade Collection | ↑", "Open Retro Arcade Collection | ↓",
            "Open Retro Arcade Collection | ←", "Open Retro Arcade Collection | →"), stacked)
    }

    @Test
    @Config(qualifiers = "en-w833dp-h200dp-xxhdpi")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun `on a screen too short for a line per direction the pairs stay and are cut short instead`() {
        val app = longApp()
        StickButton.entries.filter { it.stick == Stick.LEFT }.forEach { prefs.setShoulderAction(it, app) }
        val activity = open(833, 200)
        assertEquals(3, lines(group(activity, OverviewControl.LEFT_STICK)!!).size)
    }

    @Test
    @Config(qualifiers = "en-w380dp-h700dp-xxhdpi")
    fun `a narrow screen lists the labels below the drawing`() {
        prefs.useEarlierDefaults()
        val activity = open(380, 700)
        val overview = overview(activity)
        val views = groups(activity).sortedBy { it.top }
        assertTrue(views.first().top > overview.width * 0.5f)
        views.zipWithNext().forEach { (upper, lower) -> assertTrue(upper.bottom <= lower.top) }
        assertTrue(views.all { it.left == 0 })
        assertFalse(views.isEmpty())
    }

    private fun page(activity: SettingsActivity): View = root(activity).findViewWithTag("settings_page_OVERVIEW")

    private fun findTitle(activity: SettingsActivity): String {
        val bar = root(activity).findViewWithTag<ViewGroup>("settings_top_bar")
        val row = bar.getChildAt(0) as ViewGroup
        return (row.getChildAt(row.indexOfChild(row.findViewWithTag("settings_app_icon")) + 1) as TextView).text.toString()
    }

    private fun findText(view: View, text: String): View? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findText(view.getChildAt(i), text)?.let { return it }
        return null
    }
}
