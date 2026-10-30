package dev.steinhorst.backbuttonhero

import android.app.ActivityManager

/** Bottom history includes observed apps that opt out of native Recents, such as companion apps. */
object RecentTaskQuery {
    fun read(manager: Any, user: Int, includeExcluded: Boolean = false): List<ActivityManager.RecentTaskInfo> {
        require(user >= 0)
        val method = manager.javaClass.methods.first { it.name == "getRecentTasks" }
        val parameters = method.parameterTypes.mapIndexed { index, type ->
            if (type == Int::class.javaPrimitiveType) when (index) {
                0 -> 500
                1 -> if (includeExcluded) ActivityManager.RECENT_WITH_EXCLUDED else 0
                2 -> user
                else -> 0
            } else null
        }.toTypedArray()
        val raw = method.invoke(manager, *parameters)
        val tasks = raw as? List<*> ?: raw?.javaClass?.getMethod("getList")?.invoke(raw) as? List<*>
            ?: error("task list unavailable")
        return tasks.filterIsInstance<ActivityManager.RecentTaskInfo>()
    }
}
