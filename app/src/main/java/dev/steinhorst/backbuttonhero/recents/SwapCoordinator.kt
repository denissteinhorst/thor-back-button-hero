package dev.steinhorst.backbuttonhero.recents

import android.content.Context
import android.os.Handler
import android.view.Display
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.actions.AutoControllerFocus
import dev.steinhorst.backbuttonhero.actions.ControllerFocus
import dev.steinhorst.backbuttonhero.core.PService
import dev.steinhorst.backbuttonhero.input.ComboButton
import dev.steinhorst.backbuttonhero.overlay.NoticeOverlay
import dev.steinhorst.backbuttonhero.prefs.AppException
import dev.steinhorst.backbuttonhero.prefs.Prefs

/**
 * Hold-to-swap and moving the app with controller focus to the other screen. Both move existing
 * tasks through the task launcher and never relaunch packages for the switch; a panel or a Hold
 * exception refuses the move before anything leaves its screen.
 */
class SwapCoordinator(private val context: Context, private val handler: Handler, private val notice: NoticeOverlay, private val host: Host) {
    interface Host {
        val connected: Boolean
        val interceptionEnabled: Boolean
        val appExceptions: List<AppException>
        fun bottomDisplay(): Display?
        fun topApp(displayId: Int): String?
        fun cancelSwitcherOpen()
        fun dismissSwitcher()
        fun refreshPanels(display: Display, then: () -> Unit)
        fun panelsPreventSwap(topPackage: String?, bottomPackage: String?): Boolean
        fun prepareBottomLaunch(displayId: Int, then: () -> Unit)
        fun launchTask(pkg: String, displayId: Int, taskId: Int?, allowRelaunch: Boolean): Boolean
        /** A swap's move, which may fall back to a relaunch; only [TaskOps.Outcome.MOVED] moved the task. */
        fun swapTask(pkg: String, displayId: Int, taskId: Int?): TaskOps.Outcome
        fun canRelaunch(pkg: String): Boolean
        fun holdSwapBlocked()
        fun unavailable()
        fun focusRoute(): String
        fun switchAppFollowFocus(button: ComboButton): Boolean
        fun runFocus(target: String, onComplete: (Boolean) -> Unit)
        fun runAutoFocus(displayId: Int, then: (Boolean) -> Unit)
        fun dismissAutoFocus()
        /** Taps the pad button that reports [keyCode] to the foreground app, on [trigger]'s behalf. */
        fun pressForApp(trigger: ComboButton, keyCode: Int)
    }

    // Main thread only; the session retires workers of a switch that was cancelled or replaced.
    private var switching = false
    @Volatile private var session = 0
    // Main thread only: packages that took a swapped task back in this service session are not moved again.
    private val selfArranging = mutableSetOf<String>()
    // Main thread only: a later swap (or switch) retires the take-back check of an earlier one, which would
    // otherwise see the second swap's result as its apps going back.
    private var swapGeneration = 0

    fun cancel() {
        session++
        switching = false
        host.dismissAutoFocus()
    }

    /**
     * Move the two captured foreground tasks; the first move must not change the second target. An app
     * that takes a moved task back is remembered and refused afterwards, so it is not rebuilt on every Hold.
     */
    fun swapScreens(trigger: ComboButton) {
        val generation = ++swapGeneration
        host.cancelSwitcherOpen()
        val display = host.bottomDisplay() ?: return host.unavailable()
        val bottom = display.displayId
        val top = Display.DEFAULT_DISPLAY
        host.dismissSwitcher()
        host.refreshPanels(display) {
            if (!host.connected) return@refreshPanels
            val exceptions = host.appExceptions
            val topPackage = host.topApp(top)
            val bottomPackage = host.topApp(bottom)
            val topApp = TaskMovePolicy.swapParticipant(topPackage, exceptions)
            val bottomApp = TaskMovePolicy.swapParticipant(bottomPackage, exceptions)
            if (topApp == null && bottomApp == null &&
                (topPackage != null || bottomPackage != null)) {
                host.holdSwapBlocked()
                return@refreshPanels
            }
            if (host.panelsPreventSwap(topApp, bottomApp)) {
                notice.show(R.string.panel_swap_unavailable, long = true)
                return@refreshPanels
            }
            when (SwapDelegation.delegate(topApp, bottomApp)) {
                SwapDelegation.IISU -> return@refreshPanels delegateToIisu(trigger)
                SwapDelegation.COCOON -> return@refreshPanels host.pressForApp(trigger, SwapDelegation.COCOON_KEY)
            }
            if (listOfNotNull(topApp, bottomApp).any { it in selfArranging }) {
                notice.show(R.string.panel_swap_unavailable, long = true)
                return@refreshPanels
            }
            Thread {
                val states = RecentsTaskState.parse(PService.runEntryPoint(context, "RecentsStateReader", "include-excluded"))
                val moves = TaskMovePolicy.planSwap(topApp, bottomApp, top, bottom, states, host::canRelaunch)
                if (moves.isNullOrEmpty()) {
                    handler.post { notice.show(R.string.swap_unavailable, long = true) }
                    return@Thread
                }
                val outcomes = mutableListOf<TaskOps.Outcome>()
                for (move in moves) {
                    outcomes += host.swapTask(move.packageName, move.targetDisplay, move.taskId)
                    if (outcomes.last() == TaskOps.Outcome.FAILED) break
                }
                // Only real moves can be taken back; a relaunch leaves the tasks where they were.
                if (moves.any { it.taskId != null } && outcomes.size == moves.size && outcomes.all { it == TaskOps.Outcome.MOVED }) {
                    handler.postDelayed({ checkTakenBack(moves, generation) }, TAKE_BACK_CHECK_MS)
                }
            }.apply { isDaemon = true }.start()
        }
    }

