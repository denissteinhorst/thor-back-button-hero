package dev.steinhorst.backbuttonhero

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmokeTest {
    @Test
    fun `Prefs can be constructed against a Robolectric application context`() {
        val prefs = Prefs(ApplicationProvider.getApplicationContext())
        assertNotNull(prefs)
    }
}
