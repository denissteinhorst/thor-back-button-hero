package dev.steinhorst.backbuttonhero

import android.graphics.Bitmap
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
}