    /** A frontend that lays out both screens itself rebuilds its own arrangement right after the moves. */
    private fun checkTakenBack(moves: List<TaskMovePolicy.Move>, generation: Int) {
        if (!host.connected || generation != swapGeneration) return
        Thread {
            val after = RecentsTaskState.parse(PService.runEntryPoint(context, "RecentsStateReader", "include-excluded")) ?: return@Thread
            handler.post {
                if (!host.connected || generation != swapGeneration) return@post
                val bottom = host.bottomDisplay()?.displayId ?: return@post
                val front = listOf(Display.DEFAULT_DISPLAY, bottom).associateWith(host::topApp)
                val rejected = TaskMovePolicy.takenBack(moves, after, front)
                if (rejected.isEmpty()) return@post
                selfArranging += rejected.map { it.packageName }
                notice.show(R.string.panel_swap_unavailable, long = true)
            }
        }.apply { isDaemon = true }.start()
    }

    /** IISU swaps its own screens on a button of its settings; with its swap switched off nothing can swap it. */
    private fun delegateToIisu(trigger: ComboButton) {
        Thread {
            val keyCode = SwapDelegation.iisuKeyCode(PService.exec(SwapDelegation.iisuButtonCommand(context.applicationInfo.dataDir)))
            handler.post {
                if (!host.connected) return@post
                if (keyCode == null) notice.show(R.string.panel_swap_unavailable, long = true)
                else host.pressForApp(trigger, keyCode)
            }
        }.apply { isDaemon = true }.start()
    }

    fun switchActiveApp(button: ComboButton) {
        if (switching) return
        val display = host.bottomDisplay() ?: return host.unavailable()
        val top = Display.DEFAULT_DISPLAY
        val bottom = display.displayId
        val route = host.focusRoute()
        val follow = host.switchAppFollowFocus(button)
        val foreground = mapOf(top to host.topApp(top), bottom to host.topApp(bottom))
        switching = true
        swapGeneration++
        val session = ++this.session
        fun active() = host.connected && host.interceptionEnabled && session == this.session
        Thread {
            val from = when (route) {
                Prefs.DISPLAY_TOP -> top
                Prefs.DISPLAY_BOTTOM -> bottom
                else -> AutoControllerFocus.focusedDisplay(PService.exec("dumpsys input | grep -m1 'FocusedDisplayId:'"))
            }
            val pkg = foreground[from]
            val task = if (pkg == null) null else {
                val tasks = RecentsTaskState.parse(PService.runEntryPoint(context, "RecentsStateReader", "include-excluded"))
                tasks?.firstOrNull { it.packageName == pkg && it.displayId == from && it.movable }
            }
            handler.post resolved@ {
                if (!active()) return@resolved
                if (from != top && from != bottom) {
                    switching = false
                    host.unavailable()
                    return@resolved
                }
                val target = if (from == top) bottom else top
                if (pkg == null) {
                    switching = false
                    notice.show(R.string.app_control_no_app)
                    return@resolved
                }
                if (task == null) {
                    switching = false
                    host.unavailable()
                    return@resolved
                }
                host.cancelSwitcherOpen()
                host.dismissSwitcher()
                host.refreshPanels(display) {
                    if (!active()) return@refreshPanels
                    if (host.panelsPreventSwap(pkg, null)) {
                        switching = false
                        notice.show(R.string.panel_swap_unavailable, long = true)
                        return@refreshPanels
                    }
                    host.prepareBottomLaunch(target) {
                        if (!active()) return@prepareBottomLaunch
                        Thread {
                            val moved = session == this.session && host.launchTask(pkg, target, task.taskId, false)
                            handler.post moved@ {
                                if (!active()) return@moved
                                switching = false
                                if (!moved) return@moved
                                if (route == ControllerFocus.AUTO_TARGET) {
                                    switching = true
                                    host.runAutoFocus(if (follow) target else from) { ok ->
                                        switching = false
                                        if (active() && !ok) host.unavailable()
                                    }
                                } else if (follow) {
                                    switching = true
                                    host.runFocus(if (target == top) Prefs.DISPLAY_TOP else Prefs.DISPLAY_BOTTOM) {
                                        if (active()) switching = false
                                    }
                                }
                            }
                        }.apply { isDaemon = true }.start()
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private companion object {
        /** IISU had rebuilt both screens about 0.4 s after the second move; the check waits well past that. */
        const val TAKE_BACK_CHECK_MS = 1500L
    }
}
