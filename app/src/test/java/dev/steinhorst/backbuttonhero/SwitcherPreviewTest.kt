package dev.steinhorst.backbuttonhero

import android.graphics.Bitmap
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SwitcherPreviewTest {
    @Test
    fun `controller selection and late thumbnail loading never add a preview border`() {
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        val source = object : AppSource {
            override fun appsOnDisplay(displayId: Int) = emptyList<RunningApp>()
            override fun forget(packageName: String) = Unit
        }
        val overlay = SwitcherOverlay(service, source, { false }, {}, { _, _ -> }, {}, { _, _ -> }, {}, {})
        val preview = ImageView(service)
        val card = LinearLayout(service).apply { addView(FrameLayout(service)); addView(preview) }
        fun field(name: String, value: Any) = SwitcherOverlay::class.java.getDeclaredField(name).apply { isAccessible = true }.set(overlay, value)
        field("shownCards", listOf(card))
        field("shownApps", listOf(RunningApp("dev.example.app", "Example", null, null)))
        val update = SwitcherOverlay::class.java.getDeclaredMethod("updateSelection", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        update.invoke(overlay, false)
        assertNull(preview.foreground)
        overlay.updateThumbnail("dev.example.app", Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))
        update.invoke(overlay, false)
        assertNull(preview.foreground)
        val state = RecentsTaskState("dev.example.app", 17, false, 10, 100)
        overlay.updateTaskStates(listOf(state))
        assertTrue(overlay.representsTask(state))
        assertFalse(overlay.representsTask(state.copy(taskId = 20)))
        assertFalse(overlay.representsTask(state.copy(displayId = 0)))
    }

    @Test
    fun `a package with a task on each screen previews the task on the switcher's screen`() {
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        val source = object : AppSource {
            override fun appsOnDisplay(displayId: Int) = emptyList<RunningApp>()
            override fun forget(packageName: String) = Unit
        }
        val overlay = SwitcherOverlay(service, source, { false }, {}, { _, _ -> }, {}, { _, _ -> }, {}, {})
        SwitcherOverlay::class.java.getDeclaredField("shownDisplayId").apply { isAccessible = true }.set(overlay, 17)
        SwitcherOverlay::class.java.getDeclaredField("shownApps").apply { isAccessible = true }
            .set(overlay, listOf(RunningApp("dev.example.frontend", "Frontend", null, null)))
        // The frontend's top-screen activity is its most recent task, ahead of its bottom one.
        val top = RecentsTaskState("dev.example.frontend", 0, false, 10, 200)
        val bottom = RecentsTaskState("dev.example.frontend", 17, false, 11, 200)
        assertEquals(bottom, overlay.taskFor("dev.example.frontend", listOf(top, bottom)))
        assertEquals(top, overlay.taskFor("dev.example.frontend", listOf(top)))
        assertNull(overlay.taskFor("dev.example.other", listOf(top, bottom)))
        overlay.updateTaskStates(listOf(top, bottom))
        assertTrue(overlay.representsTask(bottom))
        assertFalse(overlay.representsTask(top))
    }
}
