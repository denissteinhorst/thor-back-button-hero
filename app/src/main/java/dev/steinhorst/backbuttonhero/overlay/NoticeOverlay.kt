package dev.steinhorst.backbuttonhero.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The service's short messages, drawn like a toast. Android rate-limits toasts from apps in the
 * background, which an accessibility service always is, and silently drops them after a few quick
 * hotkeys. This is one window that only changes its text, so every message gets through. Like the
 * focus pictogram it uses the top display, which AYN's assistant never covers. It carries the app
 * icon like Android's own toasts do, so it reads as ours next to the system toasts of the settings.
 */
class NoticeOverlay(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private class Shown(val wm: WindowManager, val box: View, val text: TextView)

    private var shown: Shown? = null

    /** While "Hide pop-ups" is on, messages are only logged; [show] with `force` still appears. */
    @Volatile var muted = false

    fun show(text: CharSequence, long: Boolean = false, force: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { show(text, long, force) }
            return
        }
        if (muted && !force) {
            Log.i(TAG, "Notice muted: " + text.toString().replace("\n", "\\n"))
            return
        }
        // One log line per message, so a two-line notice stays one entry for the on-device suite.
        Log.i(TAG, "Notice: " + text.toString().replace("\n", "\\n"))
        handler.removeCallbacksAndMessages(null)
        val view = shown ?: add() ?: return
        view.text.text = text
        view.text.announceForAccessibility(text)
        view.box.animate().cancel()
        view.box.animate().alpha(1f).setDuration(FADE_MS).start()
        handler.postDelayed(::hide, if (long) LONG_MS else SHORT_MS)
    }

    fun show(textRes: Int, long: Boolean = false, force: Boolean = false) = show(context.getString(textRes), long, force)

    private fun add(): Shown? {
        val windowContext = TopOverlayWindow.context(context) ?: return null
        val wm = windowContext.getSystemService(WindowManager::class.java)
        val density = windowContext.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val label = TextView(windowContext).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            maxWidth = dp(MAX_WIDTH_DP - ICON_DP - ICON_GAP_DP - 28)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        // The same icon the launcher and the system toasts show; decorative, the text carries the message.
        val icon = ImageView(windowContext).apply {
            setImageDrawable(runCatching { windowContext.packageManager.getApplicationIcon(windowContext.applicationInfo) }.getOrNull())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val view = LinearLayout(windowContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(BACKGROUND)
                cornerRadius = dp(22).toFloat()
            }
            addView(icon, LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)).apply { marginEnd = dp(ICON_GAP_DP) })
            addView(label)
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
            Shown(wm, view, label).also { shown = it }
        }.getOrNull()
    }

    private fun hide() {
        shown?.box?.animate()?.alpha(0f)?.setDuration(FADE_MS)?.withEndAction(::dismiss)?.start()
    }

    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        shown?.let { view ->
            view.box.animate().cancel()
            runCatching { view.wm.removeViewImmediate(view.box) }
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
        private const val ICON_DP = 20
        private const val ICON_GAP_DP = 8
        private const val BACKGROUND = 0xE6323232.toInt()
    }
}
