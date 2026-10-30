package dev.steinhorst.backbuttonhero

import android.graphics.Bitmap
import android.graphics.drawable.Drawable

data class RunningApp(val packageName: String, val label: String, val icon: Drawable?, val thumbnail: Bitmap?, val panel: PanelWindow? = null) {
    val key: String get() = panel?.let { "@panel:${it.id}" } ?: packageName
}

/** Where the list of apps on a display comes from. */
interface AppSource {
    fun appsOnDisplay(displayId: Int): List<RunningApp>
    fun panelsOnDisplay(displayId: Int): List<RunningApp> = emptyList()
    fun forget(packageName: String)
}
