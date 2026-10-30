package dev.steinhorst.backbuttonhero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkingPolicyTest {
    private val own = "dev.steinhorst.backbuttonhero"

    @Test
    fun `only the same app in front on both screens is a frontend to park`() {
        assertEquals("dev.example.frontend", ParkingPolicy.candidate("dev.example.frontend", "dev.example.frontend", own, emptySet()))
        assertNull(ParkingPolicy.candidate("dev.example.frontend", "dev.example.game", own, emptySet()))
        assertNull(ParkingPolicy.candidate(null, "dev.example.frontend", own, emptySet()))
        assertNull(ParkingPolicy.candidate("dev.example.frontend", null, own, emptySet()))
    }

    @Test
    fun `our own settings, abandoned frontends and unsafe names are never parked`() {
        assertNull(ParkingPolicy.candidate(own, own, own, emptySet()))
        assertNull(ParkingPolicy.candidate("dev.example.frontend", "dev.example.frontend", own, setOf("dev.example.frontend")))
        assertNull(ParkingPolicy.candidate("x; reboot", "x; reboot", own, emptySet()))
    }

    @Test
    fun `a late reclaim is parked again while an immediate one stops parking`() {
        val parkedAt = 10_000L
        assertFalse(ParkingPolicy.mayRepark(parkedAt + 130, parkedAt))
        assertFalse(ParkingPolicy.mayRepark(parkedAt + ParkingPolicy.REPARK_MIN_INTERVAL_MS - 1, parkedAt))
        assertTrue(ParkingPolicy.mayRepark(parkedAt + ParkingPolicy.REPARK_MIN_INTERVAL_MS, parkedAt))
        assertTrue(ParkingPolicy.mayRepark(parkedAt + 60_000, parkedAt))
    }

    @Test
    fun `only a successful park result yields the moved task`() {
        assertEquals(237, ParkingPolicy.parkedTask("OK task=237"))
        assertNull(ParkingPolicy.parkedTask("ERR not front"))
        assertNull(ParkingPolicy.parkedTask("OK task=-1"))
        assertNull(ParkingPolicy.parkedTask("OK task=x"))
        assertNull(ParkingPolicy.parkedTask(null))
    }
}
