package dev.steinhorst.backbuttonhero

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalKeyTrackerTest {
    @Test
    fun `duplicate devices trigger one physical press`() {
        val tracker = PhysicalKeyTracker()

        assertTrue(tracker.down(keyCode = 105, deviceId = 11))
        assertFalse(tracker.down(keyCode = 105, deviceId = 12))
        assertTrue(tracker.isDown(keyCode = 105))
        assertTrue(tracker.up(keyCode = 105, deviceId = 11))
        assertTrue(tracker.isDown(keyCode = 105))
        assertTrue(tracker.up(keyCode = 105, deviceId = 12))
        assertFalse(tracker.isDown(keyCode = 105))

        assertTrue(tracker.down(keyCode = 105, deviceId = 11))
    }

    @Test
    fun `untracked release is left alone`() {
        assertFalse(PhysicalKeyTracker().up(keyCode = 105, deviceId = 11))
    }

    @Test
    fun `release from another device is left alone`() {
        val tracker = PhysicalKeyTracker()
        tracker.down(keyCode = 105, deviceId = 11)
        assertFalse(tracker.up(keyCode = 105, deviceId = 12))
        assertTrue(tracker.isDown(keyCode = 105))
    }

    @Test
    fun `clear starts a fresh press cycle`() {
        val tracker = PhysicalKeyTracker()
        assertTrue(tracker.down(keyCode = 105, deviceId = 11))
        tracker.clear()
        assertTrue(tracker.down(keyCode = 105, deviceId = 11))
    }
}
