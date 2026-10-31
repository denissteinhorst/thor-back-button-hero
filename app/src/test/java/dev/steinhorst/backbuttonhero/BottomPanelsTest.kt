package dev.steinhorst.backbuttonhero

import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-xxhdpi")
class BottomPanelsTest {
    private class Fixture {
        val context: Context = ApplicationProvider.getApplicationContext()
        val display: Display = context.getSystemService(DisplayManager::class.java)
            .getDisplay(ShadowDisplayManager.addDisplay("w800dp-h600dp"))
        var covered = true
        var visible = listOf(PanelWindow(552, "dev.example.frontend", display.displayId, false))
        val events = mutableListOf<String>()
        val image = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        var screenshot: Bitmap? = image
        var lost: (() -> Unit)? = null
        var closed = false
        var fail = false
        val transport = object : PanelTransport {
            override fun request(command: JSONObject, then: (List<PanelWindow>?) -> Unit) {
                val op = command.getString("op")
                events += "$op:${command.optInt("id", -1)}"
                if (fail) { lost?.invoke(); then(null); return }
                when (op) {
                    "hide", "show" -> visible = visible.map {
                        if (it.id == command.getInt("id")) it.copy(hidden = op == "hide") else it
                    }
                    "restore" -> visible = emptyList()
                }
                then(visible)
            }
            override fun close() { closed = true }
        }
        val panels = BottomPanels(context, { covered }, { panel, then ->
            assertFalse(panel.hidden)
            events += "capture:${panel.id}"
            then(screenshot)
        }, { onLost -> lost = onLost; transport }) {}
    }

    @Test
    fun `drawer launch captures the panel before hiding it and keeps that exact image for the return card`() {
        val f = Fixture()
        f.panels.prepareLaunch(f.display) { f.events += "launch" }
        assertEquals(listOf("list:-1", "capture:552", "hide:552", "launch"), f.events)
        val card = f.panels.cards(f.display.displayId).single()
        assertTrue(requireNotNull(card.panel).hidden)
        assertSame(f.image, card.thumbnail)
        assertEquals("dev.example.frontend – bottom panel", card.label)
        assertNotEquals(card.packageName, card.key)
        f.panels.show(552) { assertTrue(it) }
        assertFalse(requireNotNull(f.panels.cards(f.display.displayId).single().panel).hidden)
        f.events.clear()
        f.panels.refresh(f.display)
        assertEquals(listOf("list:-1"), f.events)
    }

    @Test
    fun `overlapping panels are captured in order before each is hidden and remain separate cards`() {
        val f = Fixture()
        f.visible += PanelWindow(553, "dev.example.frontend", f.display.displayId, false)
        f.panels.prepareLaunch(f.display) {}
        assertEquals(listOf("list:-1", "capture:552", "hide:552", "capture:553", "hide:553"), f.events)
        assertEquals(2, f.panels.cards(f.display.displayId).map { it.key }.toSet().size)
        f.panels.show(553) { assertTrue(it) }
        assertEquals(listOf(true, false), f.panels.cards(f.display.displayId).map { requireNotNull(it.panel).hidden })
    }

    @Test
    fun `recreated panel is suppressed but never inherits the destroyed windows preview`() {
        val f = Fixture()
        f.panels.prepareLaunch(f.display) {}
        f.visible = listOf(PanelWindow(999, "dev.example.frontend", f.display.displayId, false))
        f.screenshot = null
        f.panels.refresh(f.display)
        val card = f.panels.cards(f.display.displayId).single()
        assertEquals(999, requireNotNull(card.panel).id)
        assertTrue(requireNotNull(card.panel).hidden)
        assertNull(card.thumbnail)
        f.panels.show(552) { assertFalse(it) }
    }

    @Test
    fun `opening and cancelling recents only captures the visible panel without suppressing it`() {
        val f = Fixture()
        var ready = false
        f.panels.prepareSwitcher(f.display) { ready = true }
        assertTrue(ready)
        assertEquals(listOf("list:-1", "capture:552"), f.events)
        assertFalse(requireNotNull(f.panels.cards(f.display.displayId).single().panel).hidden)
    }

    private fun Fixture.withAynPanel(): Fixture {
        visible = listOf(PanelWindow(182, PanelWindows.AYN_PANEL_PACKAGE, display.displayId, false, covering = true)) + visible
        return this
    }

    private fun Fixture.aynHidden() = requireNotNull(panels.cards(display.displayId).first { it.panel?.covering == true }.panel).hidden

    @Test
    fun `recents hides a covering panel to be seen and returns it when closed without a choice`() {
        val f = Fixture().withAynPanel()
        var ready = false
        f.panels.prepareSwitcher(f.display) { ready = true }
        assertTrue(ready)
        assertEquals(listOf("list:-1", "capture:182", "hide:182", "capture:552"), f.events)
        assertTrue(f.aynHidden())
        assertTrue(f.panels.hasHiddenCovering)
        assertNotNull(f.panels.cards(f.display.displayId).first().thumbnail)
        f.events.clear()
        f.panels.switcherClosed(chose = false)
        assertEquals(listOf("show:182"), f.events)
        assertFalse(f.aynHidden())
        assertFalse(f.panels.hasHiddenCovering)
    }

