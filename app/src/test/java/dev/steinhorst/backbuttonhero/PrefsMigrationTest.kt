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
    fun `retired single-stick toggles migrate to the default pair without changing duration`() {
        val ctx = context()
        for (legacy in listOf("l3", "r3")) {
            rawPrefs(ctx).edit().clear().putString("toggle_buttons", legacy)
                .putLong("toggle_hold_ms", 1700L).apply()
            val prefs = Prefs(ctx)
            assertEquals(Prefs.TOGGLE_L2_R2, prefs.toggleButtons)
            assertEquals(1700L, prefs.toggleHoldMs)
            assertTrue(rawPrefs(ctx).getBoolean("toggle_pairs_v1", false))
            assertEquals(Prefs.TOGGLE_L2_R2, rawPrefs(ctx).getString("toggle_buttons", null))
        }
    }

    @Test
    fun `pair migration preserves supported choices and subsequent user selections`() {
        val ctx = context()
        for (binding in listOf(Prefs.TOGGLE_NONE, Prefs.TOGGLE_BOTH, Prefs.TOGGLE_L1_R1, Prefs.TOGGLE_L2_R2)) {
            rawPrefs(ctx).edit().clear().putString("toggle_buttons", binding).apply()
            assertEquals(binding, Prefs(ctx).toggleButtons)
            Prefs(ctx).toggleButtons = Prefs.TOGGLE_L2_R2
            assertEquals(Prefs.TOGGLE_L2_R2, Prefs(ctx).toggleButtons)
        }
    }

    @Test
    fun `new toggle default applies to an unset selection and preserves explicit pairs`() {
        val ctx = context()
        rawPrefs(ctx).edit().clear().putBoolean("toggle_pairs_v1", true).apply()
        assertEquals(Prefs.TOGGLE_L2_R2, Prefs(ctx).toggleButtons)
        assertTrue(rawPrefs(ctx).getBoolean("toggle_default_v2", false))
        for (binding in listOf(Prefs.TOGGLE_NONE, Prefs.TOGGLE_BOTH, Prefs.TOGGLE_L1_R1, Prefs.TOGGLE_L2_R2)) {
            rawPrefs(ctx).edit().putString("toggle_buttons", binding).remove("toggle_default_v2").apply()
            assertEquals(binding, Prefs(ctx).toggleButtons)
            assertEquals(binding, Prefs(ctx).toggleButtons)
        }
    }

    @Test
    fun `a fresh install starts with nothing assigned and every migration marked done`() {
        val ctx = context()
        val prefs = Prefs(ctx)

        (ShoulderButton.entries + DpadDirection.entries + StickButton.entries +
            StickClickButton.entries + BackGestureButton.entries).forEach {
            assertEquals(it.name, "", prefs.shoulderAction(it))
        }
        assertEquals(Prefs.TOGGLE_NONE, prefs.toggleButtons)
        assertEquals(SettingsState(), prefs.snapshot())
        listOf("navigation_flick_defaults_v1", "navigation_flick_directions_v2", "shoulder_defaults_v3",
            "back_gestures_v1", "toggle_pairs_v1", "toggle_default_v2", "unassigned_defaults_v1")
            .forEach { assertTrue(it, rawPrefs(ctx).getBoolean(it, false)) }
    }

    @Test
    fun `an update pins the D-pad shortcuts and the activation pair it was running on`() {
        val ctx = context()
        rawPrefs(ctx).edit()
            .putBoolean("navigation_flick_defaults_v1", true)
            .putBoolean("navigation_flick_directions_v2", true)
            .putBoolean("shoulder_defaults_v3", true)
            .putBoolean("back_gestures_v1", true)
            .putBoolean("toggle_pairs_v1", true)
            .putBoolean("toggle_default_v2", true)
            .putString("dpad_left_package", "")
            .apply()

        val prefs = Prefs(ctx)

        assertEquals(Prefs.SHOULDER_BRIGHTNESS_UP, prefs.shoulderAction(DpadDirection.UP))
        assertEquals(Prefs.SHOULDER_BRIGHTNESS_DOWN, prefs.shoulderAction(DpadDirection.DOWN))
        assertEquals("", prefs.shoulderAction(DpadDirection.LEFT))
        assertEquals(Prefs.SHOULDER_VOLUME_UP, prefs.shoulderAction(DpadDirection.RIGHT))
        assertEquals(Prefs.TOGGLE_L2_R2, prefs.toggleButtons)
        assertTrue(rawPrefs(ctx).getBoolean("unassigned_defaults_v1", false))
        // Pinned once: a later user choice is never replaced.
        prefs.setShoulderAction(DpadDirection.UP, "")
        assertEquals("", Prefs(ctx).shoulderAction(DpadDirection.UP))
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
        Prefs(ctx).apply { setShoulderAction(BackGestureButton.SINGLE, Prefs.SHOULDER_BACK) }

        val second = Prefs(ctx)

        assertEquals(Prefs.SHOULDER_BACK, second.shoulderAction(BackGestureButton.SINGLE))
    }

    @Test
    fun `an update keeps unassigned L2 and R2 unassigned`() {
        val ctx = context()
        rawPrefs(ctx).edit()
            .putBoolean("navigation_flick_defaults_v1", true)
            .putBoolean("navigation_flick_directions_v2", true)
            .apply()

        val prefs = Prefs(ctx)

        assertEquals("", prefs.shoulderAction(ShoulderButton.L2))
        assertEquals("", prefs.shoulderAction(ShoulderButton.R2))
        assertTrue(rawPrefs(ctx).getBoolean("shoulder_defaults_v3", false))
    }

    @Test
    fun `an update keeps a user's own L2 and R2 choices`() {
        val ctx = context()
        rawPrefs(ctx).edit()
            .putBoolean("navigation_flick_defaults_v1", true)
            .putBoolean("navigation_flick_directions_v2", true)
            .putString("l2_package", "com.custom.app")
            .putString("r2_package", Prefs.SHOULDER_RECENTS)
            .apply()

        val prefs = Prefs(ctx)

        assertEquals("com.custom.app", prefs.shoulderAction(ShoulderButton.L2))
        assertEquals(Prefs.SHOULDER_RECENTS, prefs.shoulderAction(ShoulderButton.R2))
    }

    @Test
    fun `the factory shoulder defaults are applied only once`() {
        val ctx = context()
        Prefs(ctx).setShoulderAction(ShoulderButton.R2, "")

        assertEquals("", Prefs(ctx).shoulderAction(ShoulderButton.R2))
    }

    @Test
    fun `an update carries the old Back gesture switches and single choice over unchanged`() {
        val ctx = context()
        rawPrefs(ctx).edit()
            .putBoolean("navigation_flick_defaults_v1", true)
            .putBoolean("navigation_flick_directions_v2", true)
            .putBoolean("shoulder_defaults_v3", true)
            .putString("single_action", "back")
            .putBoolean("double_enabled", false)
            .putBoolean("tap_hold_enabled", true)
            .putBoolean("hold_enabled", false)
            .apply()

        val prefs = Prefs(ctx)

        assertEquals(Prefs.SHOULDER_BACK, prefs.shoulderAction(BackGestureButton.SINGLE))
        assertEquals("", prefs.shoulderAction(BackGestureButton.DOUBLE))
        assertEquals(Prefs.SHOULDER_RECENTS, prefs.shoulderAction(BackGestureButton.TAP_HOLD))
        assertEquals("", prefs.shoulderAction(BackGestureButton.HOLD))
    }

    @Test
    fun `an update with the old defaults, or a custom launcher, ends up on Home and the old gestures`() {
        val ctx = context()
        rawPrefs(ctx).edit()
            .putBoolean("navigation_flick_defaults_v1", true)
            .putBoolean("navigation_flick_directions_v2", true)
            .putBoolean("shoulder_defaults_v3", true)
            .putString("single_action", "com.example.launcher/com.example.launcher.Main")
            .apply()

        val prefs = Prefs(ctx)

        assertEquals(Prefs.SHOULDER_HOME, prefs.shoulderAction(BackGestureButton.SINGLE))
        assertEquals(Prefs.SHOULDER_SWITCHER, prefs.shoulderAction(BackGestureButton.DOUBLE))
        assertEquals(Prefs.SHOULDER_RECENTS, prefs.shoulderAction(BackGestureButton.TAP_HOLD))
        assertEquals(Prefs.SHOULDER_SWAP_APPS, prefs.shoulderAction(BackGestureButton.HOLD))
    }

    @Test
    fun `the Back gesture migration runs once and never overrides a later choice`() {
        val ctx = context()
        rawPrefs(ctx).edit().putBoolean("hold_enabled", false).apply()
        val first = Prefs(ctx)
        assertEquals("", first.shoulderAction(BackGestureButton.HOLD))
        first.setShoulderAction(BackGestureButton.HOLD, Prefs.SHOULDER_SCREENSHOT)
        rawPrefs(ctx).edit().putBoolean("hold_enabled", false).apply()
        assertEquals(Prefs.SHOULDER_SCREENSHOT, Prefs(ctx).shoulderAction(BackGestureButton.HOLD))
    }

    @Test
    fun `existing exceptions keep their stick shortcuts on once, malformed lines stay as they are`() {
        val ctx = context()
        rawPrefs(ctx).edit().clear()
            .putString("app_exceptions", "com.a\t5\t1\tA\nbroken line\ncom.b\t4095\t0\tB").apply()
        val exceptions = Prefs(ctx).appExceptions
        assertEquals(listOf(5 or (ExceptionControl.STICK_BITS shl 12), 4095 or (ExceptionControl.STICK_BITS shl 12)), exceptions.map { it.allowed })
        assertTrue(rawPrefs(ctx).getBoolean("exception_sticks_v2", false))
        // A stick the user switched off afterwards is not switched back on by a later start.
        Prefs(ctx).appExceptions = exceptions.map { it.copy(allowed = 5) }
        assertEquals(listOf(5, 5), Prefs(ctx).appExceptions.map { it.allowed })
    }

    @Test
    fun `a fresh install has nothing to migrate`() {
        val ctx = context()
        rawPrefs(ctx).edit().clear().apply()
        assertEquals(emptyList<AppException>(), Prefs(ctx).appExceptions)
        assertTrue(rawPrefs(ctx).getBoolean("exception_sticks_v2", false))
    }
}
