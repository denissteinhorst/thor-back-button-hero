package dev.steinhorst.backbuttonhero.recents

import android.os.Handler

/**
 * What a window change sets off. Both screens' app history is recorded at once; the panel refresh,
 * the parked frontend's reclaim check and the thumbnail capture each wait until the window events
 * settle, so a burst of events runs each of them once.
 */
class WindowTracker(private val handler: Handler, private val host: Host) {
    interface Host {
        /** Records the apps on both screens and updates what depends on the app in front. */
        fun recordWindows()
        fun refreshPanels()
        fun parked(): Boolean
        fun reclaimParked()
        fun topRecentsActive(): Boolean
        fun checkTopRecents()
        fun capture()
    }

    private val panelRefresh = Runnable { host.refreshPanels() }
    private val reclaim = Runnable { host.reclaimParked() }
    private val capture = Runnable { host.capture() }

    /** The service is connected: panels are looked at once right away. */
    fun connected() {
        handler.post(panelRefresh)
    }

    fun windowsChanged() {
        host.recordWindows()
        restart(panelRefresh, PANEL_SETTLE_MS)
        if (host.parked()) restart(reclaim, PARKING_RECLAIM_SETTLE_MS)
        if (host.topRecentsActive()) host.checkTopRecents()
        restart(capture, CAPTURE_DELAY_MS)
    }

    private fun restart(runnable: Runnable, delayMs: Long) {
        handler.removeCallbacks(runnable)
        handler.postDelayed(runnable, delayMs)
    }

    companion object {
        const val PANEL_SETTLE_MS = 400L
        const val CAPTURE_DELAY_MS = 800L
        const val PARKING_RECLAIM_SETTLE_MS = 300L
    }
}
