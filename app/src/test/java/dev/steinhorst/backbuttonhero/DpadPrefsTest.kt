package dev.steinhorst.backbuttonhero

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DpadPrefsTest {

    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        prefs = Prefs(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `D-pad directions and shoulder buttons all start unassigned`() {
        (DpadDirection.entries + ShoulderButton.entries).forEach { assertEquals(it.name, "", prefs.shoulderAction(it)) }
    }

    @Test
    fun `level target defaults to both screens for every button`() {
        (DpadDirection.entries + ShoulderButton.entries).forEach {
            assertEquals(Prefs.TARGET_BOTH, prefs.levelTarget(it))
        }
    }

    @Test
    fun `level target round-trips per button`() {
        prefs.setLevelTarget(DpadDirection.UP, Prefs.DISPLAY_TOP)
        prefs.setLevelTarget(ShoulderButton.L1, Prefs.DISPLAY_BOTTOM)
        assertEquals(Prefs.DISPLAY_TOP, prefs.levelTarget(DpadDirection.UP))
        assertEquals(Prefs.DISPLAY_BOTTOM, prefs.levelTarget(ShoulderButton.L1))
        assertEquals(Prefs.TARGET_BOTH, prefs.levelTarget(DpadDirection.DOWN))
    }

    @Test
    fun `screenshot defaults to the top screen, which is what it always did`() {
        prefs.setShoulderAction(ShoulderButton.R2, Prefs.SHOULDER_SCREENSHOT)
        assertEquals(Prefs.DISPLAY_TOP, prefs.levelTarget(ShoulderButton.R2))
    }

    @Test
    fun `an explicit screenshot target wins, including both screens`() {
        prefs.setShoulderAction(ShoulderButton.R2, Prefs.SHOULDER_SCREENSHOT)
        prefs.setLevelTarget(ShoulderButton.R2, Prefs.TARGET_BOTH)
        assertEquals(Prefs.TARGET_BOTH, prefs.levelTarget(ShoulderButton.R2))
        prefs.setLevelTarget(ShoulderButton.R2, Prefs.DISPLAY_BOTTOM)
        assertEquals(Prefs.DISPLAY_BOTTOM, prefs.levelTarget(ShoulderButton.R2))
    }

    @Test
    fun `hasScreenTarget covers level screenshot and controller focus actions`() {
        listOf(
            Prefs.SHOULDER_VOLUME_UP, Prefs.SHOULDER_VOLUME_DOWN,
            Prefs.SHOULDER_BRIGHTNESS_UP, Prefs.SHOULDER_BRIGHTNESS_DOWN, Prefs.SHOULDER_SCREENSHOT,
            Prefs.SHOULDER_CONTROLLER_FOCUS,
        ).forEach { assertTrue(it, Prefs.hasScreenTarget(it)) }
        listOf("", Prefs.SHOULDER_HOME, Prefs.SHOULDER_RECENTS, "com.android.chrome")
            .forEach { assertFalse(it, Prefs.hasScreenTarget(it)) }
    }

    @Test
    fun `controller focus target defaults to toggle and round-trips per button`() {
        assertEquals(Prefs.FOCUS_TOGGLE, prefs.focusTarget(ShoulderButton.R2))
        prefs.setFocusTarget(ShoulderButton.R2, Prefs.DISPLAY_BOTTOM)
        assertEquals(Prefs.DISPLAY_BOTTOM, prefs.focusTarget(ShoulderButton.R2))
        assertEquals(Prefs.FOCUS_TOGGLE, prefs.focusTarget(DpadDirection.UP))
    }

    @Test
    fun `focus indicator settings default per button and round-trip`() {
        assertEquals(Prefs.FOCUS_POS_CENTER_RIGHT, prefs.focusPosition(ShoulderButton.L1))
        prefs.setFocusPosition(ShoulderButton.L1, Prefs.FOCUS_POS_TOP_RIGHT)
        assertEquals(Prefs.FOCUS_POS_TOP_RIGHT, prefs.focusPosition(ShoulderButton.L1))
        assertEquals(Prefs.FOCUS_POS_CENTER_RIGHT, prefs.focusPosition(DpadDirection.UP))
        assertEquals(500L, prefs.focusDuration(DpadDirection.UP))
        assertTrue(prefs.focusIndicator(ShoulderButton.L1))
        prefs.setFocusIndicator(ShoulderButton.L1, false)
        assertFalse(prefs.focusIndicator(ShoulderButton.L1))
        assertTrue(prefs.focusIndicator(DpadDirection.UP))
        assertEquals(500L, prefs.focusDuration(ShoulderButton.L1))
        prefs.setFocusDuration(ShoulderButton.L1, 400L)
        assertEquals(400L, prefs.focusDuration(ShoulderButton.L1))
        prefs.setFocusDuration(ShoulderButton.L1, 50_000L)
        assertEquals(Prefs.MAX_FOCUS_DURATION_MS, prefs.focusDuration(ShoulderButton.L1))
    }

    @Test
    fun `a cleared D-pad direction stays cleared instead of falling back to its default`() {
        prefs.setShoulderAction(DpadDirection.UP, "")
        assertEquals("", prefs.shoulderAction(DpadDirection.UP))
    }

    @Test
    fun `hasDpadAction is false by default and follows the assignments`() {
        assertFalse(prefs.hasDpadAction())
        prefs.setShoulderAction(DpadDirection.LEFT, Prefs.SHOULDER_SCREENSHOT)
        assertTrue(prefs.hasDpadAction())
        prefs.setShoulderAction(DpadDirection.LEFT, "")
        assertFalse(prefs.hasDpadAction())
    }

    @Test
    fun `isLevelAction covers exactly the four volume and brightness actions`() {
        listOf(Prefs.SHOULDER_VOLUME_UP, Prefs.SHOULDER_VOLUME_DOWN, Prefs.SHOULDER_BRIGHTNESS_UP, Prefs.SHOULDER_BRIGHTNESS_DOWN)
            .forEach { assertTrue(it, Prefs.isLevelAction(it)) }
        listOf("", Prefs.SHOULDER_HOME, Prefs.SHOULDER_SCREENSHOT, "com.android.chrome")
            .forEach { assertFalse(it, Prefs.isLevelAction(it)) }
    }

    @Test
    fun `listeners hear changes from another Prefs instance until unregistered`() {
        var calls = 0
        val listener: () -> Unit = { calls++ }
        prefs.registerListener(listener)
        Prefs(ApplicationProvider.getApplicationContext()).setShoulderAction(DpadDirection.DOWN, "")
        assertTrue(calls > 0)
        val seen = calls
        prefs.unregisterListener(listener)
        prefs.setShoulderAction(DpadDirection.DOWN, Prefs.SHOULDER_HOME)
        assertEquals(seen, calls)
    }

    @Test
    fun `unregistering an unknown listener is harmless`() {
        prefs.unregisterListener { }
    }
}
