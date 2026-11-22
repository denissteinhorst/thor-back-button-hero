package dev.steinhorst.backbuttonhero.input

import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import dev.steinhorst.backbuttonhero.AppControlPrompt
import dev.steinhorst.backbuttonhero.SwitcherOverlay
import dev.steinhorst.backbuttonhero.SwitcherService
import dev.steinhorst.backbuttonhero.core.AppSource
import dev.steinhorst.backbuttonhero.core.RunningApp
import dev.steinhorst.backbuttonhero.overlay.FocusIndicatorOverlay
import dev.steinhorst.backbuttonhero.pad.DpadOverlay
import dev.steinhorst.backbuttonhero.pad.PadHelper
import dev.steinhorst.backbuttonhero.prefs.Prefs
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

@RunWith(RobolectricTestRunner::class)
class HomeAsBackServiceTest {
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
        // Pausing dismisses these; onServiceConnected, which creates them, never runs here.
        ReflectionHelpers.setField(service, "appControlPrompt", AppControlPrompt(service) { false })
        ReflectionHelpers.setField(service, "focusIndicator", FocusIndicatorOverlay(service))
        try {
            check(service, Prefs(service))
        } finally {
            service.onDestroy()
        }
    }

    private fun key(action: Int, code: Int, source: Int = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_KEYBOARD) =
        KeyEvent(100, 180, action, code, 0, 0, 7, 0, 0, source)

    private fun dispatch(service: SwitcherService, event: KeyEvent) = ReflectionHelpers.callInstanceMethod<Boolean>(
        service, "onKeyEvent", ReflectionHelpers.ClassParameter.from(KeyEvent::class.java, event))

    private fun backDown(service: SwitcherService) =
        ReflectionHelpers.callInstanceMethod<BackGestureMachine>(service, "getBackGesture").backDown

    @Test
    fun `with the option the pad's Home is Back and a second key is swallowed`() = withService { service, prefs ->
        prefs.homeAsBack = true
        assertTrue(dispatch(service, key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME)))
        assertTrue(backDown(service))
        assertTrue(dispatch(service, key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
        assertTrue(dispatch(service, key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME)))
        assertFalse(backDown(service))
        assertTrue(dispatch(service, key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
        assertFalse(backDown(service))
    }

    @Test
    fun `without the option, or for AYN's own Home, Home stays Home`() = withService { service, prefs ->
        assertFalse(dispatch(service, key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME)))
        assertFalse(dispatch(service, key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME)))
        prefs.homeAsBack = true
        assertFalse(dispatch(service, key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME, InputDevice.SOURCE_KEYBOARD)))
        assertFalse(backDown(service))
        assertFalse(dispatch(service, key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME, InputDevice.SOURCE_KEYBOARD)))
    }

    @Test
    fun `paused interception leaves Home to Android`() = withService { service, prefs ->
        prefs.homeAsBack = true
        ReflectionHelpers.setField(service, "interceptionEnabled", false)
        assertFalse(dispatch(service, key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME)))
        assertFalse(dispatch(service, key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME)))
        assertEquals(false, backDown(service))
    }

    private fun enabled(service: SwitcherService) = ReflectionHelpers.getField<Boolean>(service, "interceptionEnabled")

    private fun holdToggle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Prefs.DEFAULT_TOGGLE_HOLD_MS))

    private fun globalActions(service: SwitcherService) =
        shadowOf(service as android.accessibilityservice.AccessibilityService).globalActionsPerformed

    @Test
    fun `Back + Home in either order pauses with nothing else, whether or not Home acts as Back`() {
        for (mirror in listOf(false, true)) for (homeFirst in listOf(false, true)) withService { service, prefs ->
            prefs.homeAsBack = mirror
            prefs.toggleButtons = Prefs.TOGGLE_BACK_HOME
            prefs.setShoulderAction(AndroidButton.HOME, Prefs.SHOULDER_HOME)
            val order = if (homeFirst) listOf(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK)
                else listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME)
            order.forEach { assertTrue(dispatch(service, key(KeyEvent.ACTION_DOWN, it))) }
            holdToggle()
            assertFalse(enabled(service))
            assertFalse(backDown(service))
            order.forEach { assertTrue(dispatch(service, key(KeyEvent.ACTION_UP, it))) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertFalse(backDown(service))
            assertTrue(globalActions(service).isEmpty())
        }
    }

    @Test
    fun `paused, Back + Home in either order resumes and nothing reaches Android`() {
        for (mirror in listOf(false, true)) for (homeFirst in listOf(false, true)) withService { service, prefs ->
            prefs.homeAsBack = mirror
            prefs.toggleButtons = Prefs.TOGGLE_BACK_HOME
            ReflectionHelpers.setField(service, "interceptionEnabled", false)
            val order = if (homeFirst) listOf(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK)
                else listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME)
            order.forEach { assertTrue(dispatch(service, key(KeyEvent.ACTION_DOWN, it))) }
            holdToggle()
            assertTrue(enabled(service))
            order.forEach { assertTrue(dispatch(service, key(KeyEvent.ACTION_UP, it))) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertFalse(backDown(service))
            assertTrue(globalActions(service).isEmpty())
        }
    }

    @Test
    fun `a held-back press that stays alone still reaches Android once`() = withService { service, prefs ->
        prefs.toggleButtons = Prefs.TOGGLE_BACK_HOME
        // Active, a lone pad Home waits for its release, then goes home.
        assertTrue(dispatch(service, key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME)))
        assertTrue(globalActions(service).isEmpty())
        assertTrue(dispatch(service, key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME)))
        assertEquals(listOf(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME), globalActions(service))
        // Paused, a lone Back does the same with Back.
        ReflectionHelpers.setField(service, "interceptionEnabled", false)
        assertTrue(dispatch(service, key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
        assertTrue(dispatch(service, key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
        val deadline = System.currentTimeMillis() + 5000
        while (globalActions(service).size < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK, globalActions(service).last())
    }
}
