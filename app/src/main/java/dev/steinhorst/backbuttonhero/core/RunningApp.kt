package dev.steinhorst.backbuttonhero.core

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import dev.steinhorst.backbuttonhero.panels.PanelWindow

/** An app or a hidden bottom-screen panel as a Recents card; [key] tells two panels of one package apart. */
data class RunningApp(val packageName: String, val label: String, val icon: Drawable?, val thumbnail: Bitmap?, val panel: PanelWindow? = null) {
    val key: String get() = panel?.let { "@panel:${it.id}" } ?: packageName
}

/** Where the list of apps on a display comes from. */
interface AppSource {
    fun appsOnDisplay(displayId: Int): List<RunningApp>
    fun panelsOnDisplay(displayId: Int): List<RunningApp> = emptyList()
    fun forget(packageName: String)
}
