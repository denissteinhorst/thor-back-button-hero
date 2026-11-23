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
        fun canRelaunch(pkg: String): Boolean
        fun holdSwapBlocked()
        fun unavailable()
        fun focusRoute(): String
        fun switchAppFollowFocus(button: ComboButton): Boolean
        fun runFocus(target: String, onComplete: (Boolean) -> Unit)
        fun runAutoFocus(displayId: Int, then: (Boolean) -> Unit)
        fun dismissAutoFocus()
    }

    // Main thread only; the session retires workers of a switch that was cancelled or replaced.
    private var switching = false
    @Volatile private var session = 0

    fun cancel() {
        session++
        switching = false
        host.dismissAutoFocus()
    }

    /** Move the two captured foreground tasks; the first move must not change the second target. */
    fun swapScreens() {
        host.cancelSwitcherOpen()
        val display = host.bottomDisplay() ?: return
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
            Thread {
                val states = RecentsTaskState.parse(PService.runEntryPoint(context, "RecentsStateReader", "include-excluded"))
                val moves = TaskMovePolicy.planSwap(topApp, bottomApp, top, bottom, states, host::canRelaunch)
                if (moves.isNullOrEmpty()) {
                    handler.post { notice.show(R.string.swap_unavailable, long = true) }
                    return@Thread
                }
                for (move in moves) {
                    if (!host.launchTask(move.packageName, move.targetDisplay, move.taskId, true)) break
                }
            }.apply { isDaemon = true }.start()
        }
    }

    fun switchActiveApp(button: ComboButton) {
        if (switching) return
        val display = host.bottomDisplay() ?: return
        val top = Display.DEFAULT_DISPLAY
        val bottom = display.displayId
        val route = host.focusRoute()
        val follow = host.switchAppFollowFocus(button)
        val foreground = mapOf(top to host.topApp(top), bottom to host.topApp(bottom))
        switching = true
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
}
