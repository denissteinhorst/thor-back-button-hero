package dev.steinhorst.backbuttonhero

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Intent
import android.os.IBinder
import android.os.Looper

/** Root entry point: resume an existing task on the requested screen without relaunching its root. */
@SuppressLint("PrivateApi") // Task reparenting and startActivityFromRecents have no public equivalents.
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
            val recent = atm.javaClass.methods.first { it.name == "getRecentTasks" }
            val raw = recent.invoke(atm, 500, 0, user)
            val tasks = raw as? List<*> ?: raw?.javaClass?.getMethod("getList")?.invoke(raw) as? List<*>
                ?: error("task list unavailable")
            val task = tasks.filterIsInstance<ActivityManager.RecentTaskInfo>().firstOrNull {
                it.taskId >= 0 && (expectedTask == null || it.taskId == expectedTask) &&
                    (it.baseIntent?.component ?: it.baseActivity)?.packageName == pkg
            } ?: return finish("ERR task unavailable", 1)
            require(!task.baseIntent.hasCategory(Intent.CATEGORY_HOME) &&
                !task.baseIntent.hasCategory(Intent.CATEGORY_SECONDARY_HOME)) { "launcher task" }
            val configuration = task.javaClass.getField("configuration").get(task)
            val windowConfiguration = configuration.javaClass.getField("windowConfiguration").get(configuration)
            require(windowConfiguration.javaClass.getMethod("getActivityType").invoke(windowConfiguration) == 1) {
                "non-app task"
            }
            val oldDisplay = task.javaClass.getField("displayId").getInt(task)
            if (oldDisplay != display) {
                val roots = atm.javaClass.getMethod("getAllRootTaskInfos").invoke(atm) as List<*>
                val rootInfo = roots.filterNotNull().first { info ->
                    (info.javaClass.getField("childTaskIds").get(info) as IntArray).contains(task.taskId)
                }
                val root = rootInfo.javaClass.getField("taskId").getInt(rootInfo)
                // Never move a shared root (home, split screen, etc.) along with unrelated tasks.
                require(root == task.taskId &&
                    (rootInfo.javaClass.getField("childTaskIds").get(rootInfo) as IntArray).contentEquals(intArrayOf(task.taskId))) {
                    "shared root task"
                }
                atm.javaClass.getMethod("moveRootTaskToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(atm, root, display)
            }
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle()
            atm.javaClass.getMethod("startActivityFromRecents", Int::class.javaPrimitiveType, android.os.Bundle::class.java)
                .invoke(atm, task.taskId, options)
            finish("OK task=${task.taskId} from=$oldDisplay to=$display", 0)
        } catch (t: Throwable) {
            finish("ERR ${t.javaClass.simpleName}: ${t.cause?.message ?: t.message}", 1)
        }
    }

    private fun finish(line: String, code: Int) {
        println(line)
        System.out.flush()
        Runtime.getRuntime().halt(code)
    }
}
