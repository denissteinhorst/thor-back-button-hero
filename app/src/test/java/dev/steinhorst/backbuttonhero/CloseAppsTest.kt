package dev.steinhorst.backbuttonhero

import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.input.ShoulderButton
import dev.steinhorst.backbuttonhero.prefs.BindingSettings
import dev.steinhorst.backbuttonhero.prefs.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CloseAppsTest {
    private val history = mapOf(0 to listOf("game", "browser"), 4 to listOf("guide", "browser", "music"))
    private val front = mapOf(0 to "game", 4 to "guide")
    private fun appsOn(id: Int) = history[id].orEmpty()

    @Test
    fun `targets cover their screens and tolerate a missing bottom display`() {
        assertEquals(listOf(0), CloseApps.displays(Prefs.DISPLAY_TOP, 0, 4))
        assertEquals(listOf(4), CloseApps.displays(Prefs.DISPLAY_BOTTOM, 0, 4))
        assertEquals(listOf(0, 4), CloseApps.displays(Prefs.TARGET_BOTH, 0, 4))
        assertTrue(CloseApps.displays(Prefs.DISPLAY_BOTTOM, 0, null).isEmpty())
        assertEquals(listOf(0), CloseApps.displays(Prefs.TARGET_BOTH, 0, null))
    }

    @Test
    fun `closing all apps takes every known app of the chosen screens once`() {
        assertEquals(listOf("game", "browser", "guide", "music"), CloseApps.packages(listOf(0, 4), listOf(0, 4), false, ::appsOn, front::get))
        assertEquals(listOf("guide", "browser", "music"), CloseApps.packages(listOf(4), listOf(0, 4), false, ::appsOn, front::get))
    }

    @Test
    fun `keeping foreground apps spares the front app of every screen`() {
        assertEquals(listOf("browser", "music"), CloseApps.packages(listOf(0, 4), listOf(0, 4), true, ::appsOn, front::get))
        // The top screen's front app stays open although it is only in the bottom screen's history.
        val moved = mapOf(0 to "music", 4 to "guide")
        assertEquals(listOf("browser"), CloseApps.packages(listOf(4), listOf(0, 4), true, ::appsOn, moved::get))
        // Screens showing only a launcher have no front app to spare.
        assertEquals(listOf("guide", "browser", "music"), CloseApps.packages(listOf(4), listOf(0, 4), true, ::appsOn) { null })
    }

    @Test
    fun `close apps actions are known grouped screen-target actions that ask by default`() {
        listOf(Prefs.SHOULDER_CLOSE_APPS, Prefs.SHOULDER_CLOSE_BACKGROUND_APPS).forEach {
            assertTrue(it in Prefs.KNOWN_ACTIONS)
            assertTrue(Prefs.hasScreenTarget(it))
            assertTrue(Prefs.isCloseApps(it))
            assertEquals(ActionGroups.Group.CLOSE_APPS, ActionGroups.groupOf(it))
        }
        assertFalse(Prefs.isCloseApps(Prefs.SHOULDER_APP_CONTROL))
        listOf(Prefs.SHOULDER_POWER_MENU, Prefs.SHOULDER_STANDBY).forEach {
            assertTrue(it in Prefs.KNOWN_ACTIONS)
            assertFalse(Prefs.hasScreenTarget(it))
            assertEquals(ActionGroups.Group.POWER, ActionGroups.groupOf(it))
        }
        assertTrue(Prefs.SHOULDER_VOLUME_MUTE in Prefs.KNOWN_ACTIONS)
        assertTrue(Prefs.hasScreenTarget(Prefs.SHOULDER_VOLUME_MUTE))
        // Held, volume up and down keep stepping; a mute toggle must not.
        assertFalse(Prefs.isLevelAction(Prefs.SHOULDER_VOLUME_MUTE))
        assertEquals(ActionGroups.Group.VOLUME, ActionGroups.groupOf(Prefs.SHOULDER_VOLUME_MUTE))
        val prefs = Prefs(ApplicationProvider.getApplicationContext())
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_CLOSE_APPS)
        assertEquals(Prefs.TARGET_BOTH, prefs.levelTarget(ShoulderButton.L1))
        assertTrue(prefs.closeConfirm(ShoulderButton.L1))
    }

    @Test
    fun `standby asks only when chosen, and the stored mute levels start empty`() {
        val prefs = Prefs(ApplicationProvider.getApplicationContext())
        assertFalse(prefs.standbyConfirm(ShoulderButton.L1))
        prefs.setStandbyConfirm(ShoulderButton.L1, true)
        assertTrue(prefs.snapshot().bindings.getValue(ShoulderButton.L1.prefKey).standbyConfirm)
        assertFalse(prefs.standbyConfirm(ShoulderButton.R1))
        assertEquals(0, prefs.topVolumeBeforeMute)
        assertEquals(0, prefs.bottomVolumeBeforeMute)
        prefs.topVolumeBeforeMute = 5
        prefs.bottomVolumeBeforeMute = 6
        assertEquals(5, prefs.topVolumeBeforeMute)
        assertEquals(6, prefs.bottomVolumeBeforeMute)
    }

    @Test
    fun `the confirmation choice round trips per button and through snapshots`() {
        val prefs = Prefs(ApplicationProvider.getApplicationContext())
        prefs.setCloseConfirm(ShoulderButton.R1, false)
        assertTrue(prefs.closeConfirm(ShoulderButton.L1))
        val before = prefs.snapshot()
        assertFalse(before.bindings.getValue(ShoulderButton.R1.prefKey).closeConfirm)

        val after = before.copy(bindings = before.bindings + (ShoulderButton.R1.prefKey to BindingSettings(
            Prefs.SHOULDER_CLOSE_BACKGROUND_APPS, targetOverride = Prefs.DISPLAY_BOTTOM, closeConfirm = true,
        )))
        assertTrue(prefs.applySettings(before, after))
        assertEquals(Prefs.SHOULDER_CLOSE_BACKGROUND_APPS, prefs.shoulderAction(ShoulderButton.R1))
        assertEquals(Prefs.DISPLAY_BOTTOM, prefs.levelTarget(ShoulderButton.R1))
        assertTrue(prefs.closeConfirm(ShoulderButton.R1))
    }
}
