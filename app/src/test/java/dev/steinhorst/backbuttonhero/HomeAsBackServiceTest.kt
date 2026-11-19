package dev.steinhorst.backbuttonhero

import android.view.InputDevice
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
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
}
