package dev.steinhorst.backbuttonhero

import android.accessibilityservice.AccessibilityService
import android.net.LocalSocket
import android.os.Looper
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import dev.steinhorst.backbuttonhero.actions.AppControlPrompt
import dev.steinhorst.backbuttonhero.core.AppSource
import dev.steinhorst.backbuttonhero.core.RunningApp
import dev.steinhorst.backbuttonhero.input.BackGestureButton
import dev.steinhorst.backbuttonhero.input.DpadDirection
import dev.steinhorst.backbuttonhero.input.FaceButton
import dev.steinhorst.backbuttonhero.input.ShoulderButton
import dev.steinhorst.backbuttonhero.input.StickClickButton
import dev.steinhorst.backbuttonhero.overlay.FocusIndicatorOverlay
import dev.steinhorst.backbuttonhero.pad.DpadOverlay
import dev.steinhorst.backbuttonhero.pad.PadHelper
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.recents.SwitcherOverlay
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * The "Press button" actions through the real onKeyEvent: the button is held exactly as long as the
 * hotkey's trigger, every exit releases it, and the pressed button coming back through the pad clone
 * reaches the game instead of starting a Back combo of its own.
 */
@RunWith(RobolectricTestRunner::class)
class ServicePadPressTest {
    private val wire = ByteArrayOutputStream()

    private fun withService(helper: Boolean = true, check: (SwitcherService, Prefs) -> Unit) {
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        val source = object : AppSource {
            override fun appsOnDisplay(displayId: Int) = emptyList<RunningApp>()
            override fun forget(packageName: String) = Unit
        }
        ReflectionHelpers.setField(service, "overlay", SwitcherOverlay(service, source, { true }, {}, { _, _ -> }, {}, { _, _ -> }, {}, {}))
        val pad = PadHelper(service, {}, {}, {})
        ReflectionHelpers.setField(pad, "closed", true)
        if (helper) {
            ReflectionHelpers.setField(pad, "active", true)
            ReflectionHelpers.setField(pad, "client", object : LocalSocket() {
                override fun getOutputStream(): OutputStream = wire
            })
        }
        ReflectionHelpers.setField(service, "padHelper", pad)
        ReflectionHelpers.setField(service, "dpadOverlay", DpadOverlay(service, {}, {}))
        ReflectionHelpers.setField(service, "appControlPrompt", AppControlPrompt(service) { false })
        ReflectionHelpers.setField(service, "focusIndicator", FocusIndicatorOverlay(service))
        val prefs = Prefs(service)
        prefs.doubleTapMs = 300
        try {
            check(service, prefs)
        } finally {
            service.onDestroy()
        }
    }

    /** The helper lines written since the last call. */
    private fun sent(): List<String> = wire.toString(Charsets.UTF_8.name()).lines().filter { it.isNotEmpty() }.also { wire.reset() }

    private fun key(service: SwitcherService, action: Int, code: Int, device: Int = 7, source: Int = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_KEYBOARD) =
        ReflectionHelpers.callInstanceMethod<Boolean>(service, "onKeyEvent",
            ClassParameter.from(KeyEvent::class.java, KeyEvent(100, 180, action, code, 0, 0, device, 0, 0, source)))

    private fun down(service: SwitcherService, code: Int, device: Int = 7) = key(service, KeyEvent.ACTION_DOWN, code, device)
    private fun up(service: SwitcherService, code: Int, device: Int = 7) = key(service, KeyEvent.ACTION_UP, code, device)

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private val l1 = KeyEvent.KEYCODE_BUTTON_L1
    private val back = KeyEvent.KEYCODE_BACK

