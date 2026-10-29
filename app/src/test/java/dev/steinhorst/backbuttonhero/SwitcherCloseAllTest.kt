package dev.steinhorst.backbuttonhero

import android.app.Activity
import android.os.Looper
import android.view.Display
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class SwitcherCloseAllTest {
    private fun verifyCloseAll(bothScreens: Boolean) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        val root = FrameLayout(activity)
        val row = LinearLayout(activity)
        root.addView(row)
        activity.setContentView(root)
        val apps = listOf("dev.example.locked", "dev.example.free", "dev.example.unknown")
            .map { RunningApp(it, it, null, null) }
        val cards = apps.map { LinearLayout(activity).also(row::addView) }
        val forgotten = mutableListOf<String>()
        var topQueries = 0
        val source = object : AppSource {
            override fun appsOnDisplay(displayId: Int): List<RunningApp> {
                assertEquals(Display.DEFAULT_DISPLAY, displayId)
                topQueries++
                return listOf(RunningApp("dev.example.top", "Top", null, null))
            }
            override fun forget(packageName: String) { forgotten += packageName }
        }
        var closed = emptyList<String>()
        var closedBoth: Boolean? = null
        val overlay = SwitcherOverlay(service, source, { bothScreens }, {}, { packages, both ->
            closed = packages
            closedBoth = both
        }, {}, { _, _ -> }, {}, {})
        fun field(name: String, value: Any) = SwitcherOverlay::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(overlay, value)
        field("root", root)
        field("shownApps", apps)
        field("shownCards", cards)
        overlay.updateTaskStates(listOf(RecentsTaskState("dev.example.locked", 17, true),
            RecentsTaskState("dev.example.free", 17, false)))
        overlay.closeAllFromController()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        val expected = apps.map { it.packageName } + if (bothScreens) listOf("dev.example.top") else emptyList()
        assertEquals(expected, closed)
        assertEquals(expected, forgotten)
        assertEquals(bothScreens, closedBoth)
        assertEquals(if (bothScreens) 1 else 0, topQueries)
        assertFalse(overlay.isShowing)
        activity.finish()
    }

    @Test
    fun `bottom close all includes locked and unknown-status apps without widening scope`() {
        verifyCloseAll(bothScreens = false)
    }

    @Test
    fun `both screens close all also includes observed top apps`() {
        verifyCloseAll(bothScreens = true)
    }
}
