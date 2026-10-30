package dev.steinhorst.backbuttonhero

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

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
 * A small focusable list window for the "Ask" screen choice and the optional force-close
 * confirmation. It takes window focus on the display the controller drives, because joystick and
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
        val rows = mutableListOf<TextView>()
        fun paint() = rows.forEachIndexed { i, row ->
            row.background = if (i == selected) GradientDrawable().apply {
                setColor(highlight)
                cornerRadius = dp(8).toFloat()
            } else null
        }
        fun finish(index: Int?) {
            dismiss()
            index?.let { onChoice(options[it].value) }
        }
        fun move(by: Int) {
            selected = (selected + by).mod(options.size)
            paint()
        }

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
            options.forEachIndexed { i, option ->
                val row = TextView(ctx).apply {
                    this.text = option.label
                    setTextColor(text)
                    textSize = 17f
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = dp(52)
                    setPadding(dp(12), 0, dp(12), 0)
                    setOnClickListener { finish(i) }
                }
                rows += row
                addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
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
    }
}
