package dev.steinhorst.backbuttonhero

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class PanelCardsTest {
    private class Fixture(bothScreens: Boolean = true) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        val image = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val panel = RunningApp("dev.example.frontend", "Frontend – bottom panel", null, image,
            PanelWindow(552, "dev.example.frontend", 17, true))
        val app = RunningApp(panel.packageName, "Frontend", null, null)
        val row = LinearLayout(activity)
        val cards = listOf(app, panel).map { entry ->
            LinearLayout(activity).apply {
                addView(FrameLayout(activity))
                addView(ImageView(activity).apply { setImageBitmap(entry.thumbnail) })
                row.addView(this)
            }
        }
        val closed = mutableListOf<String>()
        var launched: String? = null
        var restored: Int? = null
        var closedAll = emptyList<String>()
        val source = object : AppSource {
            override fun appsOnDisplay(displayId: Int) = listOf(app)
            override fun panelsOnDisplay(displayId: Int) = listOf(panel)
            override fun forget(packageName: String) { closed += packageName }
        }
        val overlay = SwitcherOverlay(service, source, { bothScreens }, { closed += it }, { packages, _ -> closedAll = packages }, {},
            { pkg, _ -> launched = pkg }, {}, {}, { restored = it })

        init {
            val root = FrameLayout(activity).apply { addView(row) }
            activity.setContentView(root)
            field("root", root)
            field("shownApps", listOf(app, panel))
            field("shownCards", cards)
            field("shownDisplayId", 17)
            field("selected", 1)
        }

        fun field(name: String, value: Any) = SwitcherOverlay::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(overlay, value)
    }

    @Test
    fun `panel identity and frozen preview are independent of the owners top activity`() {
        val f = Fixture()
        assertNotEquals(f.app.key, f.panel.key)
        val taskImage = Bitmap.createBitmap(5, 5, Bitmap.Config.ARGB_8888)
        f.overlay.updateThumbnail(f.app.packageName, taskImage)
        assertSame(taskImage, ((f.cards[0].getChildAt(1) as ImageView).drawable as BitmapDrawable).bitmap)
        assertSame(f.image, ((f.cards[1].getChildAt(1) as ImageView).drawable as BitmapDrawable).bitmap)
        f.overlay.activateSelection()
        assertEquals(552, f.restored)
        assertEquals(null, f.launched)
        assertTrue(f.closed.isEmpty())
        f.activity.finish()
    }

    @Test
    fun `task expiry preserves the panel and its selected return action`() {
        val f = Fixture()
        f.overlay.reconcileTaskStates(emptyList())
        assertEquals(1, f.row.childCount)
        assertTrue(f.overlay.isShowing)
        assertTrue(f.overlay.previewPackages().isEmpty())
        f.overlay.activateSelection()
        assertEquals(552, f.restored)
        assertEquals(null, f.launched)
        f.activity.finish()
    }

    @Test
    fun `destroying a panel expires only that window without dropping its owners task`() {
        val f = Fixture()
        f.overlay.reconcilePanels(emptyList())
        assertEquals(1, f.row.childCount)
        assertTrue(f.overlay.isShowing)
        f.overlay.activateSelection()
        assertEquals(f.app.packageName, f.launched)
        assertEquals(null, f.restored)
        assertTrue(f.closed.isEmpty())
        f.activity.finish()
    }

    @Test
    fun `a panel scan keeps the empty switcher open until the user closes it`() {
        val f = Fixture()
        f.field("shownApps", emptyList<RunningApp>())
        f.field("shownCards", emptyList<Any>())
        f.overlay.reconcilePanels(emptyList())
        assertTrue(f.overlay.isShowing)
        f.activity.finish()
    }

    @Test
    fun `expiring the last panel card still closes the switcher`() {
        val f = Fixture()
        f.field("shownApps", listOf(f.panel))
        f.field("shownCards", listOf(f.cards[1]))
        f.overlay.reconcilePanels(emptyList())
        assertFalse(f.overlay.isShowing)
        f.activity.finish()
    }

    @Test
    fun `panel without an owner task is never matched to a system task thumbnail`() {
        val f = Fixture()
        f.overlay.reconcileTaskStates(emptyList())
        val state = RecentsTaskState(f.panel.packageName, 0, false, 34, 123)
        f.overlay.updateTaskStates(listOf(state))
        assertFalse(f.overlay.representsTask(state))
        f.overlay.updateThumbnail(f.panel.packageName, Bitmap.createBitmap(5, 5, Bitmap.Config.ARGB_8888))
        assertSame(f.image, ((f.cards[1].getChildAt(1) as ImageView).drawable as BitmapDrawable).bitmap)
        f.activity.finish()
    }

    @Test
    fun `swiping or controller dismissing a panel preserves its return card and owner`() {
        val f = Fixture()
        f.overlay.closeSelected()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertTrue(f.overlay.isShowing)
        assertEquals(2, f.row.childCount)
        assertTrue(f.closed.isEmpty())
        f.overlay.activateSelection()
        assertEquals(552, f.restored)
        f.activity.finish()
    }

    @Test
    fun `close all includes panel owners once and closes unrelated apps`() {
        val f = Fixture()
        val ordinary = RunningApp("dev.example.ordinary", "Ordinary", null, null)
        val card = LinearLayout(f.activity).also(f.row::addView)
        f.field("shownApps", listOf(f.app, f.panel, ordinary))
        f.field("shownCards", f.cards + card)
        f.overlay.closeAllFromController()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertEquals(listOf(f.panel.packageName, ordinary.packageName), f.closedAll)
        assertEquals(f.closedAll, f.closed)
        assertFalse(f.overlay.isShowing)
        assertEquals(null, f.restored)
        f.activity.finish()
    }

    @Test
    fun `bottom only close all closes a panel owner even without a separate top activity card`() {
        val f = Fixture(bothScreens = false)
        f.row.removeView(f.cards[0])
        f.field("shownApps", listOf(f.panel))
        f.field("shownCards", listOf(f.cards[1]))
        f.overlay.closeAllFromController()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertEquals(listOf(f.panel.packageName), f.closedAll)
        assertEquals(f.closedAll, f.closed)
        assertFalse(f.overlay.isShowing)
        f.activity.finish()
    }
}
