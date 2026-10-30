package dev.steinhorst.backbuttonhero

import android.app.Activity
import android.app.ActivityManager
import android.os.Looper
import android.view.Display
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAppTask
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

    @Test
    fun `close all removes our own task but never kills the process hosting the service`() {
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        val manager = service.getSystemService(ActivityManager::class.java)
        val ownTask = ShadowAppTask.newInstance()
        shadowOf(manager).setAppTasks(listOf(ownTask))
        SwitcherService::class.java.getDeclaredMethod("closeAll", List::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(service, listOf(service.packageName, "dev.example.other"), false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertTrue(shadowOf(ownTask).isFinishedAndRemoved)
        assertEquals("dev.example.other", shadowOf(manager).backgroundPackage)
    }

    @Test
    fun `closing other apps leaves our own task alone`() {
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        val manager = service.getSystemService(ActivityManager::class.java)
        val ownTask = ShadowAppTask.newInstance()
        shadowOf(manager).setAppTasks(listOf(ownTask))
        SwitcherService::class.java.getDeclaredMethod("closeAll", List::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(service, listOf("dev.example.other"), false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertFalse(shadowOf(ownTask).isFinishedAndRemoved)
        assertEquals("dev.example.other", shadowOf(manager).backgroundPackage)
    }
}
