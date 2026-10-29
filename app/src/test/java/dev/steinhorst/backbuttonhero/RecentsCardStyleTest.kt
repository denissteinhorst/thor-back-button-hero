package dev.steinhorst.backbuttonhero

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecentsCardStyleTest {
    @Test
    fun `adaptive normalization preserves drawable bounds shared with the app source`() {
        val style = RecentsCardStyle.load(ApplicationProvider.getApplicationContext<Context>())
        val icon = AdaptiveIconDrawable(ColorDrawable(Color.WHITE), ColorDrawable(Color.BLACK))
        val bounds = Rect(10, 20, 90, 100)
        icon.bounds = bounds
        val size = style.normalizedDrawableSize(icon)
        assertEquals(bounds, icon.bounds)
        assertTrue(size in 1..style.iconDrawableSize)
        assertEquals(style.iconDrawableSize, style.normalizedDrawableSize(ColorDrawable(Color.RED)))
    }
}
