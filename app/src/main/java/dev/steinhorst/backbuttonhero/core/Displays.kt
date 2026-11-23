package dev.steinhorst.backbuttonhero.core

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * The one rule for which display is which screen. The top screen is always [Display.DEFAULT_DISPLAY];
 * the bottom screen is discovered, because its id is not fixed (4 on the Thor today). Call from any
 * thread; nothing is cached, so a display that comes or goes is seen on the next call.
 */
object Displays {
    /** Private displays are only ever this app's own (the parking display); none is a screen the user can see. */
    fun isPrivate(display: Display): Boolean = display.flags and Display.FLAG_PRIVATE != 0

    /** The bottom screen: the non-default display with the highest id that is not private; null if absent. */
    fun bottom(context: Context): Display? =
        context.getSystemService(DisplayManager::class.java).displays
            .filter { it.displayId != Display.DEFAULT_DISPLAY && !isPrivate(it) }
            .maxByOrNull { it.displayId }
}
