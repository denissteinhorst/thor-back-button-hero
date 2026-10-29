package dev.steinhorst.backbuttonhero

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
class TopRecentsBackTest {
    private fun checkClose(device: Int) {
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        val source = object : AppSource {
            override fun appsOnDisplay(displayId: Int) = emptyList<RunningApp>()
            override fun forget(packageName: String) = Unit
        }
        val overlay = SwitcherOverlay(service, source, { true }, {}, { _, _ -> }, {}, { _, _ -> }, {}, {})
        ReflectionHelpers.setField(service, "overlay", overlay)
        ReflectionHelpers.setField(service, "topRecentsControllerActive", true)
        fun event(action: Int, id: Int) = KeyEvent(100, 180, action, KeyEvent.KEYCODE_BACK, 0, 0, id, 158)
        fun dispatch(event: KeyEvent) = ReflectionHelpers.callInstanceMethod<Boolean>(service, "onKeyEvent",
            ReflectionHelpers.ClassParameter.from(KeyEvent::class.java, event))
        try {
            assertTrue(dispatch(event(KeyEvent.ACTION_DOWN, device)))
            assertFalse(ReflectionHelpers.getField(service, "topRecentsExitRequested"))
            assertTrue(ReflectionHelpers.getField(service, "switcherBackDown"))
            assertFalse(dispatch(event(KeyEvent.ACTION_UP, device + 1)))
            assertTrue(ReflectionHelpers.getField(service, "switcherBackDown"))
            assertTrue(dispatch(event(KeyEvent.ACTION_UP, device)))
            assertTrue(ReflectionHelpers.getField(service, "topRecentsExitRequested"))
            assertFalse(ReflectionHelpers.getField(service, "switcherBackDown"))
            assertFalse(dispatch(event(KeyEvent.ACTION_DOWN, -1)))
            assertFalse(dispatch(event(KeyEvent.ACTION_UP, -1)))
        } finally {
            service.onDestroy()
        }
    }

    @Test
    fun `physical close waits for matching release before injecting native Back`() = checkClose(42)

    @Test
    fun `virtual close consumes its own release before forwarding the injected Back pair`() = checkClose(-1)
}
