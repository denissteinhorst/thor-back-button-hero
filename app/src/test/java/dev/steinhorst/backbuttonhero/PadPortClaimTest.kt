package dev.steinhorst.backbuttonhero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PadPortClaimTest {
    @Test
    fun `every RetroArch build is recognized and nothing else`() {
        assertTrue(PadPortClaim.isRetroArch("com.retroarch"))
        assertTrue(PadPortClaim.isRetroArch("com.retroarch.aarch64"))
        assertTrue(PadPortClaim.isRetroArch("com.retroarch.ra32"))
        assertFalse(PadPortClaim.isRetroArch("org.es_de.frontend"))
        assertFalse(PadPortClaim.isRetroArch(null))
    }

    @Test
    fun `RetroArch arriving on a screen claims the port while staying in front does not`() {
        assertTrue(PadPortClaim.arrived(emptySet(), setOf(0)))
        assertTrue(PadPortClaim.arrived(setOf(0), setOf(4)))
        assertFalse(PadPortClaim.arrived(setOf(0), setOf(0)))
        assertFalse(PadPortClaim.arrived(setOf(0), emptySet()))
    }

    @Test
    fun `the route follows a swap only when RetroArch is the controlled app`() {
        val game = "com.retroarch.aarch64"
        val companion = "com.esde.companion"
        assertEquals(Prefs.DISPLAY_BOTTOM, PadPortClaim.routeBeforeSwap(true, game, companion))
        assertEquals(Prefs.DISPLAY_TOP, PadPortClaim.routeBeforeSwap(false, companion, game))
        assertNull(PadPortClaim.routeBeforeSwap(false, game, companion))
        assertNull(PadPortClaim.routeBeforeSwap(true, companion, game))
        assertNull(PadPortClaim.routeBeforeSwap(true, "dev.example.game", companion))
    }

    @Test
    fun `a slow reset still gets a later nudge`() {
        assertTrue(PadPortClaim.NUDGE_DELAYS_MS.size >= 2 && PadPortClaim.NUDGE_DELAYS_MS.last() > PadPortClaim.NUDGE_DELAYS_MS.first())
    }
}
