package dev.steinhorst.backbuttonhero

import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityWindowInfo

/**
 * A normal app cannot read other apps' task list, so this builds a most-recently-used list from the
 * windows the accessibility service sees on each display. Only apps seen since the service started appear.
 */
class AccessibilityAppSource(private val service: AccessibilityService) : AppSource {

    private val history = mutableMapOf<Int, MutableList<String>>()

    private val thumbnails = mutableMapOf<String, Bitmap>()

    private val ignored = setOf(
        "com.android.systemui",
        "com.odin.dualscreen.assistant",
        "com.odin.odinlauncher",
        "com.android.launcher3",
    )

    /** Call on window changes; moves the apps currently visible on [displayId] to the front. */
    fun record(displayId: Int) {
        val windows = service.windowsOnAllDisplays.get(displayId) ?: return
        val visible = windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root?.packageName?.toString() }
            .filter { it != service.packageName && it !in ignored }
        val set = history.getOrPut(displayId) { mutableListOf() }
        visible.asReversed().forEach { set.remove(it); set.add(0, it) }
    }

    /**
     * True when a non-app window (e.g. a Presentation another app draws) fills [displayId] above the apps,
     * so an app launched there would be hidden and a screenshot would show that window instead.
     */
    fun isCovered(displayId: Int): Boolean {
        val display = service.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(displayId) ?: return false
        val size = android.graphics.Point().also { display.getRealSize(it) }
        val screenArea = size.x.toLong() * size.y
        val rect = android.graphics.Rect()
        return service.windowsOnAllDisplays.get(displayId).orEmpty().any { w ->
            val type = w.type
            if (type == AccessibilityWindowInfo.TYPE_APPLICATION || type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return@any false
            w.getBoundsInScreen(rect)
            rect.width().toLong() * rect.height() >= screenArea * 9 / 10
        }
    }

    /** The app in front on [displayId], or null when only a launcher/system UI is showing. */
    fun topApp(displayId: Int): String? =
        service.windowsOnAllDisplays.get(displayId).orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }
            .mapNotNull { it.root?.packageName?.toString() }
            .firstOrNull { it != service.packageName && it !in ignored }

    fun saveThumbnail(pkg: String, bitmap: Bitmap) {
        thumbnails[pkg]?.recycle()
        thumbnails[pkg] = bitmap
    }

    override fun forget(packageName: String) {
        history.values.forEach { it.remove(packageName) }
        thumbnails.remove(packageName)?.recycle()
    }

    /** A successful system snapshot can expire observed history, but never adds unobserved apps. */
    fun retainRecentTasks(tasks: List<RecentsTaskState>) {
        val packages = tasks.map { it.packageName }.toSet()
        val expired = history.values.flatten().toSet() - packages
        expired.forEach(::forget)
    }

    override fun appsOnDisplay(displayId: Int): List<RunningApp> {
        record(displayId)
        val pm = service.packageManager
        return history[displayId].orEmpty().map { RunningApp(it, labelOf(pm, it), iconOf(pm, it), thumbnails[it]) }
    }

    private fun iconOf(pm: PackageManager, pkg: String) = try {
        pm.getApplicationIcon(pkg)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun labelOf(pm: PackageManager, pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }
}
