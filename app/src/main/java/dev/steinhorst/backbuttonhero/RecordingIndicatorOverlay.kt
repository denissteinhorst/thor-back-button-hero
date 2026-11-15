package dev.steinhorst.backbuttonhero

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * A small blinking red dot while a recording runs, which turns into a steady microphone while the
 * push-to-talk button is held. It never takes touch, key or controller focus.
 */
class RecordingIndicatorOverlay(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var shown: Pair<WindowManager, Indicator>? = null
    private var blink: ObjectAnimator? = null
    private var shownPosition: String? = null

    /** Shows the dot at [position] on the controller-focus pictogram's grid until [dismiss]. */
    fun show(position: String) {
        handler.removeCallbacksAndMessages(null)
        if (shown != null && shownPosition == position) return
        dismiss()
        // AYN's game-assistant window can cover accessibility overlays on the bottom display.
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val windowContext = context.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        val wm = windowContext.getSystemService(WindowManager::class.java)
        val density = windowContext.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val size = dp(SIZE_DP)
        val view = Indicator(windowContext).apply {
            contentDescription = windowContext.getString(R.string.record_indicator_overlay)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val horizontal = when (position) {
            Prefs.FOCUS_POS_TOP_LEFT, Prefs.FOCUS_POS_CENTER_LEFT, Prefs.FOCUS_POS_BOTTOM_LEFT -> Gravity.START
            Prefs.FOCUS_POS_TOP_RIGHT, Prefs.FOCUS_POS_CENTER_RIGHT, Prefs.FOCUS_POS_BOTTOM_RIGHT -> Gravity.END
            else -> Gravity.CENTER_HORIZONTAL
        }
        val vertical = when (position) {
            Prefs.FOCUS_POS_TOP_LEFT, Prefs.FOCUS_POS_TOP_CENTER, Prefs.FOCUS_POS_TOP_RIGHT -> Gravity.TOP
            Prefs.FOCUS_POS_BOTTOM_LEFT, Prefs.FOCUS_POS_BOTTOM_CENTER, Prefs.FOCUS_POS_BOTTOM_RIGHT -> Gravity.BOTTOM
            else -> Gravity.CENTER_VERTICAL
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = horizontal or vertical
            // Measured from the physical edges, like the controller-focus pictogram.
            fitInsetsTypes = 0
            if (horizontal != Gravity.CENTER_HORIZONTAL) x = dp(MARGIN_DP)
            if (vertical != Gravity.CENTER_VERTICAL) y = dp(MARGIN_DP)
        }
        runCatching {
            wm.addView(view, params)
            shown = wm to view
            shownPosition = position
            startBlink(view)
        }
    }

    private fun startBlink(view: View) {
        blink = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.2f).apply {
            duration = BLINK_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    /** A held push-to-talk button shows a steady microphone; releasing it brings the blinking dot back. */
    fun setTalking(talking: Boolean) {
        val view = shown?.second ?: return
        if (view.talking == talking) return
        view.talking = talking
        view.contentDescription = context.getString(if (talking) R.string.record_indicator_talking else R.string.record_indicator_overlay)
        blink?.cancel()
        blink = null
        if (talking) view.alpha = 1f else startBlink(view)
        view.invalidate()
    }

    /** Shows the dot for a moment, e.g. while its position is being chosen. */
    fun preview(position: String) {
        show(position)
        handler.postDelayed(::dismiss, PREVIEW_MS)
    }

    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        blink?.cancel()
        blink = null
        shown?.let { (wm, view) -> runCatching { wm.removeViewImmediate(view) } }
        shown = null
        shownPosition = null
    }

    /** Drawn by hand so the dot and the microphone share one small window. */
    private class Indicator(context: Context) : View(context) {
        var talking = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RED }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = RED
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val shape = RectF()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (!talking) {
                canvas.drawCircle(w / 2, h / 2, w * DOT_FRACTION / 2, paint)
                return
            }
            // A capsule on a cradle with a short stand.
            stroke.strokeWidth = w * 0.09f
            shape.set(w * 0.36f, h * 0.06f, w * 0.64f, h * 0.58f)
            canvas.drawRoundRect(shape, w * 0.14f, w * 0.14f, paint)
            shape.set(w * 0.22f, h * 0.22f, w * 0.78f, h * 0.74f)
            canvas.drawArc(shape, 0f, 180f, false, stroke)
            canvas.drawLine(w / 2, h * 0.74f, w / 2, h * 0.92f, stroke)
            canvas.drawLine(w * 0.36f, h * 0.92f, w * 0.64f, h * 0.92f, stroke)
        }
    }

    companion object {
        private val RED = Color.rgb(0xE5, 0x39, 0x35)
        /** The window fits the microphone; the dot keeps its earlier 14 dp inside it. */
        private const val SIZE_DP = 20
        private const val DOT_FRACTION = 0.7f
        private const val MARGIN_DP = 17
        private const val BLINK_MS = 600L
        private const val PREVIEW_MS = 1_500L
    }
}
