package dev.steinhorst.backbuttonhero

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppControlTest {
    private val apps = mapOf(0 to "game", 4 to "guide")

    @Test
    fun `fixed screens resolve to that screens foreground app`() {
        assertEquals(listOf(AppControlTarget(0, "game")), AppControl.targets(Prefs.DISPLAY_TOP, 0, 4, apps::get))
        assertEquals(listOf(AppControlTarget(4, "guide")), AppControl.targets(Prefs.DISPLAY_BOTTOM, 0, 4, apps::get))
    }

    @Test
    fun `both screens resolve to every foreground app`() {
        assertEquals(
            listOf(AppControlTarget(0, "game"), AppControlTarget(4, "guide")),
            AppControl.targets(Prefs.TARGET_BOTH, 0, 4, apps::get),
        )
    }

    @Test
    fun `screens showing only a launcher or no bottom display contribute nothing`() {
        assertEquals(listOf(AppControlTarget(0, "game")), AppControl.targets(Prefs.TARGET_BOTH, 0, 4) { if (it == 0) "game" else null })
        assertTrue(AppControl.targets(Prefs.DISPLAY_BOTTOM, 0, null, apps::get).isEmpty())
        assertTrue(AppControl.targets(Prefs.APP_SCREEN_ASK, 0, 4, apps::get).isEmpty())
    }

    @Test
    fun `app control settings default to minimize ask and instant`() {
        val prefs = Prefs(ApplicationProvider.getApplicationContext())
        assertEquals(Prefs.APP_MINIMIZE, prefs.appMode(ShoulderButton.L1))
        assertEquals(Prefs.APP_SCREEN_ASK, prefs.appScreen(ShoulderButton.L1))
        assertFalse(prefs.appConfirm(ShoulderButton.L1))
    }

    @Test
    fun `app control settings round trip per button and through snapshots`() {
        val prefs = Prefs(ApplicationProvider.getApplicationContext())
        prefs.setAppMode(ShoulderButton.R1, Prefs.APP_FORCE_CLOSE)
        prefs.setAppScreen(ShoulderButton.R1, Prefs.TARGET_BOTH)
        prefs.setAppConfirm(ShoulderButton.R1, true)
        assertEquals(Prefs.APP_MINIMIZE, prefs.appMode(ShoulderButton.L1))
        val before = prefs.snapshot()
        val binding = before.bindings.getValue(ShoulderButton.R1.prefKey)
        assertEquals(Prefs.APP_FORCE_CLOSE, binding.appMode)
        assertEquals(Prefs.TARGET_BOTH, binding.appScreen)
        assertTrue(binding.appConfirm)

        val after = before.copy(bindings = before.bindings + (ShoulderButton.R1.prefKey to BindingSettings(
            Prefs.SHOULDER_APP_CONTROL, appMode = Prefs.APP_MINIMIZE, appScreen = Prefs.DISPLAY_TOP, appConfirm = false,
        )))
        assertTrue(prefs.applySettings(before, after))
        assertEquals(Prefs.APP_MINIMIZE, prefs.appMode(ShoulderButton.R1))
        assertEquals(Prefs.DISPLAY_TOP, prefs.appScreen(ShoulderButton.R1))
        assertFalse(prefs.appConfirm(ShoulderButton.R1))
    }
}
