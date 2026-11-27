package dev.steinhorst.backbuttonhero.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.input.FacePosition
import dev.steinhorst.backbuttonhero.input.PromptKeys

/**
 * The face-button diamond of the settings' face icon (`ic_settings_face`): four rings in the Thor's
 * layout with the one at [filled] drawn solid, so a hint shows which physical button it means
 * without naming a letter the style switch may have moved.
 */
class FaceButtonPictogram @JvmOverloads constructor(
    context: Context,
    val filled: FacePosition = FacePosition.RIGHT,
    color: Int = Color.BLACK,
) : View(context) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    var color: Int = color
        set(value) {
            field = value
            invalidate()
        }

    init {
        contentDescription = context.getString(when (filled) {
            FacePosition.LEFT -> R.string.face_position_left
            FacePosition.TOP -> R.string.face_position_top
            FacePosition.RIGHT -> R.string.face_position_right
            FacePosition.BOTTOM -> R.string.face_position_bottom
        })
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // The same 24-unit geometry as the vector icon: rings of radius 3 around a centre at 12.
        val unit = minOf(width, height) / VIEWPORT
        if (unit <= 0f) return
        val left = width / 2f - unit * VIEWPORT / 2
        val top = height / 2f - unit * VIEWPORT / 2
        ring.color = color
        ring.strokeWidth = STROKE * unit
        dot.color = color
        for (position in FacePosition.entries) {
            val (x, y) = center(position)
            val cx = left + x * unit
            val cy = top + y * unit
            if (position == filled) canvas.drawCircle(cx, cy, (RADIUS + STROKE / 2) * unit, dot)
            else canvas.drawCircle(cx, cy, RADIUS * unit, ring)
        }
    }

    private companion object {
        const val VIEWPORT = 24f
        const val RADIUS = 3f
        const val STROKE = 2f

        fun center(position: FacePosition): Pair<Float, Float> = when (position) {
            FacePosition.LEFT -> 5f to 12f
            FacePosition.TOP -> 12f to 5f
            FacePosition.RIGHT -> 19f to 12f
            FacePosition.BOTTOM -> 12f to 19f
        }
    }
}

/** Pictogram-plus-label legends for the controller keys of the prompts and switchers. */
object ButtonHints {
    /** One legend entry: the key a press must report, and what it does. */
    class Hint(val keyCode: Int, val label: String)

    fun confirmAndCancel(context: Context): List<Hint> = listOf(
        Hint(PromptKeys.CONFIRM, context.getString(R.string.button_hint_confirm)),
        Hint(PromptKeys.CANCEL, context.getString(R.string.app_control_prompt_cancel)),
    )

    /**
     * The entries side by side (or stacked when [vertical]), each a pictogram with the button
     * position [flipped] gives that key filled, then its label. Not focusable: a hint, never a target.
     */
    fun legend(
        ctx: Context,
        hints: List<Hint>,
        flipped: Boolean,
        color: Int,
        textSp: Float,
        iconDp: Int,
        vertical: Boolean = false,
    ): LinearLayout {
        fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), ctx.resources.displayMetrics).toInt()
        return LinearLayout(ctx).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (vertical) Gravity.START else Gravity.CENTER
            hints.forEachIndexed { i, hint ->
                val position = PromptKeys.position(hint.keyCode, flipped) ?: return@forEachIndexed
                val item = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(FaceButtonPictogram(ctx, position, color),
                        LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)).apply { marginEnd = dp(6) })
                    addView(TextView(ctx).apply {
                        text = hint.label
                        setTextColor(color)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
                    })
                }
                addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0 && vertical) topMargin = dp(6)
                    if (i > 0 && !vertical) marginStart = dp(20)
                })
            }
        }
    }

    /** Recolours a [legend] in place, for a theme change while it is shown. */
    fun recolor(view: View, color: Int) {
        when (view) {
            is FaceButtonPictogram -> view.color = color
            is TextView -> view.setTextColor(color)
            is ViewGroup -> for (i in 0 until view.childCount) recolor(view.getChildAt(i), color)
        }
    }
}

/**
 * Confirm and Cancel for the controller session over native top Recents. Quickstep's window is not
 * ours to draw into, so this is a small window of our own in the top screen's bottom-right corner,
 * clear of the centred cards and actions. Like the focus pictogram it never takes focus or touch.
 */
class ButtonHintOverlay(private val context: Context) {
    private var shown: Pair<WindowManager, View>? = null

    val showing: Boolean get() = shown != null

    fun show(flipped: Boolean) {
        dismiss()
        val windowContext = TopOverlayWindow.context(context) ?: return
        val wm = windowContext.getSystemService(WindowManager::class.java)
        val density = windowContext.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val view = ButtonHints.legend(windowContext, ButtonHints.confirmAndCancel(windowContext), flipped,
            TEXT, 14f, 18, vertical = true).apply {
            setPadding(dp(14), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(BACKGROUND)
                cornerRadius = dp(18).toFloat()
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            // From the physical edges, as the focus pictogram measures its margins.
            fitInsetsTypes = 0
            x = dp(MARGIN_DP)
            y = dp(MARGIN_DP)
        }
        runCatching {
            wm.addView(view, params)
            shown = wm to view
        }
    }

    fun dismiss() {
        shown?.let { (wm, view) -> runCatching { wm.removeViewImmediate(view) } }
        shown = null
    }

    private companion object {
        /** The notice's toast colours, legible over Quickstep's light and dark scrim alike. */
        const val BACKGROUND = 0xE6323232.toInt()
        const val TEXT = 0xFFFFFFFF.toInt()
        const val MARGIN_DP = 20
    }
}
