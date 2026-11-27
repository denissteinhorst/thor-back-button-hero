package dev.steinhorst.backbuttonhero

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.view.Display
import dev.steinhorst.backbuttonhero.core.AppSource
import dev.steinhorst.backbuttonhero.core.RunningApp
import dev.steinhorst.backbuttonhero.panels.PanelWindow
import dev.steinhorst.backbuttonhero.recents.SwitcherOverlay
import dev.steinhorst.backbuttonhero.support.Golden
import dev.steinhorst.backbuttonhero.support.OverlayTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Freezes the bottom Recents overlay as SwitcherOverlay builds it, for fixed fake apps with plain
 * icons and thumbnails. Canvas content (thumbnail pixels) is not part of a tree snapshot, and the
 * launcher-derived selection colour is absent on the JVM, so card selection shows as scroll position.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-xxhdpi")
class SwitcherSnapshotTest {

    private fun app(pkg: String, label: String, color: Int) =
        RunningApp(pkg, label, ColorDrawable(color), Bitmap.createBitmap(40, 24, Bitmap.Config.ARGB_8888))

    private val many = listOf(
        app("com.example.mail", "Mail", Color.BLUE),
        app("com.example.game", "Game", Color.RED),
        app("org.example.emu", "Emulator", Color.GREEN),
    )
    private val panel = RunningApp("org.example.frontend", "Frontend", ColorDrawable(Color.GRAY), null,
        PanelWindow(7, "org.example.frontend", Display.DEFAULT_DISPLAY, hidden = true))

    private fun overlay(apps: List<RunningApp>, panels: List<RunningApp> = emptyList(), hints: Boolean? = null): Pair<SwitcherOverlay, Display> {
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        val source = object : AppSource {
            override fun appsOnDisplay(displayId: Int) = apps
            override fun panelsOnDisplay(displayId: Int) = panels
            override fun forget(packageName: String) = Unit
        }
        val display = service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        return SwitcherOverlay(service, source, { false }, {}, { _, _ -> }, {}, { _, _ -> }, {}, {}, controllerHints = { hints }) to display
    }

    /** The overlay's window only, serialized in its current state. */
    private fun shown(label: String): String {
        ShadowLooper.idleMainLooper()
        val windows = OverlayTree.windows()
        assertEquals("exactly one overlay window", 1, windows.size)
        return "## $label\n" + OverlayTree.describe(windows, state = true)
    }

    private fun record(golden: String, apps: List<RunningApp>, panels: List<RunningApp> = emptyList(), steps: Boolean = false, hints: Boolean? = null) {
        val (overlay, display) = overlay(apps, panels, hints)
        overlay.toggle(display)
        assertTrue(overlay.isShowing)
        val out = StringBuilder(shown("shown"))
        if (steps) {
            overlay.moveSelection(right = false)
            out.append(shown("selection moved left"))
            overlay.moveSelectionDown()
            out.append(shown("selection moved down to the buttons"))
            overlay.moveSelection(right = true)
            out.append(shown("button selection moved right"))
            overlay.moveSelectionUp()
            out.append(shown("selection back on the cards"))
        }
        overlay.toggle(display)
        ShadowLooper.idleMainLooper()
        assertEquals("toggling again removes the window", 0, OverlayTree.windows().size)
        Golden.verify(golden, out.toString())
    }

    @Test
    fun `no apps`() = record("switcher_empty_en_light", emptyList())

    @Test
    fun `one app`() = record("switcher_one_en_light", many.take(1))

    @Test
    fun `apps, a panel and controller selection`() = record("switcher_many_en_light", many, listOf(panel), steps = true)

    @Test
    @Config(qualifiers = "en-night-xxhdpi")
    fun `apps, a panel and controller selection, dark`() = record("switcher_many_en_dark", many, listOf(panel), steps = true)

    @Test
    @Config(qualifiers = "de-xxhdpi")
    fun `apps, a panel and controller selection, German`() = record("switcher_many_de_light", many, listOf(panel), steps = true)

    @Test
    fun `controller legend, Standard style`() = record("switcher_hints_standard_en_light", many, hints = false)

    @Test
    fun `controller legend, Xbox style`() = record("switcher_hints_xbox_en_light", many, hints = true)

    @Test
    @Config(qualifiers = "en-night-xxhdpi")
    fun `controller legend, Standard style, dark`() = record("switcher_hints_standard_en_dark", many, hints = false)

    @Test
    @Config(qualifiers = "en-night-xxhdpi")
    fun `controller legend, Xbox style, dark`() = record("switcher_hints_xbox_en_dark", many, hints = true)

    @Test
    fun `controller legend without apps leaves out Close all`() = record("switcher_hints_empty_en_light", emptyList(), hints = false)
}
