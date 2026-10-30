package dev.steinhorst.backbuttonhero

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecentTaskQueryTest {
    class Slice(private val tasks: List<ActivityManager.RecentTaskInfo>) {
        fun getList() = tasks
    }

    class TaskManager(private val slice: Boolean = true) {
        var tasks = listOf(task("dev.example.game", 51, false), task("dev.example.companion", 49, true))
        var lastQuery: Triple<Int, Int, Int>? = null
        fun getRecentTasks(max: Int, flags: Int, user: Int): Any {
            lastQuery = Triple(max, flags, user)
            val result = tasks.filter {
                flags and ActivityManager.RECENT_WITH_EXCLUDED != 0 ||
                    it.baseIntent.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS == 0
            }
            return if (slice) Slice(result) else result
        }
    }

    class BrokenManager {
        @Suppress("UNUSED_PARAMETER")
        fun getRecentTasks(max: Int, flags: Int, user: Int): Any? = null
    }

    @Test
    fun `native recents keeps its default exclusion while bottom inventory includes opted out tasks for the current user`() {
        val manager = TaskManager()
        assertEquals(listOf(51), RecentTaskQuery.read(manager, 10).map { it.taskId })
        assertEquals(Triple(500, 0, 10), manager.lastQuery)
        assertEquals(listOf(51, 49), RecentTaskQuery.read(manager, 10, includeExcluded = true).map { it.taskId })
        assertEquals(Triple(500, ActivityManager.RECENT_WITH_EXCLUDED, 10), manager.lastQuery)
    }

    @Test
    fun `excluded companion stays in observed history while alive and is pruned when its task actually ends`() {
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        val source = AccessibilityAppSource(service)
        @Suppress("UNCHECKED_CAST")
        val history = AccessibilityAppSource::class.java.getDeclaredField("history").apply { isAccessible = true }
            .get(source) as MutableMap<Int, MutableList<String>>
        history[17] = mutableListOf("dev.example.companion", "dev.example.game")
        val manager = TaskManager()
        fun refresh() = source.retainRecentTasks(RecentTaskQuery.read(manager, 0, includeExcluded = true).map {
            RecentsTaskState(requireNotNull(it.baseIntent.component).packageName, 17, false, it.taskId)
        })
        refresh()
        assertEquals(listOf("dev.example.companion", "dev.example.game"), history[17])
        manager.tasks = manager.tasks.filterNot { it.taskId == 49 }
        refresh()
        assertEquals(listOf("dev.example.game"), history[17])
    }

    @Test
    fun `plain list firmware responses include excluded tasks too`() {
        assertEquals(listOf(51, 49), RecentTaskQuery.read(TaskManager(slice = false), 0, true).map { it.taskId })
    }

    @Test
    fun `unavailable task inventory fails rather than pretending every app has closed`() {
        assertThrows(IllegalStateException::class.java) { RecentTaskQuery.read(BrokenManager(), 0, true) }
        assertTrue(RecentTaskQuery.read(TaskManager().apply { tasks = emptyList() }, 0, true).isEmpty())
    }

    companion object {
        private fun task(pkg: String, id: Int, excluded: Boolean) = ActivityManager.RecentTaskInfo().apply {
            taskId = id
            baseIntent = Intent(Intent.ACTION_MAIN).setComponent(ComponentName(pkg, "$pkg.MainActivity")).apply {
                if (excluded) addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
        }
    }
}