    @Test
    fun `a choice in recents keeps the covering panel parked until its button brings it back`() {
        val f = Fixture().withAynPanel()
        f.panels.prepareSwitcher(f.display) {}
        f.panels.switcherClosed(chose = true)
        f.panels.prepareLaunch(f.display) {}
        assertTrue(f.aynHidden())
        f.events.clear()
        f.panels.showCovering()
        assertEquals(listOf("show:182"), f.events)
        assertFalse(f.aynHidden())
        // The button's return ends suppression, so the next refresh leaves the panel visible.
        f.events.clear()
        f.panels.refresh(f.display)
        assertEquals(listOf("list:-1"), f.events)
        assertFalse(f.aynHidden())
    }

    @Test
    fun `closing the covering card lets its owner close it and recents does not reopen it`() {
        val f = Fixture().withAynPanel()
        f.panels.prepareSwitcher(f.display) {}
        var press: (() -> Unit)? = null
        f.panels.closeCovering { done -> f.events += "press"; press = done }
        f.panels.switcherClosed(chose = false)
        assertEquals("press", f.events.last())
        f.events.clear()
        requireNotNull(press).invoke()
        assertTrue(f.events.isEmpty())
        assertTrue(f.aynHidden())
    }

    @Test
    fun `a covering panel its owner reopens stays visible while launches keep other panels suppressed`() {
        val f = Fixture()
        f.panels.prepareLaunch(f.display) {}
        f.withAynPanel()
        f.visible = f.visible.map { if (it.id == 552) it.copy(hidden = false) else it }
        f.events.clear()
        f.panels.refresh(f.display)
        assertEquals(listOf("list:-1", "capture:552", "hide:552"), f.events)
        assertFalse(f.aynHidden())
    }

    @Test
    fun `closing without a covering panel never presses its owner's button`() {
        val f = Fixture()
        f.panels.refresh(f.display)
        f.panels.closeCovering { f.events += "press" }
        f.panels.showCovering()
        assertEquals(listOf("list:-1"), f.events)
    }

    @Test
    fun `a panel already parked by a launch is not reopened when recents closes`() {
        val f = Fixture().withAynPanel()
        f.panels.prepareLaunch(f.display) {}
        f.panels.prepareSwitcher(f.display) {}
        f.events.clear()
        f.panels.switcherClosed(chose = false)
        assertTrue(f.events.isEmpty())
        assertTrue(f.aynHidden())
    }

    @Test
    fun `a failed hide or show stops without claiming the covering panel moved`() {
        val f = Fixture().withAynPanel()
        f.panels.refresh(f.display)
        f.fail = true
        f.panels.prepareSwitcher(f.display) { f.events += "ready" }
        assertEquals("ready", f.events.last())
        assertFalse(f.panels.hasHiddenCovering)
        f.fail = false
        f.panels.prepareSwitcher(f.display) {}
        f.fail = true
        f.panels.showCovering()
        f.panels.switcherClosed(chose = false)
        assertFalse(f.panels.hasPanels)
    }

    @Test
    fun `the covering flag survives the root channel and older replies default to not covering`() {
        val panel = PanelWindow(182, PanelWindows.AYN_PANEL_PACKAGE, 4, hidden = true, covering = true)
        assertEquals(panel, PanelWindow.parse(panel.json()))
        assertFalse(PanelWindow.parse(panel.json().apply { remove("covering") }).covering)
    }

    @Test
    fun `ordinary displays and failed privileged connections still complete the requested launch`() {
        val f = Fixture()
        f.covered = false
        f.panels.prepareLaunch(f.display) { f.events += "ordinary launch" }
        assertEquals(listOf("ordinary launch"), f.events)
        f.covered = true
        f.fail = true
        f.panels.prepareLaunch(f.display) { f.events += "fallback launch" }
        assertEquals("fallback launch", f.events.last())
        assertFalse(f.panels.hasPanels)
    }

    @Test
    fun `losing the display or service releases the panels instead of retaining an unreachable return card`() {
        val f = Fixture()
        f.panels.prepareLaunch(f.display) {}
        f.panels.refresh(null)
        assertTrue(f.panels.cards(f.display.displayId).isEmpty())
        assertEquals("restore:-1", f.events.last())
        f.panels.close()
        assertTrue(f.closed)
    }

    @Test
    fun `swap is blocked for either foreground owner with visible or hidden panels but not unrelated apps`() {
        val f = Fixture()
        f.panels.refresh(f.display)
        assertTrue(f.panels.preventsSwap("dev.example.frontend", "dev.example.game"))
        assertTrue(f.panels.preventsSwap("dev.example.game", "dev.example.frontend"))
        assertFalse(f.panels.preventsSwap("dev.example.game", "dev.example.browser"))
        assertFalse(f.panels.preventsSwap(null, null))
        f.panels.prepareLaunch(f.display) {}
        assertTrue(f.panels.preventsSwap("dev.example.frontend", null))
        f.visible = emptyList()
        f.covered = false
        f.panels.refresh(f.display)
        assertFalse(f.panels.preventsSwap("dev.example.frontend", "dev.example.game"))
    }
}
