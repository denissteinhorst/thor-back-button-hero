package dev.steinhorst.backbuttonhero.recents

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.text.format.Formatter
import android.util.Log
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.core.PService
import dev.steinhorst.backbuttonhero.core.Shell
import dev.steinhorst.backbuttonhero.overlay.NoticeOverlay

/** Closing apps and moving their existing tasks, with the unprivileged fallbacks when root fails. */
class TaskOps(private val context: Context, private val handler: Handler, private val notice: NoticeOverlay, private val host: Host) {
    interface Host {
        fun topApp(displayId: Int): String?
        fun forgetParked(packageName: String)
        fun goHome(displayId: Int)
        fun launchOn(packageName: String, displayId: Int)
    }

    /** Only the unprivileged fallback lacks task identity; never relaunch a potential launcher there. */
    fun canRelaunch(pkg: String): Boolean = Shell.isPkg(pkg) &&
        listOf(Intent.CATEGORY_HOME, Intent.CATEGORY_SECONDARY_HOME).none { category ->
            context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(pkg), 0).isNotEmpty()
        }

    /** Runs on a worker; swap moves must use one ordered worker rather than competing launches. */
    fun launch(pkg: String, displayId: Int, taskId: Int?, allowRelaunch: Boolean): Boolean {
        val args = listOfNotNull(pkg, displayId.toString(), taskId?.toString()).toTypedArray()
        val result = PService.runEntryPoint(context, "RecentsTaskLauncher", *args)
        Log.d(TAG, "Recents launch: $result")
        val launched = result?.startsWith("OK ") == true
        if (!launched) {
            if (allowRelaunch && TaskMovePolicy.mayRelaunch(result, canRelaunch(pkg))) {
                handler.post { runCatching { host.launchOn(pkg, displayId) }.onFailure { Log.e(TAG, "Recents launch failed", it) } }
                return true
            }
            handler.post { notice.show(R.string.swap_unavailable, long = true) }
        }
        return launched
    }

    /** A normal app can only kill background processes, so leave the app first. With AYN's root
     *  service reachable ([PService]) the task is also removed from Recents and the app force-stopped;
     *  if that fails, the unprivileged kill below still runs. Our own package only loses its tasks:
     *  this process hosts the service, so stopping it would end every gesture. With [reportMemory]
     *  the released memory is shown afterwards, as the stock launcher's Clear all does. */
    fun kill(closing: List<String>, displays: Set<Int>, reportMemory: Boolean, afterwards: (() -> Unit)?) {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        // Measured before the app leaves the screen, so the whole release is counted.
        val availableBefore = if (reportMemory) RecentsMemory.read(context).available else null
        val closesOwn = context.packageName in closing
        val packages = closing - context.packageName
        packages.forEach(host::forgetParked)
        packages.forEach { pkg ->
            displays.forEach { d -> if (host.topApp(d) == pkg) host.goHome(d) }
        }
        val rootable = if (PService.cachedAvailable()) packages.filter(Shell::isPkg) else emptyList()
        handler.postDelayed({
            if (closesOwn) activityManager.appTasks.forEach { it.finishAndRemoveTask() }
            if (rootable.isEmpty()) {
                packages.forEach { activityManager.killBackgroundProcesses(it) }
                availableBefore?.let(::reportReleasedMemory)
                afterwards?.let { handler.postDelayed(it, RESTART_DELAY_MS) }
                return@postDelayed
            }
            Thread {
                val removed = PService.runEntryPoint(context, "TaskRemover", rootable.joinToString(","))
                val stopped = rootable.map { PService.forceStop(it) }
                if (removed?.startsWith("OK") != true || !stopped.all { it }) {
                    handler.post { packages.forEach { activityManager.killBackgroundProcesses(it) } }
                }
                availableBefore?.let { before -> handler.post { reportReleasedMemory(before) } }
                afterwards?.let { handler.postDelayed(it, RESTART_DELAY_MS) }
            }.start()
        }, 500)
    }

    /** The kernel hands memory back with a short lag; the stock launcher measures after the same wait. */
    private fun reportReleasedMemory(availableBefore: Long) {
        handler.postDelayed({
            val available = RecentsMemory.read(context).available
            val released = RecentsMemory.released(availableBefore, available)
            fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes).uppercase()
            notice.show(
                if (released > 0) context.getString(R.string.memory_released, size(released), size(available))
                else context.getString(R.string.memory_nothing_released),
            )
        }, MEMORY_REPORT_DELAY_MS)
    }

    companion object {
        private const val TAG = "BackButtonHero"
        const val RESTART_DELAY_MS = 400L
        const val MEMORY_REPORT_DELAY_MS = 600L
    }
}
