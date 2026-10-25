package dev.steinhorst.backbuttonhero

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView

/** A brief, display-specific confirmation that never takes touch, key or controller focus. */
class FocusIndicatorOverlay(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val shown = mutableListOf<Pair<WindowManager, View>>()

    private var shownKey: Triple<Boolean, String?, Boolean>? = null

    /** [position] null means the indicator is switched off for this action. A non-null [onTap] makes
     *  the pictogram touchable (and closes it on tap); otherwise touches pass straight through. */
    fun show(top: Boolean, position: String?, visibleMs: Long = DEFAULT_VISIBLE_MS, onTap: (() -> Unit)? = null) {
        if (shown.isNotEmpty() && shownKey == Triple(top, position, onTap != null)) {
            handler.removeCallbacksAndMessages(null)
            shown.forEach { (_, view) -> view.animate().cancel(); view.animate().alpha(VISIBLE_ALPHA).setDuration(60).start() }
            handler.postDelayed(::hide, visibleMs)
            return
        }
        dismiss()
        if (position == null) return
        shownKey = Triple(top, position, onTap != null)
        val displayManager = context.getSystemService(DisplayManager::class.java)
        // AYN's privileged game-assistant window can cover accessibility overlays on the bottom
        // display, so focus confirmations always use the unobstructed top display.
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.let { addToDisplay(it, top, position.orEmpty(), onTap) }
        handler.postDelayed(::hide, visibleMs)
    }

    private fun addToDisplay(display: Display, top: Boolean, position: String, onTap: (() -> Unit)?) {
        val windowContext = context.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        val wm = windowContext.getSystemService(WindowManager::class.java)
        val density = windowContext.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
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
        val corner = position != Prefs.FOCUS_POS_CENTER
        val size = dp(if (corner) CORNER_DP else CENTER_DP)
        val pad = dp(if (corner) CORNER_DP / 13 else 12)
        val view = ImageView(windowContext).apply {
            setImageResource(if (top) R.drawable.focus_top_active else R.drawable.focus_bottom_active)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(size, size)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = windowContext.getString(
                if (top) R.string.focus_overlay_top else R.string.focus_overlay_bottom,
            )
            alpha = 0f
            if (onTap != null) {
                setOnClickListener {
                    dismiss()
                    onTap()
                }
            }
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                (if (onTap == null) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0) or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = horizontal or vertical
            // Measure the margin from the physical display edges: system bar insets (the navigation
            // bar is only on one side) would otherwise make the margins unequal.
            fitInsetsTypes = 0
            if (horizontal != Gravity.CENTER_HORIZONTAL) x = dp(CORNER_MARGIN_DP)
            if (vertical != Gravity.CENTER_VERTICAL) y = dp(CORNER_MARGIN_DP)
        }
        runCatching {
            wm.addView(view, params)
            shown += wm to view
            view.animate().alpha(VISIBLE_ALPHA).setDuration(140).start()
        }
    }

    private fun hide() {
        shown.forEach { (_, view) -> view.animate().alpha(0f).setDuration(180).start() }
        handler.postDelayed(::dismiss, FADE_MS)
    }

    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        shown.forEach { (wm, view) ->
            view.animate().cancel()
            runCatching { wm.removeViewImmediate(view) }
        }
        shown.clear()
        shownKey = null
    }

    companion object {
        private const val DEFAULT_VISIBLE_MS = Prefs.DEFAULT_FOCUS_DURATION_MS
        private const val FADE_MS = 180L
        private const val CENTER_DP = 156
        private const val CORNER_DP = 78
        private const val CORNER_MARGIN_DP = 20
        private const val VISIBLE_ALPHA = 0.75f
    }
}
