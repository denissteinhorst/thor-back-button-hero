package dev.steinhorst.backbuttonhero

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrefsMigrationTest {

    private fun rawPrefs(context: Context) =
        context.getSharedPreferences("back_button_hero", Context.MODE_PRIVATE)

    private fun context() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `fresh install applies v1 navigation-flick defaults and marks both migrations done`() {
        val ctx = context()
        val prefs = Prefs(ctx)

        assertTrue(rawPrefs(ctx).getBoolean("navigation_flick_defaults_v1", false))
        assertEquals(Prefs.SHOULDER_FLICK_LEFT, prefs.shoulderAction(ShoulderButton.L1))
        assertEquals(Prefs.SHOULDER_FLICK_RIGHT, prefs.shoulderAction(ShoulderButton.R1))
        assertTrue(rawPrefs(ctx).getBoolean("navigation_flick_directions_v2", false))
    }

    @Test
    fun `v1 install without v2 gets its flick directions swapped`() {
        val ctx = context()
        rawPrefs(ctx).edit()
            .putBoolean("navigation_flick_defaults_v1", true)
            .putString("l1_package", Prefs.SHOULDER_FLICK_LEFT)
            .putString("r1_package", Prefs.SHOULDER_FLICK_RIGHT)
            .apply()

        val prefs = Prefs(ctx)

        assertEquals(Prefs.SHOULDER_FLICK_RIGHT, prefs.shoulderAction(ShoulderButton.L1))
        assertEquals(Prefs.SHOULDER_FLICK_LEFT, prefs.shoulderAction(ShoulderButton.R1))
        assertTrue(rawPrefs(ctx).getBoolean("navigation_flick_directions_v2", false))
    }

    @Test
    fun `v1 and v2 already applied, a custom value is left untouched`() {
        val ctx = context()
        rawPrefs(ctx).edit()
            .putBoolean("navigation_flick_defaults_v1", true)
            .putBoolean("navigation_flick_directions_v2", true)
            .putString("l1_package", "com.custom.app")
            .apply()

        val prefs = Prefs(ctx)

        assertEquals("com.custom.app", prefs.shoulderAction(ShoulderButton.L1))
    }

    @Test
    fun `the retired at-swap_screens sentinel is cleared on construction`() {
        val ctx = context()
        rawPrefs(ctx).edit()
            .putBoolean("navigation_flick_defaults_v1", true)
            .putBoolean("navigation_flick_directions_v2", true)
            .putString("l2_package", "@swap_screens")
            .apply()

        val prefs = Prefs(ctx)

        assertEquals("", prefs.shoulderAction(ShoulderButton.L2))
    }

    @Test
    fun `constructing Prefs a second time does not override an explicit user value`() {
        val ctx = context()
        Prefs(ctx).apply { singleAction = Prefs.SINGLE_BACK }

        val second = Prefs(ctx)

        assertEquals(Prefs.SINGLE_BACK, second.singleAction)
    }

    @Test
    fun `resetToDefaults restores every property to its default, including unset shoulder buttons`() {
        val ctx = context()
        val prefs = Prefs(ctx)

        prefs.doubleTapEnabled = false
        prefs.doubleTapMs = 450L
        prefs.holdEnabled = false
        prefs.holdMs = 1500L
        prefs.tapHoldEnabled = false
        prefs.tapHoldMs = 1700L
        prefs.singleAction = Prefs.SINGLE_BACK
        prefs.setShoulderAction(ShoulderButton.L1, "com.other.app")
        prefs.setShoulderAction(ShoulderButton.R2, "com.other.app")

        prefs.resetToDefaults()

        assertEquals(Prefs.DEFAULT_DOUBLE_TAP_ENABLED, prefs.doubleTapEnabled)
        assertEquals(Prefs.DEFAULT_DOUBLE_TAP_MS, prefs.doubleTapMs)
        assertEquals(Prefs.DEFAULT_HOLD_ENABLED, prefs.holdEnabled)
        assertEquals(Prefs.DEFAULT_HOLD_MS, prefs.holdMs)
        assertEquals(Prefs.DEFAULT_TAP_HOLD_ENABLED, prefs.tapHoldEnabled)
        assertEquals(Prefs.DEFAULT_TAP_HOLD_MS, prefs.tapHoldMs)
        assertEquals(Prefs.SINGLE_LAUNCHER, prefs.singleAction)
        assertEquals(Prefs.SHOULDER_FLICK_LEFT, prefs.shoulderAction(ShoulderButton.L1))
        assertEquals(Prefs.SHOULDER_FLICK_RIGHT, prefs.shoulderAction(ShoulderButton.R1))
        assertEquals("", prefs.shoulderAction(ShoulderButton.R2))
    }
}
