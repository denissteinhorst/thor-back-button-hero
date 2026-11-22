package dev.steinhorst.backbuttonhero

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.steinhorst.backbuttonhero.actions.AppControlPrompt
import dev.steinhorst.backbuttonhero.actions.AutoControllerFocus
import dev.steinhorst.backbuttonhero.capture.RecordingIndicatorOverlay
import dev.steinhorst.backbuttonhero.overlay.FocusIndicatorOverlay
import dev.steinhorst.backbuttonhero.overlay.NoticeOverlay
import dev.steinhorst.backbuttonhero.pad.DpadOverlay
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.support.Golden
import dev.steinhorst.backbuttonhero.support.OverlayTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Freezes the overlays the service adds outside any activity. They are captured from the window
 * manager exactly as added, right after the call (before any fade runs), so window parameters,
 * content and styling are all part of the snapshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-xxhdpi")
class OverlaySnapshotTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val positions = listOf(
        Prefs.FOCUS_POS_TOP_LEFT, Prefs.FOCUS_POS_TOP_CENTER, Prefs.FOCUS_POS_TOP_RIGHT,
        Prefs.FOCUS_POS_CENTER_LEFT, Prefs.FOCUS_POS_CENTER, Prefs.FOCUS_POS_CENTER_RIGHT,
        Prefs.FOCUS_POS_BOTTOM_LEFT, Prefs.FOCUS_POS_BOTTOM_CENTER, Prefs.FOCUS_POS_BOTTOM_RIGHT,
    )

    /** Runs [block] and serializes exactly the windows it added. */
    private fun added(label: String, block: () -> Unit): String {
        val before = OverlayTree.windows().size
        block()
        val now = OverlayTree.windows()
        return "## $label\n" + OverlayTree.describe(now.drop(before))
    }

    @Test
    fun `notice overlay`() {
        val notice = NoticeOverlay(context)
        val out = added("short") { notice.show("Screenshot saved") } +
            added("again while shown (reuses the window)") { notice.show("Second notice") }
        notice.dismiss()
        assertEquals("dismiss removes the window", 0, OverlayTree.windows().size)
        Golden.verify("overlay_notice", out + added("two lines, long") { notice.show("First line\nSecond line", long = true) })
        notice.dismiss()
    }

    @Test
    fun `recording indicator`() {
        val out = StringBuilder()
        val indicator = RecordingIndicatorOverlay(context)
        for (position in positions) {
            out.append(added(position) { indicator.show(position) })
            indicator.dismiss()
        }
        out.append(added("talking") { indicator.show(Prefs.FOCUS_POS_TOP_RIGHT); indicator.setTalking(true) })
        indicator.dismiss()
        out.append(added("preview") { indicator.preview(Prefs.FOCUS_POS_BOTTOM_LEFT) })
        indicator.dismiss()
        assertEquals("dismiss removes the window", 0, OverlayTree.windows().size)
        Golden.verify("overlay_recording", out.toString())
    }

    @Test
    fun `controller-focus pictogram`() {
        val out = StringBuilder()
        val focus = FocusIndicatorOverlay(context)
        for (top in listOf(true, false)) {
            for (position in positions) {
                out.append(added("top=$top $position") { focus.show(top, position) })
                focus.dismiss()
            }
        }
        out.append(added("tappable") { focus.show(true, Prefs.FOCUS_POS_CENTER_RIGHT, onTap = {}) })
        focus.dismiss()
        out.append(added("persistent") { focus.showPersistent(false, Prefs.FOCUS_POS_CENTER_RIGHT) })
        focus.dismiss()
        out.append(added("no position adds nothing") { focus.show(true, null) })
        assertEquals("dismiss removes the window", 0, OverlayTree.windows().size)
        Golden.verify("overlay_focus", out.toString())
    }

    private fun appControl(golden: String) {
        val prompt = AppControlPrompt(context) { it == KeyEvent.KEYCODE_BUTTON_A }
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val options = listOf(
            AppControlPrompt.Option("top", "Top screen"),
            AppControlPrompt.Option("bottom", "Bottom screen"),
            AppControlPrompt.Option("both", "Both screens"),
        )
        val out = added("three options") { prompt.show(display, "Close which app?", options) {} }
        assertTrue(prompt.showing)
        prompt.dismiss()
        assertEquals("dismiss removes the window", 0, OverlayTree.windows().size)
        Golden.verify(golden, out)
    }

    @Test
    fun `app-control prompt`() = appControl("overlay_app_control_en_light")

    @Test
    @Config(qualifiers = "en-night-xxhdpi")
    fun `app-control prompt, dark`() = appControl("overlay_app_control_en_dark")

    @Test
    @Config(qualifiers = "de-xxhdpi")
    fun `app-control prompt, German`() = appControl("overlay_app_control_de_light")

    @Test
    fun `auto controller-focus window`() {
        val auto = AutoControllerFocus(context)
        val out = added("default display") { auto.run(Display.DEFAULT_DISPLAY) {} }
        auto.dismiss()
        Golden.verify("overlay_auto_focus", out)
    }

    @Test
    fun `D-pad focus window`() {
        val dpad = DpadOverlay(context, {}, {})
        val out = added("attached") { dpad.attach() }
        assertTrue(dpad.attached)
        dpad.detach()
        // removeView (unlike removeViewImmediate) completes on the next main-looper pass.
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals("detach removes every window", 0, OverlayTree.windows().size)
        Golden.verify("overlay_dpad", out)
    }
}
