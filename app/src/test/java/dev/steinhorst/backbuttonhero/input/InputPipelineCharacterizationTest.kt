package dev.steinhorst.backbuttonhero.input

import android.accessibilityservice.AccessibilityService
import android.content.ContextWrapper
import android.os.Looper
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.steinhorst.backbuttonhero.SwitcherService
import dev.steinhorst.backbuttonhero.actions.AppControlPrompt
import dev.steinhorst.backbuttonhero.actions.ControllerFocus
import dev.steinhorst.backbuttonhero.core.AppSource
import dev.steinhorst.backbuttonhero.core.RunningApp
import dev.steinhorst.backbuttonhero.overlay.FocusIndicatorOverlay
import dev.steinhorst.backbuttonhero.pad.DpadOverlay
import dev.steinhorst.backbuttonhero.pad.PadHelper
import dev.steinhorst.backbuttonhero.prefs.AppException
import dev.steinhorst.backbuttonhero.prefs.ExceptionControl
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.recents.SwitcherOverlay
import dev.steinhorst.backbuttonhero.support.FakePService
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers

/**
 * Pins the service's key path, driven through the real onKeyEvent, before R4 extracts anything
 * from it: consume/pass-through per half, which binding runs, DOWN-latched decisions and the
 * exception of the controller-focused app. Expected values were observed on the baseline build.
 */
@RunWith(RobolectricTestRunner::class)
class InputPipelineCharacterizationTest {
    private val game = "org.example.game"
    private val other = "org.example.other"

    private fun withService(check: (SwitcherService, Prefs) -> Unit) {
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        val source = object : AppSource {
            override fun appsOnDisplay(displayId: Int) = emptyList<RunningApp>()
            override fun forget(packageName: String) = Unit
        }
        ReflectionHelpers.setField(service, "overlay", SwitcherOverlay(service, source, { true }, {}, { _, _ -> }, {}, { _, _ -> }, {}, {}))
        val helper = PadHelper(service, {}, {}, {})
        // Exercise the key path without starting a real privileged input connection.
        ReflectionHelpers.setField(helper, "closed", true)
        ReflectionHelpers.setField(service, "padHelper", helper)
        ReflectionHelpers.setField(service, "dpadOverlay", DpadOverlay(service, {}, {}))
        ReflectionHelpers.setField(service, "appControlPrompt", AppControlPrompt(service) { false })
        ReflectionHelpers.setField(service, "focusIndicator", FocusIndicatorOverlay(service))
        try {
            val prefs = Prefs(service)
            prefs.doubleTapMs = 300
            prefs.tapHoldMs = 400
            prefs.holdMs = 800
            check(service, prefs)
        } finally {
            service.onDestroy()
        }
    }

    private fun key(action: Int, code: Int, device: Int = 7) =
        KeyEvent(100, 180, action, code, 0, 0, device, 0, 0, InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_KEYBOARD)

    private fun dispatch(service: SwitcherService, action: Int, code: Int) = ReflectionHelpers.callInstanceMethod<Boolean>(
        service, "onKeyEvent", ReflectionHelpers.ClassParameter.from(KeyEvent::class.java, key(action, code)))

    private fun down(service: SwitcherService, code: Int) = dispatch(service, KeyEvent.ACTION_DOWN, code)
    private fun up(service: SwitcherService, code: Int) = dispatch(service, KeyEvent.ACTION_UP, code)

    private fun globalActions(service: SwitcherService) = shadowOf(service as AccessibilityService).globalActionsPerformed

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun appWindow(pkg: String): AccessibilityWindowInfo = AccessibilityWindowInfo.obtain().also { window ->
        shadowOf(window).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        shadowOf(window).setRoot(AccessibilityNodeInfo.obtain().apply { packageName = pkg })
    }

    private fun allowing(pkg: String, vararg controls: ExceptionControl) =
        AppException(pkg, pkg, controls.sumOf { it.bit })

    private val shoulderKeys = listOf(
        ShoulderButton.L1 to KeyEvent.KEYCODE_BUTTON_L1,
        ShoulderButton.L2 to KeyEvent.KEYCODE_BUTTON_L2,
        ShoulderButton.R1 to KeyEvent.KEYCODE_BUTTON_R1,
        ShoulderButton.R2 to KeyEvent.KEYCODE_BUTTON_R2,
    )