    @Test
    fun `Back plus L1 holds A while L1 is held, across both mirrored devices, and never leaks L1`() = withService { service, prefs ->
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_PAD_A)
        assertTrue(down(service, back))
        assertTrue(down(service, l1, 7))
        assertTrue(down(service, l1, 8))
        assertEquals(listOf("PRESS K 304 1"), sent())
        assertTrue(up(service, back))
        assertTrue(up(service, l1, 7))
        assertEquals(emptyList<String>(), sent())
        assertTrue(up(service, l1, 8))
        assertEquals(listOf("PRESS K 304 0"), sent())
        idle(2000)
        assertEquals(emptyList<String>(), sent())
    }

    @Test
    fun `the pressed button comes back to the game, not as the Back combo it is bound to`() = withService { service, prefs ->
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_PAD_A)
        prefs.setShoulderAction(FaceButton.A, Prefs.SHOULDER_QUICK_SETTINGS)
        val a = KeyEvent.KEYCODE_BUTTON_A
        down(service, back)
        down(service, l1)
        // The clone's A, with Back still held: not consumed, so it reaches the game.
        assertFalse(down(service, a, 9))
        assertFalse(down(service, a, 9))
        up(service, l1)
        assertFalse(up(service, a, 9))
        up(service, back)
        idle(2000)
        assertEquals(emptyList<Int>(), shadowOf(service as AccessibilityService).globalActionsPerformed.toList())
        // The guard ended with that release: a real Back + A is the combo again.
        down(service, back)
        assertTrue(down(service, a))
        assertTrue(up(service, a))
        up(service, back)
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS), shadowOf(service as AccessibilityService).globalActionsPerformed.toList())
    }

    @Test
    fun `the printed label follows the controller style, and so does the returning key`() = withService { service, prefs ->
        Settings.System.putInt(service.contentResolver, "flip_button_layout", 1)
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_PAD_A)
        prefs.setShoulderAction(FaceButton.A, Prefs.SHOULDER_QUICK_SETTINGS)
        down(service, back)
        down(service, l1)
        assertEquals(listOf("PRESS K 305 1"), sent())
        // Printed A is BUTTON_B in this style.
        assertFalse(down(service, KeyEvent.KEYCODE_BUTTON_B, 9))
        up(service, l1)
        up(service, back)
        assertEquals(listOf("PRESS K 305 0"), sent())
    }

    @Test
    fun `a D-pad direction holds a shoulder until its axis is released, a trigger also moves its axis`() = withService { service, prefs ->
        prefs.setShoulderAction(DpadDirection.UP, Prefs.SHOULDER_PAD_R2)
        down(service, back)
        ReflectionHelpers.callInstanceMethod<Unit>(service, "handleDpad", ClassParameter.from(DpadDirection::class.java, DpadDirection.UP))
        assertEquals(listOf("PRESS K 313 1", "PRESS A 9 1000"), sent())
        up(service, back)
        ReflectionHelpers.callInstanceMethod<Unit>(service, "handleDpadRelease", ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        assertEquals(listOf("PRESS K 313 0", "PRESS A 9 0"), sent())
    }

    @Test
    fun `a stick click without other gestures holds Start from its press to its release`() = withService { service, prefs ->
        prefs.setShoulderAction(StickClickButton.LEFT_PRESS, Prefs.SHOULDER_PAD_START)
        down(service, back)
        assertTrue(down(service, KeyEvent.KEYCODE_BUTTON_THUMBL))
        assertEquals(listOf("PRESS K 315 1"), sent())
        assertTrue(up(service, KeyEvent.KEYCODE_BUTTON_THUMBL))
        assertEquals(listOf("PRESS K 315 0"), sent())
        up(service, back)
    }

    @Test
    fun `Back's own gesture taps the button`() = withService { service, prefs ->
        prefs.setShoulderAction(BackGestureButton.SINGLE, Prefs.SHOULDER_PAD_UP)
        down(service, back)
        up(service, back)
        idle(1000)
        assertEquals(listOf("PRESS A 17 -1000", "PRESS A 17 0"), sent())
    }

    @Test
    fun `forgotten keys, a lost helper and the service going away release everything`() = withService { service, prefs ->
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_PAD_A)
        down(service, back); down(service, l1)
        sent()
        // Standby and pausing forget every held key.
        ReflectionHelpers.callInstanceMethod<Unit>(service, "forgetHeldKeys")
        assertEquals(listOf("UNPRESS"), sent())
        up(service, l1); up(service, back)
        assertEquals(emptyList<String>(), sent())

        down(service, back); down(service, l1)
        sent()
        // The clone is gone with the helper; nothing is left to release afterwards.
        ReflectionHelpers.callInstanceMethod<Unit>(service, "onPadHelperActiveChanged", ClassParameter.from(Boolean::class.javaPrimitiveType, false))
        up(service, l1); up(service, back)
        assertEquals(emptyList<String>(), sent())

        down(service, back); down(service, l1)
        sent()
        ReflectionHelpers.callInstanceMethod<Unit>(service, "releaseSession")
        assertEquals(listOf("UNPRESS"), sent())
    }

    @Test
    fun `without the helper the press is unavailable`() = withService(helper = false) { service, prefs ->
        prefs.setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_PAD_A)
        ShadowLog.clear()
        down(service, back)
        assertTrue(down(service, l1))
        assertTrue(up(service, l1))
        up(service, back)
        idle(100)
        val notices = ShadowLog.getLogsForTag("BackButtonHero").map { it.msg }.filter { it.startsWith("Notice: ") }
        assertEquals(listOf("Notice: " + service.getString(R.string.shoulder_action_unavailable)), notices)
    }

    @Test
    fun `any hotkey pressing a button keeps the helper wanted, the Home layer only in dual mode`() = withService { _, prefs ->
        assertFalse(prefs.hasPadPressAction())
        // Turning dual mode on seeds the Home layer, so the binding goes in afterwards.
        prefs.dualMode = true
        prefs.setShoulderAction(dev.steinhorst.backbuttonhero.input.HomeLayerButton.of(ShoulderButton.R1), Prefs.SHOULDER_PAD_B)
        assertTrue(prefs.hasPadPressAction())
        prefs.dualMode = false
        assertFalse(prefs.hasPadPressAction())
        prefs.setShoulderAction(BackGestureButton.HOLD, Prefs.SHOULDER_PAD_HOME)
        assertTrue(prefs.hasPadPressAction())
    }
}
