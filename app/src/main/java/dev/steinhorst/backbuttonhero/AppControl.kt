package dev.steinhorst.backbuttonhero

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.steinhorst.backbuttonhero.prefs.Prefs

/** One foreground app an active-app-control press acts on. */
data class AppControlTarget(val displayId: Int, val packageName: String)

object AppControl {
    /**
     * The foreground apps [screen] covers. Displays without an app (only a launcher or system UI
     * showing) contribute nothing, so the action never reaches Home or this app by accident.
     */
    fun targets(screen: String, topId: Int, bottomId: Int?, appOn: (Int) -> String?): List<AppControlTarget> {
        val displays = when (screen) {
            Prefs.DISPLAY_TOP -> listOf(topId)
            Prefs.DISPLAY_BOTTOM -> listOfNotNull(bottomId)
            Prefs.TARGET_BOTH -> listOfNotNull(topId, bottomId)
            else -> emptyList()
        }
        return displays.mapNotNull { id -> appOn(id)?.let { AppControlTarget(id, it) } }
    }
}

/**
 * A small focusable list window for the "Ask" choices and the optional confirmations. A long list
 * scrolls; a red Cancel row below a rule closes it, reached like any other row, so a controller
 * never needs a hidden button to back out. It takes window focus on the display the controller drives, because joystick and
 * hat events only reach the focused window - the same reason [DpadOverlay] exists. The app behind it
 * is untouched until a choice is made, and it is resolved before this opens.
 */
class AppControlPrompt(
    private val context: Context,
    private val isConfirmKey: (Int) -> Boolean,
) {
    class Option(val value: String, val label: String)

    private var window: Pair<WindowManager, View>? = null

    val showing: Boolean get() = window != null

    fun show(display: Display, title: String, options: List<Option>, onChoice: (String) -> Unit) {
        dismiss()
        val ctx = context.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        val wm = ctx.getSystemService(WindowManager::class.java)
        val density = ctx.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val dark = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val text = if (dark) Color.WHITE else Color.BLACK
        val highlight = if (dark) 0x44FFFFFF else 0x33000000
        var selected = 0
        // The options, then Cancel as the last row.
        val rows = mutableListOf<TextView>()
        fun paint() = rows.forEachIndexed { i, row ->
            row.background = if (i == selected) GradientDrawable().apply {
                setColor(highlight)
                cornerRadius = dp(8).toFloat()
            } else null
        }
        fun finish(index: Int?) {
            dismiss()
            index?.let(options::getOrNull)?.let { onChoice(it.value) }
        }
        fun move(by: Int) {
            selected = (selected + by).mod(rows.size)
            paint()
            rows[selected].let { it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height), true) }
        }
        fun row(label: String, color: Int, index: Int) = TextView(ctx).apply {
            this.text = label
            setTextColor(color)
            textSize = 17f
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(52)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { finish(index) }
            rows += this
        }
        val full = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        // Keeps title and Cancel on the screen however many apps the list holds.
        val maxList = (ctx.resources.displayMetrics.heightPixels * LIST_SHARE).toInt()
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        options.forEachIndexed { i, option -> list.addView(row(option.label, text, i), LinearLayout.LayoutParams(full, wrap)) }
        val scroll = object : ScrollView(ctx) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxList, MeasureSpec.AT_MOST))
        }.apply {
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            addView(list)
        }
        val cancel = row(ctx.getString(R.string.app_control_prompt_cancel), if (dark) Color.rgb(255, 138, 128) else Color.rgb(211, 47, 47), options.size)

        val view = object : LinearLayout(ctx) {
            private var lastY = 0f

            // The D-pad arrives as a hat axis and the analog stick as Y; only the edge out of rest moves.
            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                val hat = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
                val y = if (hat != 0f) hat else event.getAxisValue(MotionEvent.AXIS_Y)
                val was = lastY
                lastY = y
                if (Math.abs(was) < AXIS_THRESHOLD && Math.abs(y) >= AXIS_THRESHOLD) move(if (y < 0f) -1 else 1)
                return true
            }

            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) return false
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    when {
                        event.keyCode == KeyEvent.KEYCODE_DPAD_UP -> move(-1)
                        event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> move(1)
                        isConfirmKey(event.keyCode) || event.keyCode == KeyEvent.KEYCODE_ENTER -> finish(selected)
                        event.keyCode == KeyEvent.KEYCODE_BUTTON_A || event.keyCode == KeyEvent.KEYCODE_BUTTON_B -> finish(null)
                    }
                }
                return true
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(if (dark) Color.rgb(48, 48, 52) else Color.rgb(250, 250, 252))
                cornerRadius = dp(20).toFloat()
            }
            isFocusable = true
            isFocusableInTouchMode = true
            addView(TextView(ctx).apply {
                this.text = title
                setTextColor(text)
                textSize = 18f
                setPadding(dp(8), 0, dp(8), dp(10))
            })
            addView(scroll, LinearLayout.LayoutParams(full, wrap))
            addView(View(ctx).apply { setBackgroundColor(if (dark) 0x33FFFFFF else 0x22000000) },
                LinearLayout.LayoutParams(full, dp(1)).apply { setMargins(dp(8), dp(6), dp(8), dp(6)) })
            addView(cancel, LinearLayout.LayoutParams(full, wrap))
        }
        paint()
        val params = WindowManager.LayoutParams(
            dp(320),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Focusable on purpose; ALT_FOCUSABLE_IM keeps a keyboard from being summoned over the game.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        runCatching {
            wm.addView(view, params)
            window = wm to view
            view.requestFocus()
        }
    }

    fun dismiss() {
        window?.let { (wm, view) -> runCatching { wm.removeViewImmediate(view) } }
        window = null
    }

    private companion object {
        const val AXIS_THRESHOLD = 0.5f
        /** The most of the screen's height the options take before they scroll. */
        const val LIST_SHARE = 0.6f
    }
}