    // F-CMB-1
    @Test
    fun `Back plus each shoulder runs its binding, withholds both halves and adds no tap`() = withService { service, prefs ->
        prefs.setShoulderAction(BackGestureButton.SINGLE, Prefs.SHOULDER_NOTIFICATIONS)
        for ((button, code) in shoulderKeys) {
            prefs.setShoulderAction(button, Prefs.SHOULDER_QUICK_SETTINGS)
            globalActions(service).clear()
            assertTrue(down(service, KeyEvent.KEYCODE_BACK))
            assertTrue("$button DOWN", down(service, code))
            assertTrue("$button UP", up(service, code))
            assertTrue(up(service, KeyEvent.KEYCODE_BACK))
            idle(2000)
            assertEquals("$button", listOf(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS), globalActions(service).toList())
            prefs.setShoulderAction(button, "")
        }
    }

    // F-CMB-5
    @Test
    fun `a bound shoulder without Back is left to the app, both halves`() = withService { service, prefs ->
        for ((button, code) in shoulderKeys) {
            prefs.setShoulderAction(button, Prefs.SHOULDER_QUICK_SETTINGS)
            assertFalse("$button DOWN", down(service, code))
            assertFalse("$button UP", up(service, code))
        }
        idle(2000)
        assertTrue(globalActions(service).isEmpty())
    }

