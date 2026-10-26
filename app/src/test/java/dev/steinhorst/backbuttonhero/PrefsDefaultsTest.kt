package dev.steinhorst.backbuttonhero

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrefsDefaultsTest {

    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        prefs = Prefs(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `doubleTapEnabled defaults to true`() {
        assertEquals(Prefs.DEFAULT_DOUBLE_TAP_ENABLED, prefs.doubleTapEnabled)
    }

    @Test
    fun `doubleTapMs defaults to 300`() {
        assertEquals(Prefs.DEFAULT_DOUBLE_TAP_MS, prefs.doubleTapMs)
    }

    @Test
    fun `holdEnabled defaults to true`() {
        assertEquals(Prefs.DEFAULT_HOLD_ENABLED, prefs.holdEnabled)
    }

    @Test
    fun `holdMs defaults to 1000`() {
        assertEquals(Prefs.DEFAULT_HOLD_MS, prefs.holdMs)
    }

    @Test
    fun `tapHoldEnabled defaults to true`() {
        assertEquals(Prefs.DEFAULT_TAP_HOLD_ENABLED, prefs.tapHoldEnabled)
    }

    @Test
    fun `tapHoldMs defaults to 1000`() {
        assertEquals(Prefs.DEFAULT_TAP_HOLD_MS, prefs.tapHoldMs)
    }

    @Test
    fun `singleAction defaults to SINGLE_LAUNCHER`() {
        assertEquals(Prefs.SINGLE_LAUNCHER, prefs.singleAction)
    }

    @Test
    fun `singleLauncher is null because SINGLE_LAUNCHER is not a valid flattened ComponentName`() {
        assertNull(prefs.singleLauncher)
    }

    @Test
    fun `doubleTapEnabled round-trips false`() {
        prefs.doubleTapEnabled = false
        assertFalse(prefs.doubleTapEnabled)
    }

    @Test
    fun `doubleTapMs round-trips 450`() {
        prefs.doubleTapMs = 450L
        assertEquals(450L, prefs.doubleTapMs)
    }

    @Test
    fun `holdEnabled round-trips false`() {
        prefs.holdEnabled = false
        assertFalse(prefs.holdEnabled)
    }

    @Test
    fun `holdMs round-trips 1500`() {
        prefs.holdMs = 1500L
        assertEquals(1500L, prefs.holdMs)
    }

    @Test
    fun `tapHoldEnabled round-trips false`() {
        prefs.tapHoldEnabled = false
        assertFalse(prefs.tapHoldEnabled)
    }

    @Test
    fun `tapHoldMs round-trips 1700`() {
        prefs.tapHoldMs = 1700L
        assertEquals(1700L, prefs.tapHoldMs)
    }

    @Test
    fun `singleAction round-trips SINGLE_BACK`() {
        prefs.singleAction = Prefs.SINGLE_BACK
        assertEquals(Prefs.SINGLE_BACK, prefs.singleAction)
    }

    @Test
    fun `shoulderAction for L2 defaults to the screenshot`() {
        assertEquals(Prefs.SHOULDER_SCREENSHOT, prefs.shoulderAction(ShoulderButton.L2))
    }

    @Test
    fun `setShoulderAction for L2 round-trips a package name`() {
        prefs.setShoulderAction(ShoulderButton.L2, "com.example.app")
        assertEquals("com.example.app", prefs.shoulderAction(ShoulderButton.L2))
    }

    @Test
    fun `shoulderDisplay for R2 defaults to DISPLAY_BOTTOM`() {
        // R2 is deliberately chosen over L1/R1: those get v1 navigation-flick defaults in
        // Prefs.init, so testing R2 isolates the plain default from any migration effect.
        assertEquals(Prefs.DISPLAY_BOTTOM, prefs.shoulderDisplay(ShoulderButton.R2))
    }

    @Test
    fun `setShoulderDisplay for R2 round-trips DISPLAY_TOP`() {
        prefs.setShoulderDisplay(ShoulderButton.R2, Prefs.DISPLAY_TOP)
        assertEquals(Prefs.DISPLAY_TOP, prefs.shoulderDisplay(ShoulderButton.R2))
    }

    @Test
    fun `isShoulderApp is false for an empty string`() {
        assertFalse(Prefs.isShoulderApp(""))
    }

    @Test
    fun `isShoulderApp is false for an at-prefixed sentinel`() {
        assertFalse(Prefs.isShoulderApp("@home"))
    }

    @Test
    fun `isShoulderApp is true for a bare package name`() {
        assertTrue(Prefs.isShoulderApp("com.example.app"))
    }
}
