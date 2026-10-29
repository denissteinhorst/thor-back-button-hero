package dev.steinhorst.backbuttonhero

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.os.IBinder
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import java.io.FileOutputStream
import android.os.Looper
import android.os.UserHandle
import android.util.EventLog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Reads system task/status data and exports system snapshots into caller-owned private cache files. */
@SuppressLint("PrivateApi") // Same ActivityTaskManager entry point as TaskRemover; no public task-list API.
object RecentsStateReader {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            if (Looper.myLooper() == null) Looper.prepareMainLooper()
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = activityThread.getMethod("systemMain").invoke(null)
            val context = activityThread.getMethod("getSystemContext").invoke(thread) as Context
            val currentUser = ActivityManager::class.java.getMethod("getCurrentUser").invoke(null) as Int
            val selectedTask = if (args.firstOrNull() == "selection") {
                val since = args.getOrNull(1)?.toLongOrNull() ?: error("selection time")
                val tag = EventLog.getTagCode("wm_task_to_front")
                require(tag >= 0 && since > 0)
                val events = mutableListOf<EventLog.Event>()
                EventLog.readEvents(intArrayOf(tag), events)
                events.asReversed().firstNotNullOfOrNull { event ->
                    val data = event.data as? Array<*>
                    if (event.timeNanos >= since && data?.getOrNull(0) == currentUser) data.getOrNull(1) as? Int else null
                } ?: -1
            } else null
            val handle = UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType).invoke(null, currentUser)
            val packageContext = Context::class.java.getMethod("createPackageContextAsUser", String::class.java,
                Int::class.javaPrimitiveType, UserHandle::class.java)
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "activity_task") as IBinder
            val atm = Class.forName("android.app.IActivityTaskManager\$Stub").getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)!!
            if (args.firstOrNull() == "snapshot") {
                val app = packageContext.invoke(context, "dev.steinhorst.backbuttonhero", 0, handle) as Context
                writeSnapshot(atm, app.cacheDir, args)
                return
            }
            val launcher = packageContext.invoke(context, "com.android.launcher3", 0, handle) as Context
            val lockFile = File(launcher.applicationInfo.dataDir, "shared_prefs/recents_lockedtasks.xml")
            val saved = if (lockFile.exists()) RecentsTaskState.lockKeys(lockFile.readText()) else emptySet()
            val method = atm.javaClass.methods.first { it.name == "getRecentTasks" }
            val parameters = method.parameterTypes.mapIndexed { index, type ->
                if (type == Int::class.javaPrimitiveType) (when (index) { 0 -> 500; 2 -> currentUser; else -> 0 }) else null
            }.toTypedArray()
            val raw = method.invoke(atm, *parameters)
            val tasks = raw as? List<*> ?: raw?.javaClass?.getMethod("getList")?.invoke(raw) as? List<*>
                ?: error("task list unavailable")
            val result = JSONArray()
            for (task in tasks) {
                val info = task as? ActivityManager.RecentTaskInfo ?: continue
                if (selectedTask != null && info.taskId != selectedTask) continue
                val component = info.baseIntent?.component ?: info.baseActivity ?: continue
                if (!Shell.isPkg(component.packageName)) continue
                val activity = runCatching { launcher.packageManager.getActivityInfo(component, 0) }.getOrNull()
                val name = activity?.taskAffinity?.takeIf { it.isNotEmpty() } ?: component.toString()
                val user = info.javaClass.getField("userId").getInt(info)
                val display = info.javaClass.getField("displayId").getInt(info)
                result.put(JSONObject().put("package", component.packageName).put("display", display)
                    .put("locked", "u: $user, $name" in saved).put("task", info.taskId)
                    .put("active", info.javaClass.getField("lastActiveTime").getLong(info)))
            }
            finish("OK $result", 0)
        } catch (t: Throwable) {
            finish("ERR ${t.javaClass.simpleName}: ${t.cause?.message ?: t.message}", 1)
        }
    }

    private fun writeSnapshot(atm: Any, cacheDir: File, args: Array<String>) {
        val taskId = args.getOrNull(1)?.toIntOrNull() ?: error("task id")
        val width = args.getOrNull(2)?.toIntOrNull() ?: error("width")
        require(taskId >= 0 && width in 1..1024)
        val destination = File(args.getOrNull(3) ?: error("destination"))
        // The app creates this private file first; root truncates it without changing its owner.
        require(destination.isFile && destination.canonicalFile.parentFile == cacheDir.canonicalFile &&
            destination.name.startsWith("task_snapshot_") && destination.name.endsWith(".png"))
        val method = atm.javaClass.methods.first { it.name == "getTaskSnapshot" }
        val parameters = method.parameterTypes.mapIndexed { index, type ->
            when (type) {
                Int::class.javaPrimitiveType -> taskId
                Boolean::class.javaPrimitiveType -> index > 1 // Full resolution; restore disk snapshot.
                else -> error("snapshot signature")
            }
        }.toTypedArray<Any>()
        val snapshot = method.invoke(atm, *parameters) ?: error("snapshot unavailable")
        val buffer = snapshot.javaClass.getMethod("getHardwareBuffer").invoke(snapshot) as? HardwareBuffer
            ?: error("snapshot buffer unavailable")
        try {
            val colorSpace = snapshot.javaClass.getMethod("getColorSpace").invoke(snapshot) as ColorSpace
            val hardware = Bitmap.wrapHardwareBuffer(buffer, colorSpace) ?: error("snapshot bitmap unavailable")
            try {
                val software = hardware.copy(Bitmap.Config.ARGB_8888, false) ?: error("snapshot copy unavailable")
                try {
                    val targetWidth = minOf(width, software.width)
                    val targetHeight = (software.height.toLong() * targetWidth / software.width).toInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(software, targetWidth, targetHeight, true)
                    try {
                        FileOutputStream(destination).use { require(scaled.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    } finally { if (scaled !== software) scaled.recycle() }
                } finally { software.recycle() }
            } finally { hardware.recycle() }
        } finally { buffer.close() }
        finish("OK snapshot", 0)
    }

    private fun finish(line: String, code: Int) {
        println(line)
        System.out.flush()
        Runtime.getRuntime().halt(code)
    }
}
