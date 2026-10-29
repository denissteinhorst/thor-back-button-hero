package dev.steinhorst.backbuttonhero

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.text.StaticLayout
import android.text.TextPaint
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class RecentsEmptyViewTest {
    @Test
    @Config(qualifiers = "en-notnight")
    fun `open empty view follows light dark and light again without recreation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = RecentsEmptyView(context)
        view.layout(0, 0, 1000, 700)
        val paint = RecentsEmptyView::class.java.getDeclaredField("paint").apply { isAccessible = true }.get(view) as TextPaint
        assertEquals(Color.BLACK, paint.color)
        RuntimeEnvironment.setQualifiers("en-night")
        view.refreshTheme()
        assertEquals(Color.WHITE, paint.color)
        assertEquals("No recent items", view.contentDescription)
        RuntimeEnvironment.setQualifiers("en-notnight")
        view.refreshTheme()
        assertEquals(Color.BLACK, paint.color)
    }

    @Test
    fun `empty message has matching English and German wording in both themes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for ((language, expected) in listOf("en" to "No recent items", "de" to "Keine kürzlich verwendeten Elemente")) {
            for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                val config = Configuration(context.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(language))
                    uiMode = uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or night
                }
                val view = RecentsEmptyView(context.createConfigurationContext(config))
                assertEquals(expected, view.contentDescription)
                view.refreshTheme()
                assertEquals(expected, view.contentDescription)
            }
        }
    }

    @Test
    fun `icon and text form one centered group with original spacing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = RecentsEmptyView(context)
        val icon = BitmapDrawable(context.resources, Bitmap.createBitmap(92, 80, Bitmap.Config.ARGB_8888))
        RecentsEmptyView::class.java.getDeclaredField("icon").apply { isAccessible = true }.set(view, icon)
        view.layout(0, 0, 1000, 700)
        val layout = RecentsEmptyView::class.java.getDeclaredField("textLayout").apply { isAccessible = true }.get(view) as StaticLayout
        val gap = RecentsEmptyView::class.java.getDeclaredField("gap").apply { isAccessible = true }.getInt(view)
        assertEquals((1000 - icon.intrinsicWidth) / 2, icon.bounds.left)
        assertEquals((700 - icon.intrinsicHeight - gap - layout.height) / 2, icon.bounds.top)
        assertEquals(1000 - 2 * gap, layout.width)
    }
}
