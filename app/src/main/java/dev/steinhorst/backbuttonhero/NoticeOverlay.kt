package dev.steinhorst.backbuttonhero

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * The service's short messages, drawn like a toast. Android rate-limits toasts from apps in the
 * background, which an accessibility service always is, and silently drops them after a few quick
 * hotkeys. This is one window that only changes its text, so every message gets through. Like the
 * focus pictogram it uses the top display, which AYN's assistant never covers.
 */
class NoticeOverlay(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var shown: Pair<WindowManager, TextView>? = null

    fun show(text: CharSequence, long: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { show(text, long) }
            return
        }
        // One log line per message, so a two-line notice stays one entry for the on-device suite.
        Log.i(TAG, "Notice: " + text.toString().replace("\n", "\\n"))
        handler.removeCallbacksAndMessages(null)
        val view = shown?.second ?: add() ?: return
        view.text = text
        view.announceForAccessibility(text)
        view.animate().cancel()
        view.animate().alpha(1f).setDuration(FADE_MS).start()
        handler.postDelayed(::hide, if (long) LONG_MS else SHORT_MS)
    }

    fun show(textRes: Int, long: Boolean = false) = show(context.getString(textRes), long)

    private fun add(): TextView? {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val windowContext = context.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        val wm = windowContext.getSystemService(WindowManager::class.java)
        val density = windowContext.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val view = TextView(windowContext).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            maxWidth = dp(MAX_WIDTH_DP)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(BACKGROUND)
                cornerRadius = dp(22).toFloat()
            }
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            alpha = 0f
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(BOTTOM_DP)
            title = WINDOW_TITLE
        }
        return runCatching {
            wm.addView(view, params)
            shown = wm to view
            view
        }.getOrNull()
    }

    private fun hide() {
        shown?.second?.animate()?.alpha(0f)?.setDuration(FADE_MS)?.withEndAction(::dismiss)?.start()
    }

    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        shown?.let { (wm, view) ->
            view.animate().cancel()
            runCatching { wm.removeViewImmediate(view) }
        }
        shown = null
    }

    companion object {
        private const val TAG = "BackButtonHero"
        /** The window's name in `dumpsys window`, which the on-device suite looks for. */
        const val WINDOW_TITLE = "BackButtonHeroNotice"
        // Android's own toast durations.
        private const val SHORT_MS = 2_000L
        private const val LONG_MS = 3_500L
        private const val FADE_MS = 150L
        private const val BOTTOM_DP = 64
        private const val MAX_WIDTH_DP = 360
        private const val BACKGROUND = 0xE6323232.toInt()
    }
}
