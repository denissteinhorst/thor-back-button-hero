package dev.steinhorst.backbuttonhero

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.actions.AppControlPrompt
import dev.steinhorst.backbuttonhero.core.AppSource
import dev.steinhorst.backbuttonhero.core.RunningApp
import dev.steinhorst.backbuttonhero.input.FacePosition
import dev.steinhorst.backbuttonhero.overlay.FaceButtonPictogram
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.recents.SwitcherOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers

/**
 * Confirm and cancel follow Android's convention in both controller styles: BUTTON_A confirms and
 * BUTTON_B cancels in every prompt and switcher, and only the pictograms move with the style.
 */
@RunWith(RobolectricTestRunner::class)
class ControllerConfirmTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val display: Display
        get() = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)

    private fun key(action: Int, code: Int, device: Int = 7) = KeyEvent(100, 120, action, code, 0, 0, device, 0)

    private fun pictograms(view: View): List<FaceButtonPictogram> = when (view) {
        is FaceButtonPictogram -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { pictograms(view.getChildAt(it)) }
        else -> emptyList()
    }

    private class OpenPrompt(val prompt: AppControlPrompt, val view: View, val chosen: MutableList<String>)

    private fun prompt(flipped: Boolean): OpenPrompt {
        val chosen = mutableListOf<String>()
        val prompt = AppControlPrompt(context) { flipped }
        prompt.show(display, "Close which app?", listOf(AppControlPrompt.Option("top", "Top"), AppControlPrompt.Option("bottom", "Bottom"))) {
            chosen += it
        }
        val view = ReflectionHelpers.getField<Pair<WindowManager, View>?>(prompt, "window")!!.second
        return OpenPrompt(prompt, view, chosen)
    }

    private fun promptKeys(flipped: Boolean) {
        // Neither X nor Y nor a release decides anything; the prompt stays open.
        prompt(flipped).run {
            for (code in listOf(KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y)) {
                assertTrue(view.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, code)))
            }
            assertTrue(view.dispatchKeyEvent(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)))
            assertTrue(prompt.showing)
            assertEquals(emptyList<String>(), chosen)
            // Confirm runs the highlighted (first) choice.
            assertTrue(view.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)))
            assertFalse(prompt.showing)
            assertEquals(listOf("top"), chosen)
        }
        prompt(flipped).run {
            assertTrue(view.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B)))
            assertFalse(prompt.showing)
            assertEquals(emptyList<String>(), chosen)
        }
        prompt(flipped).run {
            assertTrue(view.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)))
            assertTrue(view.dispatchKeyEvent(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)))
            assertEquals(listOf("bottom"), chosen)
        }
    }

    @Test
    fun `prompt confirms with A and cancels with B in Standard style`() = promptKeys(flipped = false)

    @Test
    fun `prompt confirms with A and cancels with B in Xbox style`() = promptKeys(flipped = true)

    @Test
    fun `prompt pictograms fill the physical confirm and cancel buttons of the style`() {
        prompt(flipped = false).run {
            assertEquals(listOf(FacePosition.RIGHT, FacePosition.BOTTOM), pictograms(view).map { it.filled })
            assertEquals(listOf("Right button", "Bottom button"), pictograms(view).map { it.contentDescription })
            prompt.dismiss()
        }
        prompt(flipped = true).run {
            assertEquals(listOf(FacePosition.BOTTOM, FacePosition.RIGHT), pictograms(view).map { it.filled })
            assertEquals(listOf("Bottom button", "Right button"), pictograms(view).map { it.contentDescription })
            prompt.dismiss()
        }
    }

    @Test
    fun `pictogram draws at any size and takes a new colour`() {
        val pictogram = FaceButtonPictogram(context, FacePosition.TOP, Color.BLACK)
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        pictogram.layout(0, 0, 0, 0)
        pictogram.draw(android.graphics.Canvas(bitmap))
        pictogram.layout(0, 0, 24, 24)
        pictogram.color = Color.WHITE
        pictogram.draw(android.graphics.Canvas(bitmap))
        assertEquals(Color.WHITE, pictogram.color)
        assertEquals("Top button", pictogram.contentDescription)
        assertEquals("Left button", FaceButtonPictogram(context, FacePosition.LEFT, Color.BLACK).contentDescription)
        assertEquals(FacePosition.RIGHT, FaceButtonPictogram(context).filled)
    }

    private fun app(pkg: String) = RunningApp(pkg, pkg, ColorDrawable(Color.BLUE), Bitmap.createBitmap(40, 24, Bitmap.Config.ARGB_8888))

    /** The bottom switcher with the style switch at [flipped], driven through the service's key path. */
    private fun switcherKeys(flipped: Boolean) {
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        Settings.System.putInt(service.contentResolver, SwitcherService.CONTROLLER_FLIP_SETTING, if (flipped) 1 else 0)
        Prefs(service).switcherControllerEnabled = true
        val launched = mutableListOf<String>()
        val apps = listOf(app("com.example.mail"), app("com.example.game"))
        val source = object : AppSource {
            override fun appsOnDisplay(displayId: Int) = apps
            override fun forget(packageName: String) = Unit
        }
        val overlay = SwitcherOverlay(service, source, { false }, {}, { _, _ -> }, {}, { pkg, _ -> launched += pkg }, {}, {})
        ReflectionHelpers.setField(service, "overlay", overlay)
        fun dispatch(event: KeyEvent) = ReflectionHelpers.callInstanceMethod<Boolean>(service, "onKeyEvent",
            ReflectionHelpers.ClassParameter.from(KeyEvent::class.java, event))
        try {
            overlay.toggle(display)
            ShadowLooper.idleMainLooper()
            assertFalse("Y is not a switcher key", dispatch(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_Y)))
            dispatch(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_Y))
            assertTrue(overlay.isShowing)

            assertTrue(dispatch(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B)))
            assertFalse("cancel closes the switcher", overlay.isShowing)
            assertTrue("the release of cancel is swallowed too", dispatch(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B)))
            assertEquals(emptyList<String>(), launched)

            overlay.toggle(display)
            ShadowLooper.idleMainLooper()
            assertTrue(dispatch(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)))
            assertFalse(overlay.isShowing)
            assertEquals("confirm opens the newest app, selected on open", listOf("com.example.mail"), launched)
            assertTrue(dispatch(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)))
            // Nothing stays held: the next confirm, with the switcher closed, is not ours.
            assertFalse(dispatch(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)))
            assertFalse(dispatch(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)))
        } finally {
            service.onDestroy()
        }
    }

    @Test
    fun `bottom switcher confirms with A and cancels with B in Standard style`() = switcherKeys(flipped = false)

    @Test
    fun `bottom switcher confirms with A and cancels with B in Xbox style`() = switcherKeys(flipped = true)

    @Test
    fun `cancel closes native top Recents and swallows its release`() {
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        Prefs(service).switcherControllerEnabled = true
        val empty = object : AppSource {
            override fun appsOnDisplay(displayId: Int) = emptyList<RunningApp>()
            override fun forget(packageName: String) = Unit
        }
        ReflectionHelpers.setField(service, "overlay", SwitcherOverlay(service, empty, { true }, {}, { _, _ -> }, {}, { _, _ -> }, {}, {}))
        val session = ReflectionHelpers.callInstanceMethod<Any>(service, "getTopSession")
        ReflectionHelpers.setField(session, "active", true)
        fun dispatch(event: KeyEvent) = ReflectionHelpers.callInstanceMethod<Boolean>(service, "onKeyEvent",
            ReflectionHelpers.ClassParameter.from(KeyEvent::class.java, event))
        try {
            // Confirm without an identified card stays Quickstep's own.
            assertFalse(dispatch(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A)))
            assertFalse(dispatch(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A)))
            assertFalse(ReflectionHelpers.getField(session, "exitRequested"))
            // Like Back, cancel closes on its release: Quickstep ignores a Back injected while a pad key is held.
            assertTrue(dispatch(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B)))
            assertTrue(ReflectionHelpers.getField(session, "backClosePending"))
            assertFalse(ReflectionHelpers.getField(session, "exitRequested"))
            assertTrue(dispatch(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B)))
            assertFalse(ReflectionHelpers.getField(session, "backClosePending"))
            assertTrue(ReflectionHelpers.getField(session, "exitRequested"))
        } finally {
            service.onDestroy()
        }
    }
}
