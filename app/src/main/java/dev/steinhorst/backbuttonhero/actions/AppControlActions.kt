package dev.steinhorst.backbuttonhero.actions

import android.content.Context
import android.util.Log
import android.view.Display
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.input.ComboButton
import dev.steinhorst.backbuttonhero.overlay.NoticeOverlay
import dev.steinhorst.backbuttonhero.prefs.Prefs

/**
 * The actions that end apps or the session: minimize, force-close or restart the app in front, close
 * the apps known to run on the chosen screens, and standby. Each may first ask on the controller's
 * screen; Cancel never changes anything.
 */
class AppControlActions(
    private val context: Context,
    private val prefs: Prefs,
    private val notice: NoticeOverlay,
    private val host: Host,
) {
    interface Host {
        fun bottomDisplayId(): Int?
        fun topApp(displayId: Int): String?
        fun appsOnDisplay(displayId: Int): List<String>
        fun forget(packageName: String)
        /** Shows the choice prompt where the controller is. */
        fun prompt(title: String, options: List<AppControlPrompt.Option>, onChoice: (String) -> Unit)
        /** Runs [then] with the screen the controller drives, [Prefs.DISPLAY_TOP] or [Prefs.DISPLAY_BOTTOM]. */
        fun focusedScreen(then: (String) -> Unit)
        fun kill(packages: List<String>, displays: Set<Int>, reportMemory: Boolean, afterwards: (() -> Unit)?)
        fun launchOn(packageName: String, displayId: Int)
        fun goHome(displayId: Int)
        fun lockScreen()
    }

    /**
     * Minimizes or force-closes the foreground app of the chosen screen. The apps are resolved
     * before any prompt opens, so "the app behind the modal" is the one that gets hit even if the
     * window state shifts while the user decides.
     */
    fun runAppControl(button: ComboButton) {
        val screen = prefs.appScreen(button)
        if (screen == Prefs.TARGET_FOCUSED) host.focusedScreen { runAppControl(button, it) } else runAppControl(button, screen)
    }

    private fun runAppControl(button: ComboButton, screen: String) {
        val mode = prefs.appMode(button)
        val bottomId = host.bottomDisplayId()
        fun resolve(chosen: String) = AppControl.targets(chosen, Display.DEFAULT_DISPLAY, bottomId, host::topApp)
        if (screen == Prefs.APP_SCREEN_ASK) {
            val labels = mapOf(
                Prefs.DISPLAY_TOP to context.getString(R.string.level_target_top),
                Prefs.DISPLAY_BOTTOM to context.getString(R.string.level_target_bottom),
                Prefs.TARGET_BOTH to context.getString(R.string.level_target_both),
            )
            val options = listOf(Prefs.DISPLAY_TOP, Prefs.DISPLAY_BOTTOM, Prefs.TARGET_BOTH)
                .map { AppControlPrompt.Option(it, labels.getValue(it)) }
            // Resolved up front: the modal must not change which app counts as "in front".
            val resolved = options.associate { it.value to resolve(it.value) }
            host.prompt(context.getString(R.string.app_control_prompt_title), options) { chosen ->
                applyAppControl(mode, resolved.getValue(chosen))
            }
            return
        }
        val targets = resolve(screen)
        if (mode != Prefs.APP_MINIMIZE && prefs.appConfirm(button) && targets.isNotEmpty()) {
            val names = targets.joinToString(", ") { label(it.packageName) }
            val restart = mode == Prefs.APP_RESTART
            // The prompt adds its own Cancel row below.
            val options = listOf(
                AppControlPrompt.Option("yes", context.getString(if (restart) R.string.app_control_prompt_restart_yes else R.string.app_control_prompt_close_yes)),
            )
            val title = context.getString(if (restart) R.string.app_control_prompt_restart_title else R.string.app_control_prompt_close_title, names)
            host.prompt(title, options) {
                if (it == "yes") applyAppControl(mode, targets)
            }
            return
        }
        applyAppControl(mode, targets)
    }

    /** Closes the apps known to run on the button's screens, optionally sparing whatever is in front. */
    fun runCloseApps(button: ComboButton, keepForeground: Boolean) {
        val all = listOfNotNull(Display.DEFAULT_DISPLAY, host.bottomDisplayId())
        val displays = CloseApps.displays(prefs.levelTarget(button), Display.DEFAULT_DISPLAY, host.bottomDisplayId())
        val packages = CloseApps.packages(displays, all, keepForeground, host::appsOnDisplay, host::topApp)
        Log.d(TAG, "Close apps on $displays (keep foreground: $keepForeground): $packages")
        if (packages.isEmpty()) {
            notice.show(R.string.close_apps_none)
            return
        }
        // Every screen, not only the chosen ones: a closed app may have moved in front of the other one.
        fun close() {
            packages.forEach(host::forget)
            host.kill(packages, all.toSet(), true, null)
        }
        if (!prefs.closeConfirm(button)) {
            close()
            return
        }
        val names = packages.joinToString(", ") { label(it) }
        val options = listOf(AppControlPrompt.Option("yes", context.getString(R.string.close_apps_prompt_yes)))
        host.prompt(context.getString(R.string.close_apps_prompt_title, names), options) {
            if (it == "yes") close()
        }
    }

    /** Locks and switches off the screens as a short press of the power button does. */
    fun runStandby(button: ComboButton) {
        if (!prefs.standbyConfirm(button)) {
            host.lockScreen()
            return
        }
        val options = listOf(AppControlPrompt.Option("yes", context.getString(R.string.group_standby)))
        host.prompt(context.getString(R.string.standby_prompt_title), options) {
            if (it == "yes") host.lockScreen()
        }
    }

    private fun applyAppControl(mode: String, targets: List<AppControlTarget>) {
        if (targets.isEmpty()) {
            notice.show(R.string.app_control_no_app)
            return
        }
        if (mode == Prefs.APP_FORCE_CLOSE) {
            // kill already leaves the app for Home first and falls back to an unprivileged kill.
            host.kill(targets.map { it.packageName }.distinct(), targets.map { it.displayId }.toSet(), true, null)
        } else if (mode == Prefs.APP_RESTART) {
            // Each app comes back on the screen it left; our own settings only lose their task, see the service's killOnly.
            host.kill(targets.map { it.packageName }.distinct(), targets.map { it.displayId }.toSet(), false) {
                targets.filter { it.packageName != context.packageName }.forEach { host.launchOn(it.packageName, it.displayId) }
            }
        } else {
            targets.forEach { host.goHome(it.displayId) }
        }
    }

    private fun label(packageName: String): String {
        val pm = context.packageManager
        return runCatching { pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString() }.getOrDefault(packageName)
    }

    private companion object {
        const val TAG = "BackButtonHero"
    }
}
