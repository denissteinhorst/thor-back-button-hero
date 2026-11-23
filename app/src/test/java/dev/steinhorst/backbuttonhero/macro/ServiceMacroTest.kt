package dev.steinhorst.backbuttonhero.macro

import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.SwitcherService
import dev.steinhorst.backbuttonhero.actions.AppControlPrompt
import dev.steinhorst.backbuttonhero.core.AppSource
import dev.steinhorst.backbuttonhero.core.RunningApp
import dev.steinhorst.backbuttonhero.input.ComboButton
import dev.steinhorst.backbuttonhero.input.ShoulderButton
import dev.steinhorst.backbuttonhero.overlay.FocusIndicatorOverlay
import dev.steinhorst.backbuttonhero.pad.DpadOverlay
import dev.steinhorst.backbuttonhero.pad.PadHelper
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.recents.SwitcherOverlay
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers

/**
 * Macro play, stop and the one-press cancel through the service's key path. Playing itself needs the
 * root helper's socket, so a running macro is set up directly; everything observed is what the user
 * gets from a hotkey press: the macro started again, nothing, or the "helper missing" message.
 */
@RunWith(RobolectricTestRunner::class)
class ServiceMacroTest {
    private val l1 = ShoulderButton.L1
    private val r1 = ShoulderButton.R1

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
            val macro = Macro(listOf(MacroStep(listOf(MacroInput.Dpad(MacroDirection.UP)), 100)))
            val saved = prefs.saveMacro(null, "m", macro)
            for (button in listOf(l1, r1)) {
                prefs.setShoulderAction(button, Prefs.SHOULDER_MACRO)
                prefs.setMacroRef(button, saved.id)
            }
            check(service, prefs)
        } finally {
            service.onDestroy()
        }
    }

    private fun key(action: Int, code: Int) =
        KeyEvent(100, 180, action, code, 0, 0, 7, 0, 0, InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_KEYBOARD)

    private fun dispatch(service: SwitcherService, action: Int, code: Int) = ReflectionHelpers.callInstanceMethod<Boolean>(
        service, "onKeyEvent", ReflectionHelpers.ClassParameter.from(KeyEvent::class.java, key(action, code)))

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

    private fun missing(service: SwitcherService) = service.getString(R.string.macro_helper_missing)

    /** Back + [button]; returns the notices it produced. */
    private fun hotkey(service: SwitcherService, button: ShoulderButton = l1): List<String> {
        ShadowLog.clear()
        dispatch(service, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        dispatch(service, KeyEvent.ACTION_DOWN, button.keyCode)
        dispatch(service, KeyEvent.ACTION_UP, button.keyCode)
        dispatch(service, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
        idle()
        return ShadowLog.getLogsForTag("BackButtonHero").map { it.msg }.filter { it.startsWith("Notice: ") }.map { it.removePrefix("Notice: ") }
    }

    private fun backTap(service: SwitcherService) {
        dispatch(service, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        dispatch(service, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
        idle()
    }

    /** The helper accepted [button]'s macro under [id], as playMacro would have reported. */
    private fun playing(service: SwitcherService, button: ComboButton, id: Int) {
        ReflectionHelpers.setField(service, "playingMacro", button)
        ReflectionHelpers.setField(service, "playingMacroId", id)
    }

    private fun end(service: SwitcherService, id: Int, stopped: Boolean) = ReflectionHelpers.callInstanceMethod<Unit>(service, "onMacroEnd",
        ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, id), ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, stopped))

    private fun padLost(service: SwitcherService) = ReflectionHelpers.callInstanceMethod<Unit>(service, "onPadHelperActiveChanged",
        ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false))

    @Test
    fun `without the helper a macro hotkey only explains`() = withService { service, _ ->
        assertEquals(listOf(missing(service)), hotkey(service))
    }

    @Test
    fun `Back stops a playing macro and its own hotkey in that press only cancels, once`() = withService { service, _ ->
        playing(service, l1, 5)
        assertEquals("the press that stopped it", emptyList<String>(), hotkey(service))
        assertEquals("the next press plays again", listOf(missing(service)), hotkey(service))
    }

    @Test
    fun `the hotkey pressed again in the stopping press plays`() = withService { service, _ ->
        playing(service, l1, 5)
        ShadowLog.clear()
        dispatch(service, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)
        repeat(2) {
            dispatch(service, KeyEvent.ACTION_DOWN, l1.keyCode)
            dispatch(service, KeyEvent.ACTION_UP, l1.keyCode)
        }
        dispatch(service, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)
        idle()
        assertEquals(listOf("Notice: " + missing(service)), ShadowLog.getLogsForTag("BackButtonHero").map { it.msg }.filter { it.startsWith("Notice: ") })
    }

    @Test
    fun `another hotkey in the stopping press is not cancelled`() = withService { service, _ ->
        playing(service, l1, 5)
        assertEquals(listOf(missing(service)), hotkey(service, r1))
        assertEquals("L1's cancel is not claimed by a later press", listOf(missing(service)), hotkey(service))
    }

    @Test
    fun `a stop the helper reports before Back arrives still counts for that Back press`() = withService { service, _ ->
        playing(service, l1, 5)
        end(service, 5, stopped = true)
        assertEquals(emptyList<String>(), hotkey(service))
        assertEquals(listOf(missing(service)), hotkey(service))
    }

    @Test
    fun `a cancel left unused expires with the next Back press`() = withService { service, _ ->
        playing(service, l1, 5)
        end(service, 5, stopped = true)
        backTap(service)
        backTap(service)
        assertEquals(listOf(missing(service)), hotkey(service))
    }

    @Test
    fun `a macro that ended by itself, a stale id or a lost pad leaves nothing to cancel`() = withService { service, _ ->
        playing(service, l1, 5)
        end(service, 5, stopped = false)
        assertEquals(listOf(missing(service)), hotkey(service))
        playing(service, l1, 6)
        end(service, 5, stopped = true)
        padLost(service)
        assertEquals(listOf(missing(service)), hotkey(service))
        playing(service, l1, 7)
        end(service, 7, stopped = true)
        padLost(service)
        assertEquals(listOf(missing(service)), hotkey(service))
    }
}
