package dev.steinhorst.backbuttonhero.actions

import android.content.Context
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.support.FakePService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** AYN's Auto-lock as a stored target, and a toggle out of it, which first reads the driven screen. */
@RunWith(RobolectricTestRunner::class)
class ControllerFocusAutoTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var fake: FakePService
    private var inputReads = 0

    @Before
    fun install() {
        fake = FakePService().install()
    }

    @After
    fun uninstall() = fake.uninstall()

    private fun routeIs(value: String?) {
        Settings.System.putString(context.contentResolver, ControllerFocus.FOCUS_SETTING, value)
    }

    private fun focus(driven: String) = ControllerFocus(context, {}, {}) {
        inputReads++
        driven
    }

    private fun command(value: String) =
        "code=0 args=[0] setprop persist.sys.input.dispaly $value && settings put system screen_focus_lock $value && input keyevent 60 && echo OK"

    private fun awaitCommands(count: Int) {
        val end = System.currentTimeMillis() + 5000
        while (fake.commands.size < count) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) fail("expected $count commands, got ${fake.commands}")
            Thread.sleep(10)
        }
        Thread.sleep(50)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `the stored auto target resolves to AYN's auto-lock value`() {
        assertEquals(ControllerFocus.AUTO_VALUE, ControllerFocus.resolveValue(Prefs.FOCUS_AUTO, "1"))
        assertEquals(Prefs.FOCUS_AUTO, ControllerFocus.AUTO_TARGET)
        val focus = focus(Prefs.DISPLAY_TOP)
        assertTrue(focus.targetIsAuto(Prefs.FOCUS_AUTO))
        assertFalse(focus.targetIsAuto(Prefs.FOCUS_TOGGLE))
        assertFalse(focus.targetIsTop(Prefs.FOCUS_AUTO))
    }

    @Test
    fun `setting auto runs AYN's sequence with value 0`() {
        routeIs(ControllerFocus.TOP_VALUE)
        val focus = focus(Prefs.DISPLAY_TOP)
        assertFalse(focus.needsInputScreen(Prefs.FOCUS_AUTO))
        var ok: Boolean? = null
        focus.run(Prefs.FOCUS_AUTO) { ok = it }
        awaitCommands(1)
        assertEquals(listOf(command("0")), fake.commands)
        assertEquals(true, ok)
        assertEquals(0, inputReads)
    }

    @Test
    fun `toggle between fixed screens needs no read`() {
        routeIs(ControllerFocus.TOP_VALUE)
        val focus = focus(Prefs.DISPLAY_BOTTOM)
        assertFalse(focus.needsInputScreen(Prefs.FOCUS_TOGGLE))
        val resolved = mutableListOf<String>()
        focus.run(Prefs.FOCUS_TOGGLE, onResolved = { resolved += it })
        awaitCommands(1)
        assertEquals(listOf(command("2")), fake.commands)
        assertEquals(emptyList<String>(), resolved)
        assertEquals(0, inputReads)
    }

    @Test
    fun `toggle out of auto-lock locks the screen that is not driven`() {
        for ((driven, locked, value) in listOf(
            Triple(Prefs.DISPLAY_BOTTOM, Prefs.DISPLAY_TOP, ControllerFocus.TOP_VALUE),
            Triple(Prefs.DISPLAY_TOP, Prefs.DISPLAY_BOTTOM, ControllerFocus.BOTTOM_VALUE),
        )) {
            for (route in listOf(ControllerFocus.AUTO_VALUE, null)) {
                routeIs(route)
                val before = fake.commands.size
                val focus = focus(driven)
                assertTrue(focus.needsInputScreen(Prefs.FOCUS_TOGGLE))
                val resolved = mutableListOf<String>()
                var ok: Boolean? = null
                focus.run(Prefs.FOCUS_TOGGLE, onResolved = { resolved += it }) { ok = it }
                awaitCommands(before + 1)
                assertEquals(listOf(command(value)), fake.commands.drop(before))
                assertEquals(listOf(locked), resolved)
                assertEquals(true, ok)
            }
        }
    }

    @Test
    fun `a second toggle while the driven screen is read flips from the screen the first one locks`() {
        routeIs(ControllerFocus.AUTO_VALUE)
        val focus = focus(Prefs.DISPLAY_BOTTOM)
        val resolved = mutableListOf<String>()
        focus.run(Prefs.FOCUS_TOGGLE, onResolved = { resolved += it })
        // Still reading: the indicator must wait for this press too.
        assertTrue(focus.needsInputScreen(Prefs.DISPLAY_TOP))
        focus.run(Prefs.FOCUS_TOGGLE, onResolved = { resolved += it })
        awaitCommands(2)
        assertEquals(listOf(command("1"), command("2")), fake.commands)
        assertEquals(listOf(Prefs.DISPLAY_TOP), resolved)
        assertEquals(1, inputReads)
    }

    @Test
    fun `without root a toggle out of auto-lock reads nothing and fails`() {
        fake.uninstall()
        Settings.System.putString(context.contentResolver, ControllerFocus.FOCUS_SETTING, ControllerFocus.AUTO_VALUE)
        var failed = 0
        var ok: Boolean? = null
        val focus = ControllerFocus(context, {}, { failed++ }) { inputReads++; Prefs.DISPLAY_TOP }
        focus.run(Prefs.FOCUS_TOGGLE) { ok = it }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, failed)
        assertEquals(false, ok)
        assertEquals(0, inputReads)
    }
}
