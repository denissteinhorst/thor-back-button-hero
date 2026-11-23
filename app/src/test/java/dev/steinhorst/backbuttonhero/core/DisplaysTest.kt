package dev.steinhorst.backbuttonhero.core

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
class DisplaysTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun display(id: Int): Display = context.getSystemService(DisplayManager::class.java).getDisplay(id)

    @Test
    fun `only the top screen means no bottom screen`() {
        assertNull(Displays.bottom(context))
    }

    @Test
    fun `the bottom screen is the highest non-default id that is not private`() {
        val second = ShadowDisplayManager.addDisplay("w400dp-h300dp")
        assertEquals(second, Displays.bottom(context)?.displayId)
        val parking = ShadowDisplayManager.addDisplay("w400dp-h300dp")
        shadowOf(display(parking)).setFlags(Display.FLAG_PRIVATE)
        assertTrue(Displays.isPrivate(display(parking)))
        assertFalse(Displays.isPrivate(display(second)))
        assertEquals(second, Displays.bottom(context)?.displayId)
        val third = ShadowDisplayManager.addDisplay("w400dp-h300dp")
        assertEquals(third, Displays.bottom(context)?.displayId)
    }
}