    // F-CMB-4
    @Test
    fun `an unassigned shoulder with Back held reaches the app and Back stays a tap`() = withService { service, prefs ->
        prefs.setShoulderAction(BackGestureButton.SINGLE, Prefs.SHOULDER_NOTIFICATIONS)
        assertTrue(down(service, KeyEvent.KEYCODE_BACK))
        assertFalse(down(service, KeyEvent.KEYCODE_BUTTON_L1))
        assertFalse(up(service, KeyEvent.KEYCODE_BUTTON_L1))
        assertTrue(up(service, KeyEvent.KEYCODE_BACK))
        idle(2000)
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS), globalActions(service).toList())
        globalActions(service).clear()
        assertFalse(down(service, KeyEvent.KEYCODE_BUTTON_L1))
        assertFalse(up(service, KeyEvent.KEYCODE_BUTTON_L1))
        idle(2000)
        assertTrue(globalActions(service).isEmpty())
    }

    // F-GES-8
    @Test
    fun `a press after a tap is judged at DOWN, so the pending single tap never fires mid-hold`() = withService { service, prefs ->
        prefs.setShoulderAction(BackGestureButton.SINGLE, Prefs.SHOULDER_QUICK_SETTINGS)
        prefs.setShoulderAction(BackGestureButton.DOUBLE, Prefs.SHOULDER_NOTIFICATIONS)
        down(service, KeyEvent.KEYCODE_BACK)
        up(service, KeyEvent.KEYCODE_BACK)
        idle(100)
        down(service, KeyEvent.KEYCODE_BACK)
        // Past the first tap's single-tap deadline and the double-tap window while still held.
        idle(400)
        assertTrue(globalActions(service).isEmpty())
        up(service, KeyEvent.KEYCODE_BACK)
        idle(2000)
        // Released too late for a double tap: one single tap, from the second release.
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS), globalActions(service).toList())
    }

    // F-GES-8
    @Test
    fun `within the window the same sequence is a double tap and no single tap`() = withService { service, prefs ->
        prefs.setShoulderAction(BackGestureButton.SINGLE, Prefs.SHOULDER_QUICK_SETTINGS)
        prefs.setShoulderAction(BackGestureButton.DOUBLE, Prefs.SHOULDER_NOTIFICATIONS)
        down(service, KeyEvent.KEYCODE_BACK)
        up(service, KeyEvent.KEYCODE_BACK)
        idle(100)
        down(service, KeyEvent.KEYCODE_BACK)
        idle(100)
        up(service, KeyEvent.KEYCODE_BACK)
        idle(2000)
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS), globalActions(service).toList())
    }

    // F-GES-8
    @Test
    fun `tap then hold latched at DOWN runs tap-and-hold and never the hold`() = withService { service, prefs ->
        prefs.setShoulderAction(BackGestureButton.SINGLE, Prefs.SHOULDER_QUICK_SETTINGS)
        prefs.setShoulderAction(BackGestureButton.TAP_HOLD, Prefs.SHOULDER_NOTIFICATIONS)
        prefs.setShoulderAction(BackGestureButton.HOLD, Prefs.SHOULDER_BACK)
        down(service, KeyEvent.KEYCODE_BACK)
        up(service, KeyEvent.KEYCODE_BACK)
        idle(100)
        down(service, KeyEvent.KEYCODE_BACK)
        idle(1500)
        assertTrue(up(service, KeyEvent.KEYCODE_BACK))
        idle(2000)
        // No double tap bound, so the first tap ran at once; the second press is tap-and-hold only.
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS),
            globalActions(service).toList())
    }

    // F-X-1
    @Test
    fun `rebinding or unbinding a held combo keeps its release withheld and runs nothing more`() = withService { service, prefs ->
        prefs.setShoulderAction(BackGestureButton.SINGLE, Prefs.SHOULDER_NOTIFICATIONS)
        for (change in listOf(Prefs.SHOULDER_BACK, "")) {
            prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_QUICK_SETTINGS)
            globalActions(service).clear()
            down(service, KeyEvent.KEYCODE_BACK)
            assertTrue(down(service, KeyEvent.KEYCODE_BUTTON_L1))
            prefs.setShoulderAction(ShoulderButton.L1, change)
            assertTrue("change to '$change'", up(service, KeyEvent.KEYCODE_BUTTON_L1))
            assertTrue(up(service, KeyEvent.KEYCODE_BACK))
            idle(2000)
            assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS), globalActions(service).toList())
        }
    }

    // F-X-1
    @Test
    fun `a binding made while Back is held counts from the button's DOWN`() = withService { service, prefs ->
        down(service, KeyEvent.KEYCODE_BACK)
        prefs.setShoulderAction(ShoulderButton.R1, Prefs.SHOULDER_QUICK_SETTINGS)
        assertTrue(down(service, KeyEvent.KEYCODE_BUTTON_R1))
        assertTrue(up(service, KeyEvent.KEYCODE_BUTTON_R1))
        up(service, KeyEvent.KEYCODE_BACK)
        idle(2000)
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS), globalActions(service).toList())
    }

    // F-X-1
    @Test
    fun `the single-tap binding is read when the tap fires, not at the press`() = withService { service, prefs ->
        prefs.setShoulderAction(BackGestureButton.SINGLE, Prefs.SHOULDER_QUICK_SETTINGS)
        prefs.setShoulderAction(BackGestureButton.DOUBLE, Prefs.SHOULDER_STATUS)
        down(service, KeyEvent.KEYCODE_BACK)
        up(service, KeyEvent.KEYCODE_BACK)
        prefs.setShoulderAction(BackGestureButton.SINGLE, Prefs.SHOULDER_NOTIFICATIONS)
        idle(2000)
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS), globalActions(service).toList())
    }

    // F-X-1, F-EXC-3
    @Test
    fun `an exception added while Back is held applies from the next press`() = withService { service, prefs ->
        shadowOf(service).setWindowsOnDisplay(0, listOf(appWindow(game)))
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_QUICK_SETTINGS)
        down(service, KeyEvent.KEYCODE_BACK)
        prefs.appExceptions = listOf(allowing(game, ExceptionControl.SINGLE))
        assertTrue(down(service, KeyEvent.KEYCODE_BUTTON_L1))
        assertTrue(up(service, KeyEvent.KEYCODE_BUTTON_L1))
        up(service, KeyEvent.KEYCODE_BACK)
        idle(2000)
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS), globalActions(service).toList())
        globalActions(service).clear()
        down(service, KeyEvent.KEYCODE_BACK)
        assertFalse(down(service, KeyEvent.KEYCODE_BUTTON_L1))
        assertFalse(up(service, KeyEvent.KEYCODE_BUTTON_L1))
        up(service, KeyEvent.KEYCODE_BACK)
        idle(2000)
        assertTrue(globalActions(service).isEmpty())
    }

    // F-EXC-3, F-X-3
    @Test
    fun `the app on the controller-focused screen decides, not the one on the other screen`() {
        val bottom = ShadowDisplayManager.addDisplay("w400dp-h300dp")
        try {
            withService { service, prefs ->
                shadowOf(service).setWindowsOnDisplay(0, listOf(appWindow(game)))
                shadowOf(service).setWindowsOnDisplay(bottom, listOf(appWindow(other)))
                prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_QUICK_SETTINGS)
                prefs.appExceptions = listOf(allowing(game, ExceptionControl.SINGLE))
                val resolver = service.contentResolver
                fun combo(): Pair<Boolean, Boolean> {
                    down(service, KeyEvent.KEYCODE_BACK)
                    val result = down(service, KeyEvent.KEYCODE_BUTTON_L1) to up(service, KeyEvent.KEYCODE_BUTTON_L1)
                    up(service, KeyEvent.KEYCODE_BACK)
                    idle(2000)
                    return result
                }
                // Focus on top (the game): its exception passes L1 on and Back's release is no tap.
                assertEquals(false to false, combo())
                assertTrue(globalActions(service).isEmpty())
                // Focus on bottom (no exception there): the combo is ours although the game is still on top.
                Settings.System.putString(resolver, ControllerFocus.FOCUS_SETTING, ControllerFocus.BOTTOM_VALUE)
                assertEquals(true to true, combo())
                assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS), globalActions(service).toList())
                globalActions(service).clear()
                // The game moved to the focused bottom screen: its exception applies there.
                shadowOf(service).setWindowsOnDisplay(0, listOf(appWindow(other)))
                shadowOf(service).setWindowsOnDisplay(bottom, listOf(appWindow(game)))
                assertEquals(false to false, combo())
                assertTrue(globalActions(service).isEmpty())
                // A switched-off row is ignored.
                prefs.appExceptions = listOf(allowing(game, ExceptionControl.SINGLE).copy(active = false))
                assertEquals(true to true, combo())
            }
        } finally {
            ShadowDisplayManager.removeDisplay(bottom)
        }
    }

    // F-SVC-2
    @Test
    fun `gestures and combos without a focus action never move focus or open a window`() {
        val fake = FakePService().install()
        try {
            withService { service, prefs ->
                prefs.setShoulderAction(BackGestureButton.SINGLE, Prefs.SHOULDER_QUICK_SETTINGS)
                prefs.setShoulderAction(BackGestureButton.DOUBLE, Prefs.SHOULDER_NOTIFICATIONS)
                prefs.setShoulderAction(BackGestureButton.TAP_HOLD, Prefs.SHOULDER_QUICK_SETTINGS)
                prefs.setShoulderAction(BackGestureButton.HOLD, Prefs.SHOULDER_NOTIFICATIONS)
                prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_QUICK_SETTINGS)
                val before = fake.commands.size
                fun tap() { down(service, KeyEvent.KEYCODE_BACK); up(service, KeyEvent.KEYCODE_BACK) }
                tap(); idle(2000)
                tap(); idle(100); tap(); idle(2000)
                tap(); idle(100); down(service, KeyEvent.KEYCODE_BACK); idle(1500); up(service, KeyEvent.KEYCODE_BACK); idle(2000)
                down(service, KeyEvent.KEYCODE_BACK); idle(1500); up(service, KeyEvent.KEYCODE_BACK); idle(2000)
                down(service, KeyEvent.KEYCODE_BACK)
                down(service, KeyEvent.KEYCODE_BUTTON_L1); up(service, KeyEvent.KEYCODE_BUTTON_L1)
                down(service, KeyEvent.KEYCODE_BUTTON_R1); up(service, KeyEvent.KEYCODE_BUTTON_R1)
                up(service, KeyEvent.KEYCODE_BACK); idle(2000)
                down(service, KeyEvent.KEYCODE_BUTTON_L1); up(service, KeyEvent.KEYCODE_BUTTON_L1); idle(2000)
                // single, double, tap-and-hold, hold, Back + L1; unbound R1 and L1 alone run nothing.
                assertEquals(5, globalActions(service).size)
                assertEquals(emptyList<String>(), fake.commands.drop(before))
                assertNull(Settings.System.getString(service.contentResolver, ControllerFocus.FOCUS_SETTING))
                assertNull(shadowOf(service as ContextWrapper).nextStartedActivity)
            }
        } finally {
            fake.uninstall()
        }
    }
}
