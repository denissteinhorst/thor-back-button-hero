package dev.steinhorst.backbuttonhero.recents

import android.content.Context
import android.os.Handler
import android.view.Display
import dev.steinhorst.backbuttonhero.core.PService

/**
 * The launch phase of native top Recents: the task list taken before Overview opens, the 250 ms
 * Quickstep poll until Overview closes again, and the selection read that identifies the task a
 * card moved. A session number retires late worker results of a launch that was cancelled or
 * replaced. The controller session that may run alongside stays with the service.
 *
 * idle --open--> baseline read --> polling (250 ms) --Overview seen, then gone--> selection read --> idle;
 * polling --25 attempts without Overview / cancel / card selected--> idle.
 */
class TopRecentsLauncher(private val context: Context, private val handler: Handler, private val host: Host) {
    interface Host {
        val connected: Boolean
        val controllerEnabled: Boolean
        fun leaveSwitcher()
        fun openNative()
        fun launchFromRecents(pkg: String, displayId: Int, taskId: Int)
        /** A card was chosen: the controller session ends before the task moves. */
        fun selected()
        fun scheduleStateCheck(delayMs: Long)
        fun trace(message: String)
    }

    private var session = 0
    /** Non-null while the launch phase runs: the tasks present when Recents opened. */
    var baseline: List<RecentsTaskState>? = null
        private set
    private var overviewSeen = false
    private var attempts = 0
    private var queryRunning = false
    var selectionPending = false
        private set
    private var startedAt = 0L
    private val runnable = Runnable { check() }

    fun open() {
        host.leaveSwitcher()
        val session = ++this.session
        handler.removeCallbacks(runnable)
        baseline = null
        overviewSeen = false
        selectionPending = false
        attempts = 0
        Thread {
            val baseline = RecentsTaskState.parse(PService.runEntryPoint(context, "RecentsStateReader"))
            handler.post {
                if (session != this.session || !host.connected) return@post
                host.trace("TOP_RECENTS launch baseline=${baseline?.size}")
                this.baseline = baseline
                startedAt = System.currentTimeMillis() * 1_000_000L
                host.openNative()
                if (baseline != null) handler.postDelayed(runnable, 250L)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun check() {
        val baseline = baseline ?: return
        if (queryRunning || selectionPending) {
            handler.postDelayed(runnable, 250L)
            return
        }
        queryRunning = true
        val session = this.session
        val overviewSeen = overviewSeen
        val startedAt = startedAt
        Thread {
            val dump = PService.exec(QUICKSTEP_STATE_COMMAND)
            val overview = dump?.let { TopRecentsState.parse(it).overview }
            val states = if (overview == false && overviewSeen) {
                RecentsTaskState.parse(PService.runEntryPoint(context, "RecentsStateReader", "selection", startedAt.toString()))
            } else null
            handler.post {
                queryRunning = false
                if (session != this.session || !host.connected) return@post
                if (selectionPending) {
                    handler.postDelayed(runnable, 250L)
                    return@post
                }
                when {
                    overview == true -> {
                        if (!this.overviewSeen && !host.controllerEnabled) {
                            Thread { PService.runEntryPoint(context, "TopRecentsFocus") }.apply { isDaemon = true }.start()
                        }
                        this.overviewSeen = true
                    }
                    overview == false && this.overviewSeen -> {
                        this.baseline = null
                        val task = states?.let { RecentsSelection.movedTask(baseline, it, Display.DEFAULT_DISPLAY) }
                        host.trace("TOP_RECENTS selected task=${task?.taskId} states=${states?.size}")
                        if (task != null) host.launchFromRecents(task.packageName, Display.DEFAULT_DISPLAY, task.taskId)
                        return@post
                    }
                    ++attempts >= READY_ATTEMPTS -> {
                        this.baseline = null
                        return@post
                    }
                }
                handler.postDelayed(runnable, 250L)
            }
        }.apply { isDaemon = true }.start()
    }

    fun cancel() {
        session++
        baseline = null
        selectionPending = false
        handler.removeCallbacks(runnable)
    }

    /** A click on a native card; only a unique label bound to a known task identifies it. */
    fun selectCard(description: String) {
        val baseline = baseline ?: return
        if (selectionPending) return
        selectionPending = true
        val session = this.session
        Thread {
            val states = RecentsTaskState.parse(PService.runEntryPoint(context, "RecentsStateReader"))
            handler.post {
                if (session != this.session || !host.connected) return@post
                selectionPending = false
                val task = states?.let { RecentsSelection.fromCard(baseline, it, description) }
                if (task != null) {
                    cancel()
                    // End controller initialization before moving the app. A late window-change
                    // check must not bring Overview back over the task we just selected.
                    host.selected()
                    if (task.displayId != Display.DEFAULT_DISPLAY) host.launchFromRecents(task.packageName, Display.DEFAULT_DISPLAY, task.taskId)
                    host.scheduleStateCheck(EVENT_SETTLE_MS)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        const val QUICKSTEP_STATE_COMMAND =
            "dumpsys activity com.android.launcher3 | " +
                "awk '/ACTIVITY /{a=/QuickstepLauncher/} " +
                "a&&(/mState:|mResumed=|mHasWindowFocus=/||(/TaskView\\{/&&(!t++||/ \\.F/))||" +
                "(/ \\.F/&&/app:id\\/(action_screenshot|clear_all_button)/)){printf \"%s|\",\$0}'"
        const val READY_ATTEMPTS = 25
        const val EVENT_SETTLE_MS = 140L
    }
}
