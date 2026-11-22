package dev.steinhorst.backbuttonhero

import android.accessibilityservice.AccessibilityService
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import dev.steinhorst.backbuttonhero.core.AppSource
import dev.steinhorst.backbuttonhero.core.RunningApp
import dev.steinhorst.backbuttonhero.overlay.FocusIndicatorOverlay
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

/** Dual mode's key routing: which modifier owns a press and which layer its gestures and combos run. */
@RunWith(RobolectricTestRunner::class)
class DualModeServiceTest {
    private fun withService(check: (SwitcherService, Prefs) -> Unit) {
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        val source = object : AppSource {
            override fun appsOnDisplay(displayId: Int) = emptyList<RunningApp>()
            override fun forget(packageName: String) = Unit
        }
        ReflectionHelpers.setField(service, "overlay", SwitcherOverlay(service, source, { true }, {}, { _, _ -> }, {}, { _, _ -> }, {}, {}))
        val helper = PadHelper(service, {}, {}, {})
        ReflectionHelpers.setField(helper, "closed", true)
        ReflectionHelpers.setField(service, "padHelper", helper)
        ReflectionHelpers.setField(service, "dpadOverlay", DpadOverlay(service, {}, {}))
        ReflectionHelpers.setField(service, "appControlPrompt", AppControlPrompt(service) { false })
        ReflectionHelpers.setField(service, "focusIndicator", FocusIndicatorOverlay(service))
        try {
            val prefs = Prefs(service)
            // Nothing copied from the Back layer, so every test states exactly what each layer holds.
            prefs.applySettings(prefs.snapshot(), prefs.snapshot().copy(dualSeeded = true))
            prefs.dualMode = true
            check(service, prefs)
        } finally {
            service.onDestroy()
        }
    }

    private fun key(action: Int, code: Int, device: Int = 7) =
        KeyEvent(100, 180, action, code, 0, 0, device, 0, 0, InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_KEYBOARD)

    private fun dispatch(service: SwitcherService, event: KeyEvent) = ReflectionHelpers.callInstanceMethod<Boolean>(
        service, "onKeyEvent", ReflectionHelpers.ClassParameter.from(KeyEvent::class.java, event))

    private fun press(service: SwitcherService, code: Int) {
        dispatch(service, key(KeyEvent.ACTION_DOWN, code))
        dispatch(service, key(KeyEvent.ACTION_UP, code))
    }

    private fun machine(service: SwitcherService, name: String) =
        ReflectionHelpers.callInstanceMethod<BackGestureMachine>(service, name)

    private fun globalActions(service: SwitcherService) = shadowOf(service as AccessibilityService).globalActionsPerformed

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

