package dev.steinhorst.backbuttonhero

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AynPrefsTest {
    private val prefs = Prefs(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `an AYN hotkey asks until an option is chosen, and a stale value is ignored`() {
        val button = ShoulderButton.L1
        assertEquals(Ayn.MODAL, prefs.aynOption(button, Ayn.Group.FAN))
        assertEquals("top", prefs.aynOption(button, Ayn.Group.DISPLAY))
        prefs.setAynOption(button, "smart")
        assertEquals("smart", prefs.aynOption(button, Ayn.Group.FAN))
        // The option of another tile does not carry over when the action changes.
        assertEquals(Ayn.MODAL, prefs.aynOption(button, Ayn.Group.PERFORMANCE))
        assertEquals("smart", prefs.snapshot().bindings.getValue(button.prefKey).aynOption)
    }
}
