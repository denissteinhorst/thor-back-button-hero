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
