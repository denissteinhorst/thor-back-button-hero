package dev.steinhorst.backbuttonhero

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecentsThemeTest {
    @Test
    fun `without OEM package both modes retain opaque backgrounds and contrasting labels`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun theme(mode: Int): RecentsTheme {
            val configuration = Configuration(context.resources.configuration).apply {
                uiMode = uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or mode
            }
            return RecentsTheme.load(context.createConfigurationContext(configuration))
        }
        val light = theme(Configuration.UI_MODE_NIGHT_NO)
        val dark = theme(Configuration.UI_MODE_NIGHT_YES)
        assertEquals(255, Color.alpha(light.background))
        assertEquals(255, Color.alpha(dark.background))
        assertNotEquals(light.background, dark.background)
        assertNotEquals(light.text, dark.text)
        assertEquals(Color.BLACK, light.text)
        assertEquals(Color.WHITE, dark.text)
    }
}
