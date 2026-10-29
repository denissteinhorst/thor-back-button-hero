package dev.steinhorst.backbuttonhero

import android.app.Activity
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SwitcherTaskSyncTest {
    private class Fixture {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        val row = LinearLayout(activity)
        val apps = listOf("dev.example.first", "dev.example.closed", "dev.example.last")
            .map { RunningApp(it, it, null, null) }
        val cards = apps.map { LinearLayout(activity).also(row::addView) }
        val closed = mutableListOf<String>()
        var launched: Pair<String, Int>? = null
        var dismissed = 0
        val source = object : AppSource {
            override fun appsOnDisplay(displayId: Int) = apps
            override fun forget(packageName: String) { closed += packageName }
        }
        val overlay = SwitcherOverlay(service, source, { true }, { closed += it }, { _, _ -> }, {},
            { pkg, display -> launched = pkg to display }, {}, { dismissed++ })

        init {
            val root = FrameLayout(activity).apply { addView(row) }
            activity.setContentView(root)
            field("root", root)
            field("shownApps", apps)
            field("shownCards", cards)
            field("shownDisplayId", 17)
            field("selected", 2)
        }

        fun field(name: String, value: Any) = SwitcherOverlay::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(overlay, value)

        fun states() = listOf(RecentsTaskState(apps[0].packageName, 0, true, 10),
            RecentsTaskState(apps[2].packageName, 17, false, 20))
    }

    @Test
    fun `native task removal drops stale card while preserving controller selection and both screen scope`() {
        val f = Fixture()
        assertTrue(f.overlay.reconcileTaskStates(f.states()))
        assertEquals(setOf(f.apps[0].packageName, f.apps[2].packageName), f.overlay.previewPackages())
        assertEquals(2, f.row.childCount)
        assertTrue(f.overlay.isShowing)
        assertTrue(f.closed.isEmpty())
        f.overlay.activateSelection()
        assertEquals(f.apps[2].packageName to 17, f.launched)
        assertEquals(1, f.dismissed)
        f.activity.finish()
    }

    @Test
    fun `removing selected task chooses remaining neighbor without launching closed app`() {
        val f = Fixture()
        f.field("selected", 1)
        f.overlay.reconcileTaskStates(listOf(f.states().first()))
        f.overlay.activateSelection()
        assertEquals(f.apps[0].packageName to 17, f.launched)
        f.activity.finish()
    }

    @Test
    fun `empty snapshot dismisses last card without closing apps or launching one`() {
        val f = Fixture()
        f.overlay.reconcileTaskStates(emptyList())
        assertFalse(f.overlay.isShowing)
        assertTrue(f.overlay.previewPackages().isEmpty())
        assertEquals(0, f.row.childCount)
        assertEquals(1, f.dismissed)
        assertTrue(f.closed.isEmpty())
        assertEquals(null, f.launched)
        f.activity.finish()
    }

    @Test
    fun `pending close all animation retains cards and thumbnails until it completes`() {
        val f = Fixture()
        f.field("bulkClosing", true)
        assertFalse(f.overlay.reconcileTaskStates(emptyList()))
        assertEquals(3, f.row.childCount)
        assertEquals(3, f.overlay.previewPackages().size)
        assertEquals(0, f.dismissed)
        f.activity.finish()
    }
}
