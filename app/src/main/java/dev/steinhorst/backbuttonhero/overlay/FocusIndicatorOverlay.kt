package dev.steinhorst.backbuttonhero.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.prefs.Prefs

/** A brief, display-specific confirmation that never takes touch, key or controller focus. */
class FocusIndicatorOverlay(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val shown = mutableListOf<Pair<WindowManager, View>>()

    private data class Key(val top: Boolean, val auto: Boolean, val position: String?, val tappable: Boolean)

    private var shownKey: Key? = null
    // A reused pictogram may now belong to another button; its listener always reads this one.
    private var tapAction: (() -> Unit)? = null

    /** [position] null means the indicator is switched off for this action. A non-null [onTap] makes
     *  the pictogram touchable (and closes it on tap); otherwise touches pass straight through. With
     *  [auto] it shows AYN's Auto-lock instead of [top]: both screens lit, labelled AUTO. */
    fun show(top: Boolean, position: String?, visibleMs: Long = DEFAULT_VISIBLE_MS, onTap: (() -> Unit)? = null, auto: Boolean = false) {
        val key = Key(top && !auto, auto, position, onTap != null)
        if (shown.isNotEmpty() && shownKey == key) {
            tapAction = onTap
            handler.removeCallbacksAndMessages(null)
            shown.forEach { (_, view) -> view.animate().cancel(); view.animate().alpha(VISIBLE_ALPHA).setDuration(60).start() }
            handler.postDelayed(::hide, visibleMs)
            return
        }
        dismiss()
        if (position == null) return
        shownKey = key
        tapAction = onTap
        // AYN's privileged game-assistant window can cover accessibility overlays on the bottom
        // display, so focus confirmations always use the unobstructed top display.
        TopOverlayWindow.context(context)?.let { addToDisplay(it, top, auto, position, onTap) }
        handler.postDelayed(::hide, visibleMs)
    }

    /** Keeps the current controller route visible while a switcher owns the controller. */
    fun showPersistent(top: Boolean, position: String?) {
        if (shown.isNotEmpty() && shownKey == Key(top, false, position, false)) {
            handler.removeCallbacksAndMessages(null)
            shown.forEach { (_, view) -> view.animate().cancel(); view.animate().alpha(VISIBLE_ALPHA).setDuration(60).start() }
            return
        }
        dismiss()
        if (position == null) return
        shownKey = Key(top, false, position, false)
        TopOverlayWindow.context(context)?.let { addToDisplay(it, top, false, position, null) }
    }

    private fun addToDisplay(windowContext: Context, top: Boolean, auto: Boolean, position: String, onTap: (() -> Unit)?) {
        val wm = windowContext.getSystemService(WindowManager::class.java)
        val density = windowContext.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val horizontal = TopOverlayWindow.horizontalGravity(position)
        val vertical = TopOverlayWindow.verticalGravity(position)
        val corner = position != Prefs.FOCUS_POS_CENTER
        val size = dp(if (corner) CORNER_DP else CENTER_DP)
        val pad = dp(if (corner) CORNER_DP / 13 else 12)
        val view = (if (auto) autoPictogram(windowContext, dp(if (corner) AUTO_TEXT_CORNER_DP else AUTO_TEXT_CENTER_DP)) else ImageView(windowContext).apply {
            setImageResource(if (top) R.drawable.focus_top_active else R.drawable.focus_bottom_active)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }).apply {
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(size, size)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = windowContext.getString(
                if (auto) R.string.focus_overlay_auto else if (top) R.string.focus_overlay_top else R.string.focus_overlay_bottom,
            )
            alpha = 0f
            if (onTap != null) {
                setOnClickListener {
                    val action = tapAction
                    dismiss()
                    action?.invoke()
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

    /**
     * Auto-lock inside the same square as the single-screen pictograms, so every grid position fits
     * unchanged: both screens lit, and below them AUTO in the pictogram's own black and white. The
     * label is sized in pixels, not sp, so a large system font cannot push it out of the square.
     */
    private fun autoPictogram(windowContext: Context, textPx: Int) = LinearLayout(windowContext).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(ImageView(windowContext).apply {
            setImageResource(R.drawable.focus_both_active)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(TextView(windowContext).apply {
            text = windowContext.getString(R.string.focus_overlay_auto_label)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx.toFloat())
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            letterSpacing = AUTO_LETTER_SPACING
            setPadding(textPx / 2, textPx / 6, textPx / 2, textPx / 6)
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                setStroke((textPx / AUTO_STROKE_DIVISOR).coerceAtLeast(1), Color.WHITE)
                cornerRadius = textPx.toFloat()
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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
        tapAction = null
    }

    companion object {
        private const val DEFAULT_VISIBLE_MS = Prefs.DEFAULT_FOCUS_DURATION_MS
        private const val FADE_MS = 180L
        private const val CENTER_DP = 156
        private const val CORNER_DP = 78
        private const val CORNER_MARGIN_DP = 20
        private const val VISIBLE_ALPHA = 0.75f
        private const val AUTO_TEXT_CENTER_DP = 20
        private const val AUTO_TEXT_CORNER_DP = 11
        private const val AUTO_LETTER_SPACING = 0.08f
        private const val AUTO_STROKE_DIVISOR = 10
    }
}
