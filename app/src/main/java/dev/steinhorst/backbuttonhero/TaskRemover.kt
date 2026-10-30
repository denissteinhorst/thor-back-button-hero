package dev.steinhorst.backbuttonhero

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.os.IBinder

/**
 * Entry point run as root in a bare VM via `app_process` (see [PService.runEntryPoint]), not inside
 * the app. Removes the recent tasks of the given packages the way Recents' "Clear all" does, over the
 * ActivityTaskManager binder - something no unprivileged app may call.
 *
 * Usage: `TaskRemover "pkgA,pkgB"`. Prints "OK removed=N" or "ERR <reason>".
 */
@SuppressLint("PrivateApi") // hidden IActivityTaskManager, reached by reflection as no SDK exposes it
object TaskRemover {
    @JvmStatic
    fun main(args: Array<String>) {
        val targets = (args.getOrNull(0) ?: "").split(",").filter { Shell.isPkg(it) }.toSet()
        try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "activity_task") as? IBinder ?: return finish("ERR activity_task unavailable", 1)
            val atm = Class.forName("android.app.IActivityTaskManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)!!

            val removeTask = atm.javaClass.methods.firstOrNull { it.name == "removeTask" && it.parameterTypes.size == 1 }
                ?: return finish("ERR removeTask missing", 1)

            val user = ActivityManager::class.java.getMethod("getCurrentUser").invoke(null) as Int
            val tasks = RecentTaskQuery.read(atm, user, includeExcluded = true)

            var removed = 0
            for (info in tasks) {
                val pkg = info.baseActivity?.packageName ?: info.topActivity?.packageName
                    ?: info.baseIntent?.component?.packageName
                if (info.taskId < 0 || pkg !in targets) continue
                try {
                    removeTask.invoke(atm, info.taskId)
                    removed++
                } catch (_: Throwable) {
                    // One stubborn task must not stop the rest.
                }
            }
            finish("OK removed=$removed", 0)
        } catch (t: Throwable) {
            finish("ERR ${t.javaClass.simpleName}: ${t.cause?.message ?: t.message}", 1)
        }
    }

    // halt, not exit: app_process would otherwise wait on the binder threads that reflection started.
    private fun finish(line: String, code: Int) {
        println(line)
        System.out.flush()
        Runtime.getRuntime().halt(code)
    }
}
