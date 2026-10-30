package dev.steinhorst.backbuttonhero

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Intent
import android.os.IBinder
import android.os.Looper
import android.view.Display

/** Root entry point: resume an existing task on the requested screen without relaunching its root. */
@SuppressLint("PrivateApi") // Task reparenting and moveTaskToFront have no public equivalents.
object RecentsTaskLauncher {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            if (Looper.myLooper() == null) Looper.prepareMainLooper()
            val pkg = args.getOrNull(0)
            val display = args.getOrNull(1)?.toIntOrNull()
            require(Shell.isPkg(pkg) && display != null && display >= 0)
            val expectedTask = args.getOrNull(2)?.toIntOrNull()
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "activity_task") as IBinder
            val atm = Class.forName("android.app.IActivityTaskManager\$Stub").getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)!!
            val user = ActivityManager::class.java.getMethod("getCurrentUser").invoke(null) as Int
            val tasks = RecentTaskQuery.read(atm, user, includeExcluded = true)
            val task = tasks.firstOrNull {
                it.taskId >= 0 && (expectedTask == null || it.taskId == expectedTask) &&
                    (it.baseIntent?.component ?: it.baseActivity)?.packageName == pkg
            } ?: return finish("ERR task unavailable", 1)
            val configuration = task.javaClass.getField("configuration").get(task)
            val windowConfiguration = configuration.javaClass.getField("windowConfiguration").get(configuration)
            val type = windowConfiguration.javaClass.getMethod("getActivityType").invoke(windowConfiguration) as Int
            if (!TaskMovePolicy.isMovable(type, task.baseIntent.hasCategory(Intent.CATEGORY_HOME),
                    task.baseIntent.hasCategory(Intent.CATEGORY_SECONDARY_HOME))) {
                return finish(TaskMovePolicy.PROTECTED_TASK, 1)
            }
            val oldDisplay = task.javaClass.getField("displayId").getInt(task)
            val front = atm.javaClass.methods.first { it.name == "moveTaskToFront" }
            var restoreSource = false
            var fallback: Int? = null
            if (oldDisplay != display) {
                val roots = atm.javaClass.getMethod("getAllRootTaskInfos").invoke(atm) as List<*>
                val sourceRoots = roots.filterNotNull().filter { info ->
                    info.javaClass.getField("displayId").getInt(info) == oldDisplay &&
                        info.javaClass.getField("topActivity").get(info) != null
                }
                val rootInfo = sourceRoots.firstOrNull { info ->
                    (info.javaClass.getField("childTaskIds").get(info) as IntArray).contains(task.taskId)
                }
                val foreground = sourceRoots.firstOrNull()?.let { it.javaClass.getField("taskId").getInt(it) }
                val appTasks = sourceRoots.filter { activityType(it) == 1 }.flatMap { info ->
                    (info.javaClass.getField("childTaskIds").get(info) as IntArray).reversed()
                }
                fallback = TaskMovePolicy.sourceFallback(task.taskId, foreground, appTasks)
                restoreSource = foreground == task.taskId || fallback != null
                // An evicted recent task has no live root to move; moveTaskToFront below restores
                // its existing task identity with the target display options.
                if (rootInfo != null) {
                    val root = rootInfo.javaClass.getField("taskId").getInt(rootInfo)
                    // Never move a shared root (home, split screen, etc.) along with unrelated tasks.
                    if (root != task.taskId ||
                        !(rootInfo.javaClass.getField("childTaskIds").get(rootInfo) as IntArray).contentEquals(intArrayOf(task.taskId))) {
                        return finish(TaskMovePolicy.PROTECTED_TASK, 1)
                    }
                    atm.javaClass.getMethod("moveRootTaskToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        .invoke(atm, root, display)
                }
            }
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle()
            // startActivityFromRecents raises default-display Home even for a background task or
            // a same-display bottom selection. Flags 0 bring only this task forward, without Home.
            front.invoke(atm, null, "android", task.taskId, 0, options)
            if (restoreSource) {
                // Recents may have interposed Home above the previous source app. Check after
                // activation, retaining an unrelated source foreground when a background task moves.
                val after = atm.javaClass.getMethod("getAllRootTaskInfos").invoke(atm) as List<*>
                val sourceFront = after.filterNotNull().firstOrNull { info ->
                    info.javaClass.getField("displayId").getInt(info) == oldDisplay &&
                        info.javaClass.getField("topActivity").get(info) != null
                }
                if (sourceFront == null || activityType(sourceFront) != 1) {
                    val restored = fallback != null && runCatching {
                        front.invoke(atm, null, "android", fallback, 0,
                            ActivityOptions.makeBasic().setLaunchDisplayId(oldDisplay).toBundle())
                    }.isSuccess
                    if (!restored && sourceFront == null) {
                        val category = if (oldDisplay == Display.DEFAULT_DISPLAY) Intent.CATEGORY_HOME else Intent.CATEGORY_SECONDARY_HOME
                        val home = ProcessBuilder("am", "start", "--display", oldDisplay.toString(),
                            "-a", Intent.ACTION_MAIN, "-c", category).redirectErrorStream(true).start()
                        home.inputStream.bufferedReader().use { it.readText() }
                        home.waitFor()
                    }
                }
            }
            finish("OK task=${task.taskId} from=$oldDisplay to=$display", 0)
        } catch (t: Throwable) {
            finish("ERR ${t.javaClass.simpleName}: ${t.cause?.message ?: t.message}", 1)
        }
    }

    private fun activityType(info: Any): Int {
        val configuration = info.javaClass.getField("configuration").get(info)
        val window = configuration.javaClass.getField("windowConfiguration").get(configuration)
        return window.javaClass.getMethod("getActivityType").invoke(window) as Int
    }

    private fun finish(line: String, code: Int) {
        println(line)
        System.out.flush()
        Runtime.getRuntime().halt(code)
    }
}