    @Test
    fun `Home owns its press on its own machine, and Back meanwhile is swallowed`() = withService { service, _ ->
        assertTrue(dispatch(service, key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME)))
        assertTrue(machine(service, "getHomeGesture").backDown)
        assertFalse(machine(service, "getBackGesture").backDown)
        assertTrue(dispatch(service, key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
        assertFalse(machine(service, "getBackGesture").backDown)
        assertTrue(dispatch(service, key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
        assertTrue(dispatch(service, key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME)))
        assertFalse(machine(service, "getHomeGesture").backDown)
    }

    @Test
    fun `Back first owns the press, and Home meanwhile is swallowed`() = withService { service, _ ->
        assertTrue(dispatch(service, key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
        assertTrue(dispatch(service, key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME)))
        assertTrue(machine(service, "getBackGesture").backDown)
        assertFalse(machine(service, "getHomeGesture").backDown)
        assertTrue(dispatch(service, key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME)))
        assertTrue(dispatch(service, key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
        assertFalse(machine(service, "getBackGesture").backDown)
    }

    @Test
    fun `the same button runs each layer's own binding`() = withService { service, prefs ->
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_QUICK_SETTINGS)
        prefs.setShoulderAction(HomeLayerButton.of(ShoulderButton.L1), Prefs.SHOULDER_NOTIFICATIONS)
        for ((modifier, expected) in listOf(
            KeyEvent.KEYCODE_HOME to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
            KeyEvent.KEYCODE_BACK to AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
        )) {
            globalActions(service).clear()
            dispatch(service, key(KeyEvent.ACTION_DOWN, modifier))
            assertTrue(dispatch(service, key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L1)))
            assertTrue(dispatch(service, key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_L1)))
            dispatch(service, key(KeyEvent.ACTION_UP, modifier))
            idle()
            assertEquals(listOf(expected), globalActions(service).toList())
        }
    }

    @Test
    fun `a button bound only under Back reaches the app while Home is held`() = withService { service, prefs ->
        prefs.setShoulderAction(ShoulderButton.R1, Prefs.SHOULDER_QUICK_SETTINGS)
        dispatch(service, key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME))
        assertFalse(dispatch(service, key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R1)))
        assertFalse(dispatch(service, key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_R1)))
        dispatch(service, key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME))
    }

    @Test
    fun `Home's gestures run the Home layer and Back's the Back layer`() = withService { service, prefs ->
        prefs.setShoulderAction(HomeLayerButton.of(BackGestureButton.SINGLE), Prefs.SHOULDER_NOTIFICATIONS)
        prefs.setShoulderAction(BackGestureButton.SINGLE, Prefs.SHOULDER_QUICK_SETTINGS)
        // A double tap bound under Back only must not delay Home's single tap.
        prefs.setShoulderAction(BackGestureButton.DOUBLE, Prefs.SHOULDER_STATUS)
        press(service, KeyEvent.KEYCODE_HOME)
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS), globalActions(service).toList())
        press(service, KeyEvent.KEYCODE_BACK)
        idle()
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS),
            globalActions(service).toList())
    }

    @Test
    fun `a Back tap and a Home tap are never one double tap`() = withService { service, prefs ->
        prefs.setShoulderAction(BackGestureButton.SINGLE, Prefs.SHOULDER_QUICK_SETTINGS)
        prefs.setShoulderAction(BackGestureButton.DOUBLE, Prefs.SHOULDER_STATUS)
        prefs.setShoulderAction(HomeLayerButton.of(BackGestureButton.SINGLE), Prefs.SHOULDER_NOTIFICATIONS)
        prefs.setShoulderAction(HomeLayerButton.of(BackGestureButton.DOUBLE), Prefs.SHOULDER_STATUS)
        press(service, KeyEvent.KEYCODE_BACK)
        press(service, KeyEvent.KEYCODE_HOME)
        idle()
        assertEquals(setOf(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS),
            globalActions(service).toSet())
    }

    @Test
    fun `with dual mode off Home is Android's again and its layer rests`() = withService { service, prefs ->
        prefs.setShoulderAction(HomeLayerButton.of(BackGestureButton.SINGLE), Prefs.SHOULDER_NOTIFICATIONS)
        prefs.dualMode = false
        assertFalse(dispatch(service, key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME)))
        assertFalse(dispatch(service, key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME)))
        idle()
        assertTrue(globalActions(service).isEmpty())
        assertEquals(Prefs.SHOULDER_NOTIFICATIONS, prefs.shoulderAction(HomeLayerButton.of(BackGestureButton.SINGLE)))
    }

    @Test
    fun `the helper is told dual mode, keeping its emergency release`() = withService { service, prefs ->
        val helper = ReflectionHelpers.getField<PadHelper>(service, "padHelper")
        ReflectionHelpers.callInstanceMethod<Unit>(service, "updatePadHelper")
        assertEquals(PadHelper.HOME_DUAL, ReflectionHelpers.getField<Int>(helper, "homeMode"))
        prefs.homeAsBack = true
        ReflectionHelpers.callInstanceMethod<Unit>(service, "updatePadHelper")
        assertEquals(PadHelper.HOME_AS_BACK, ReflectionHelpers.getField<Int>(helper, "homeMode"))
        prefs.homeAsBack = false
        ReflectionHelpers.callInstanceMethod<Unit>(service, "updatePadHelper")
        assertEquals(PadHelper.HOME_PLAIN, ReflectionHelpers.getField<Int>(helper, "homeMode"))
    }
}
