package dev.steinhorst.backbuttonhero.overlay

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import dev.steinhorst.backbuttonhero.prefs.Prefs

/**
 * What the service's small indicators share: a window context on the top display, which AYN's
 * game-assistant window never covers, and the nine-point grid of the controller-focus pictogram.
 */
object TopOverlayWindow {
    fun context(context: Context): Context? {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        return context.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
    }

    fun horizontalGravity(position: String?) = when (position) {
        Prefs.FOCUS_POS_TOP_LEFT, Prefs.FOCUS_POS_CENTER_LEFT, Prefs.FOCUS_POS_BOTTOM_LEFT -> Gravity.START
        Prefs.FOCUS_POS_TOP_RIGHT, Prefs.FOCUS_POS_CENTER_RIGHT, Prefs.FOCUS_POS_BOTTOM_RIGHT -> Gravity.END
        else -> Gravity.CENTER_HORIZONTAL
    }

    fun verticalGravity(position: String?) = when (position) {
        Prefs.FOCUS_POS_TOP_LEFT, Prefs.FOCUS_POS_TOP_CENTER, Prefs.FOCUS_POS_TOP_RIGHT -> Gravity.TOP
        Prefs.FOCUS_POS_BOTTOM_LEFT, Prefs.FOCUS_POS_BOTTOM_CENTER, Prefs.FOCUS_POS_BOTTOM_RIGHT -> Gravity.BOTTOM
        else -> Gravity.CENTER_VERTICAL
    }
}
