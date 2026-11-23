package dev.steinhorst.backbuttonhero.recents

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.util.Log
import android.view.Display
import dev.steinhorst.backbuttonhero.core.PService
import java.io.File

/**
 * The bottom switcher's open/close state: the task list read before the overlay opens, the
 * one-second task-state poll while it shows, and task thumbnails (at most three requests per task,
 * a delivered one is final). A session number retires every late worker result of an open that
 * was cancelled, closed or replaced. Controller routing stays with the service.
 *
 * closed --toggle--> opening --task list read--> open --toggle/dismiss--> closed;
 * opening --toggle/cancelOpen--> closed (the late result is dropped by the session).
 */
class BottomSwitcherController(private val context: Context, private val handler: Handler, private val host: Host) {
    interface Host {
        val connected: Boolean
        val overlay: SwitcherOverlay
        val parkedPackage: String?
        fun bottomDisplay(): Display?
        fun cancelTopRecentsLaunch()
        fun verifyParking()
        fun prepareSwitcher(display: Display, then: () -> Unit)
        fun captureFrontApp(then: () -> Unit)
        fun retainRecentTasks(states: List<RecentsTaskState>)
        fun switcherClosed(chose: Boolean)
    }

    private val overlay get() = host.overlay
    private val recentsThumbnails = RecentsThumbnailCache()
    private val requestedThumbnails = mutableMapOf<RecentsThumbnailCache.Key, Int>()
    private var thumbnailQueryRunning = false
    @Volatile private var session = 0
    private var stateQueryRunning = false
    var opening = false
        private set
    private var chose = false
    private val stateRunnable = Runnable { refreshTaskStates() }

    fun toggle() {
        host.cancelTopRecentsLaunch()
        val display = host.bottomDisplay() ?: return
        if (opening) { cancelOpen(); return }
        if (overlay.isShowing) { overlay.dismiss(); return }
        opening = true
        chose = false
        host.verifyParking()
        val session = ++this.session
        host.prepareSwitcher(display) { host.captureFrontApp {
            Thread {
                val states = RecentsTaskState.parse(PService.runEntryPoint(context, "RecentsStateReader", "include-excluded"))
                handler.post {
                    if (session != this.session || !host.connected) return@post
                    opening = false
                    if (states != null) host.retainRecentTasks(states)
                    runCatching { overlay.toggle(display) }.onFailure { Log.e(TAG, "toggle failed", it) }
                }
            }.apply { isDaemon = true }.start()
        } }
    }

    fun cancelOpen() {
        opening = false
        session++
    }

    /** A card choice keeps a captured panel hidden when the switcher closes. */
    fun markChosen() {
        if (overlay.isShowing) chose = true
    }

    fun shown() {
        session++
        requestedThumbnails.clear()
        refreshTaskStates()
    }

    fun dismissed() {
        session++
        handler.removeCallbacks(stateRunnable)
        host.switcherClosed(chose)
        chose = false
    }

    fun refreshTaskStates() {
        if (!overlay.isShowing || stateQueryRunning) return
        stateQueryRunning = true
        val session = this.session
        Thread {
            val states = RecentsTaskState.parse(PService.runEntryPoint(context, "RecentsStateReader", "include-excluded"))
            handler.post {
                stateQueryRunning = false
                if (!overlay.isShowing) return@post
                if (session == this.session && states != null) {
                    if (overlay.reconcileTaskStates(states)) host.retainRecentTasks(states)
                    if (!overlay.isShowing) return@post
                    loadThumbnails(states, session)
                }
                handler.removeCallbacks(stateRunnable)
                handler.postDelayed(stateRunnable, 1000L)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun loadThumbnails(states: List<RecentsTaskState>, session: Int) {
        // A parked frontend's newest task is usually its top-screen activity; keep the captured bottom preview.
        val packages = overlay.previewPackages() - setOfNotNull(host.parkedPackage)
        val width = overlay.previewWidth().coerceIn(1, 1024)
        val entries = packages.mapNotNull { overlay.taskFor(it, states) }.filter { it.taskId >= 0 }
        val requests = entries.map { it to RecentsThumbnailCache.Key(it.taskId, it.lastActiveTime, width, it.displayId) }
        requests.forEach { (state, key) -> recentsThumbnails.get(key)?.let { overlay.updateThumbnail(state.packageName, it) } }
        if (thumbnailQueryRunning) return
        val pending = requests.filter { (requestedThumbnails[it.second] ?: 0) < 3 }
        if (pending.isEmpty()) return
        thumbnailQueryRunning = true
        pending.forEach { requestedThumbnails[it.second] = (requestedThumbnails[it.second] ?: 0) + 1 }
        Thread {
            try {
                for ((state, key) in pending) {
                    if (session != this.session) break
                    val bitmap = runCatching {
                        val file = File.createTempFile("task_snapshot_", ".png", context.cacheDir)
                        try {
                            val output = PService.runEntryPoint(context, "RecentsStateReader", "snapshot",
                                state.taskId.toString(), width.toString(), file.absolutePath)
                            if (output?.startsWith("OK snapshot") == true) BitmapFactory.decodeFile(file.absolutePath) else null
                        } finally { file.delete() }
                    }.getOrNull()
                    if (bitmap != null) {
                        recentsThumbnails.put(key, bitmap)
                        handler.post {
                            if (session == this.session) requestedThumbnails[key] = 3
                            if (session == this.session && overlay.isShowing &&
                                overlay.representsTask(state)) overlay.updateThumbnail(state.packageName, bitmap)
                        }
                    }
                }
            } finally { handler.post { thumbnailQueryRunning = false } }
        }.apply { isDaemon = true }.start()
    }

    private companion object {
        const val TAG = "BackButtonHero"
    }
}
