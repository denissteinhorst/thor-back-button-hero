package dev.steinhorst.backbuttonhero.actions

import android.content.Context
import android.os.Handler
import android.util.Log
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.core.PService
import dev.steinhorst.backbuttonhero.overlay.NoticeOverlay

/**
 * Brings back the app used before the current one on the controller's screen; see [LastApp]. With
 * root the existing task returns on that screen, skipping apps that moved to the other one; without
 * root a plain launch still brings an existing task back.
 */
class LastAppAction(
    private val context: Context,
    private val handler: Handler,
    private val notice: NoticeOverlay,
    private val host: Host,
) {
    interface Host {
        fun controllerDisplayId(): Int
        fun appsOnDisplay(displayId: Int): List<String>
        fun topApp(displayId: Int): String?
        fun forget(packageName: String)
        fun launchOn(packageName: String, displayId: Int)
        fun unavailable()
    }

    fun run() {
        val display = host.controllerDisplayId()
        val candidates = LastApp.candidates(host.appsOnDisplay(display), host.topApp(display), context.packageName)
        if (candidates.isEmpty()) return notice.show(R.string.last_app_none)
        if (!PService.cachedAvailable()) {
            // Without root the task cannot be checked; the launch still brings an existing one back.
            runCatching { host.launchOn(candidates.first(), display) }.onFailure { host.unavailable() }
            return
        }
        Thread {
            // History is per screen but never drops an app that moved to the other one; the launcher
            // refuses those, so the next app of this screen becomes the last app instead.
            val attempt = candidates.firstNotNullOfOrNull { pkg ->
                val result = PService.runEntryPoint(context, "RecentsTaskLauncher", pkg, display.toString(), LastApp.SAME_DISPLAY)
                Log.d(TAG, "Last app $pkg on $display: $result")
                if (result == LastApp.OTHER_DISPLAY) null else pkg to result
            }
            handler.post {
                val (previous, result) = attempt ?: return@post notice.show(R.string.last_app_none)
                when {
                    result?.startsWith("OK ") == true -> Unit
                    result == LastApp.TASK_GONE -> {
                        host.forget(previous)
                        notice.show(context.getString(R.string.last_app_gone, label(previous)))
                    }
                    else -> notice.show(R.string.swap_unavailable, long = true)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun label(pkg: String): String {
        val pm = context.packageManager
        return runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() }.getOrDefault(pkg)
    }

    private companion object {
        const val TAG = "BackButtonHero"
    }
}
