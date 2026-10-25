package dev.steinhorst.backbuttonhero

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Fallback for Back + D-pad when [PadHelper] is not running (no pservice). The D-pad is a hat axis,
 * which an accessibility service never sees; joystick events only go to the focused window. So while
 * Back is held, an invisible 1x1 focusable overlay sits on every display and takes the focus - and
 * with it the D-pad. The price: the app in front loses window focus for that moment, and many games
 * and emulators pause on that. The settings screen warns about it.
 */
class DpadOverlay(private val context: Context, private val onPress: (DpadDirection) -> Unit) {
    private val windows = mutableListOf<Pair<WindowManager, View>>()
    private var lastX = 0f
    private var lastY = 0f

    fun attach() {
        if (windows.isNotEmpty()) return
        lastX = 0f
        lastY = 0f
        for (display in context.getSystemService(DisplayManager::class.java).displays) {
            val ctx = context.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            val wm = ctx.getSystemService(WindowManager::class.java)
            val view = object : View(ctx) {
                override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                    val x = event.getAxisValue(MotionEvent.AXIS_HAT_X)
                    val y = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
                    // Only the edge from rest to a direction counts, so one press fires once.
                    val pressed = DpadDirection.fromHat(true, x).takeIf { DpadDirection.fromHat(true, lastX) == null }
                        ?: DpadDirection.fromHat(false, y).takeIf { DpadDirection.fromHat(false, lastY) == null }
                    lastX = x
                    lastY = y
                    pressed?.let(onPress)
                    return true
                }

                // Swallow anything else that lands here while it has focus (it only exists while Back is held).
                override fun dispatchKeyEvent(event: KeyEvent): Boolean = true
            }.apply {
                isFocusable = true
                isFocusableInTouchMode = true
            }
            val params = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }
            runCatching {
                wm.addView(view, params)
                windows += wm to view
                view.requestFocus()
            }
        }
    }

    fun detach() {
        windows.forEach { (wm, view) -> runCatching { wm.removeView(view) } }
        windows.clear()
    }
}
