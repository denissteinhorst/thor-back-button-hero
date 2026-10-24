package dev.steinhorst.backbuttonhero

import android.graphics.Bitmap
import android.graphics.drawable.Drawable

data class RunningApp(val packageName: String, val label: String, val icon: Drawable?, val thumbnail: Bitmap?)

/** Where the list of apps on a display comes from. */
interface AppSource {
    fun appsOnDisplay(displayId: Int): List<RunningApp>
    fun forget(packageName: String)
}
