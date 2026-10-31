package dev.steinhorst.backbuttonhero

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Intent
import android.os.IBinder
import android.os.Looper

/**
 * Root entry point: moves a frontend's standalone bottom task onto the private parking display and
 * back. Running root tasks are read directly, since recent-task queries may omit private displays.
 */
@SuppressLint("PrivateApi") // Task reparenting and moveTaskToFront have no public equivalents.
object TaskParker {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            if (Looper.myLooper() == null) Looper.prepareMainLooper()
            val pkg = args.getOrNull(1)
            require(Shell.isPkg(pkg))
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "activity_task") as IBinder
            val atm = Class.forName("android.app.IActivityTaskManager\$Stub").getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)!!
            val roots = (atm.javaClass.getMethod("getAllRootTaskInfos").invoke(atm) as List<*>).filterNotNull()
            when (args.firstOrNull()) {
                "park" -> {
                    val from = args.getOrNull(2)?.toIntOrNull()
                    val to = args.getOrNull(3)?.toIntOrNull()
                    require(from != null && from > 0 && to != null && to > 0 && from != to)
                    // Only the visible front task is a frontend surface; a covered instance is left alone.
                    val front = roots.firstOrNull { display(it) == from && it.javaClass.getField("topActivity").get(it) != null }
                    if (front == null || owner(front) != pkg) return finish("ERR not front", 1)
                    if (!standalone(front)) return finish(TaskMovePolicy.PROTECTED_TASK, 1)
                    val task = taskId(front)
                    move(atm, task, to)
                    finish("OK task=$task", 0)
                }
                "restore" -> {
                    val task = args.getOrNull(2)?.toIntOrNull()
                    val to = args.getOrNull(3)?.toIntOrNull()
                    require(task != null && task >= 0 && to != null && to >= 0)
                    val root = roots.firstOrNull { taskId(it) == task && owner(it) == pkg } ?: return finish("ERR task unavailable", 1)
                    if (!standalone(root)) return finish(TaskMovePolicy.PROTECTED_TASK, 1)
                    if (display(root) != to) move(atm, task, to)
                    val options = ActivityOptions.makeBasic().setLaunchDisplayId(to).toBundle()
                    atm.javaClass.methods.first { it.name == "moveTaskToFront" }.invoke(atm, null, "android", task, 0, options)
                    finish("OK task=$task", 0)
                }
                "query" -> {
                    val task = args.getOrNull(2)?.toIntOrNull()
                    require(task != null && task >= 0)
                    val root = roots.firstOrNull { taskId(it) == task && owner(it) == pkg } ?: return finish("ERR task unavailable", 1)
                    finish("OK display=${display(root)}", 0)
                }
                else -> error("unknown parking command")
            }
        } catch (t: Throwable) {
            finish("ERR ${t.javaClass.simpleName}: ${t.cause?.message ?: t.message}", 1)
        }
    }

    private fun move(atm: Any, task: Int, display: Int) {
        atm.javaClass.getMethod("moveRootTaskToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(atm, task, display)
    }

    /** Never move a shared root (home, split screen, etc.) along with unrelated tasks. */
    private fun standalone(info: Any): Boolean {
        val children = info.javaClass.getField("childTaskIds").get(info) as IntArray
        val intent = info.javaClass.getField("baseIntent").get(info) as? Intent ?: return false
        val configuration = info.javaClass.getField("configuration").get(info)
        val window = configuration.javaClass.getField("windowConfiguration").get(configuration)
        val type = window.javaClass.getMethod("getActivityType").invoke(window) as Int
        return children.contentEquals(intArrayOf(taskId(info))) && TaskMovePolicy.isMovable(type,
            intent.hasCategory(Intent.CATEGORY_HOME), intent.hasCategory(Intent.CATEGORY_SECONDARY_HOME))
    }

    private fun owner(info: Any): String? {
        val intent = info.javaClass.getField("baseIntent").get(info) as? Intent
        val base = info.javaClass.getField("baseActivity").get(info) as? android.content.ComponentName
        return (intent?.component ?: base)?.packageName
    }

    private fun taskId(info: Any) = info.javaClass.getField("taskId").getInt(info)

    private fun display(info: Any) = info.javaClass.getField("displayId").getInt(info)

    private fun finish(line: String, code: Int) {
        println(line)
        System.out.flush()
        Runtime.getRuntime().halt(code)
    }
}
