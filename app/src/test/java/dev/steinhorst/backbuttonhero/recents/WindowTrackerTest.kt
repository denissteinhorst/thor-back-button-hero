package dev.steinhorst.backbuttonhero.recents

import android.os.Handler
import android.os.Looper
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class WindowTrackerTest {
    private class Host(var parked: Boolean = false, var topRecents: Boolean = false) : WindowTracker.Host {
        val calls = mutableListOf<String>()
        override fun recordWindows() { calls += "record" }
        override fun refreshPanels() { calls += "panels" }
        override fun parked() = parked
        override fun reclaimParked() { calls += "reclaim" }
        override fun topRecentsActive() = topRecents
        override fun checkTopRecents() { calls += "topRecents" }
        override fun capture() { calls += "capture" }
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun `connecting refreshes the panels right away`() {
        val host = Host()
        WindowTracker(Handler(Looper.getMainLooper()), host).connected()
        idle(0)
        assertEquals(listOf("panels"), host.calls)
    }

    @Test
    fun `a burst of changes records each time but runs every settled step once, in delay order`() {
        val host = Host(parked = true, topRecents = true)
        val tracker = WindowTracker(Handler(Looper.getMainLooper()), host)
        tracker.windowsChanged()
        idle(200)
        tracker.windowsChanged()
        assertEquals(listOf("record", "topRecents", "record", "topRecents"), host.calls)
        idle(WindowTracker.PARKING_RECLAIM_SETTLE_MS)
        idle(WindowTracker.PANEL_SETTLE_MS - WindowTracker.PARKING_RECLAIM_SETTLE_MS)
        idle(WindowTracker.CAPTURE_DELAY_MS)
        assertEquals(listOf("record", "topRecents", "record", "topRecents", "reclaim", "panels", "capture"), host.calls)
    }

    @Test
    fun `without a parked frontend or a top Recents session those steps stay out`() {
        val host = Host()
        WindowTracker(Handler(Looper.getMainLooper()), host).windowsChanged()
        idle(WindowTracker.CAPTURE_DELAY_MS)
        assertEquals(listOf("record", "panels", "capture"), host.calls)
    }
}
