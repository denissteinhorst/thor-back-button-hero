package dev.steinhorst.backbuttonhero.macro

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.dp
import dev.steinhorst.backbuttonhero.themedColor

/** Colours and the small building blocks the macro editor, its sheets and its palette share. */
internal class MacroUi(val activity: Activity) {
    val primary = activity.themedColor(android.R.attr.textColorPrimary, Color.BLACK)
    val secondary = activity.themedColor(android.R.attr.textColorSecondary, Color.GRAY)
    val accent = activity.themedColor(android.R.attr.colorAccent, Color.parseColor("#6750A4"))
    val surface = activity.themedColor(android.R.attr.colorBackgroundFloating, Color.parseColor("#F3F3F3"))
    val windowColor = activity.themedColor(android.R.attr.colorBackground, Color.WHITE)
    /** Amber that stays readable: lighter on a dark window, deeper on a light one. */
    val warning: Int = Color.parseColor(if (Color.luminance(windowColor) < 0.5f) "#FFB74D" else "#B26A00")

    fun dp(value: Int) = activity.dp(value)

    fun alpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null, strokeDp: Int = 1) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        stroke?.let { setStroke(dp(strokeDp), it) }
    }

    /** The ring a focused control shows, so the editor can be driven with the D-pad and A. */
    fun focusRing(radiusDp: Int = 12): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(Color.TRANSPARENT, radiusDp, accent, 3))
    }

    fun ripple(borderless: Boolean): Drawable? = TypedValue().let { value ->
        activity.theme.resolveAttribute(
            if (borderless) android.R.attr.selectableItemBackgroundBorderless else android.R.attr.selectableItemBackground, value, true,
        )
        activity.getDrawable(value.resourceId)
    }

    /** Makes [view] a control: clickable, reachable with the D-pad, with a ring while focused. */
    fun <T : View> touchable(view: T, radiusDp: Int = 12, onClick: () -> Unit): T = view.apply {
        isClickable = true
        isFocusable = true
        foreground = focusRing(radiusDp)
        setOnClickListener { onClick() }
    }

    fun text(sizeSp: Float, color: Int = primary, bold: Boolean = false) = TextView(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    fun icon(res: Int, color: Int = primary, sizeDp: Int = 24) = ImageView(activity).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** A 48 dp round icon control. */
    fun iconButton(res: Int, label: String, color: Int = primary, onClick: () -> Unit) = touchable(ImageView(activity), radiusDp = 24, onClick).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        scaleType = ImageView.ScaleType.CENTER
        background = ripple(borderless = true)
        contentDescription = label
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    }

    /** A pill-shaped button: [filled] for the one main action, outlined for the others. */
    fun pill(label: String, filled: Boolean, color: Int = accent, onClick: () -> Unit) = touchable(text(15f, if (filled) Color.WHITE else color, bold = true), 24, onClick).apply {
        text = label
        gravity = Gravity.CENTER
        minHeight = dp(44)
        minWidth = dp(64)
        setPadding(dp(18), 0, dp(18), 0)
        background = if (filled) rounded(color, 22) else rounded(Color.TRANSPARENT, 22, alpha(color, 120), 1)
    }

    fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    /** A panel that slides up from the bottom edge, at most [maxWidthDp] wide; refilled in place as values change. */
    fun sheet(maxWidthDp: Int = MAX_SHEET_DP): Sheet = Sheet(this, maxWidthDp)

    class Sheet(private val ui: MacroUi, maxWidthDp: Int) {
        val content = LinearLayout(ui.activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(20))
        }
        val dialog = Dialog(ui.activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(ScrollView(ui.activity).apply {
                background = GradientDrawable().apply {
                    setColor(ui.windowColor)
                    val r = ui.dp(24).toFloat()
                    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                }
                addView(content)
            })
            setCanceledOnTouchOutside(true)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setGravity(Gravity.BOTTOM)
                setWindowAnimations(android.R.style.Animation_InputMethod)
                val width = minOf(ui.activity.resources.displayMetrics.widthPixels, ui.dp(maxWidthDp))
                setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }

        /** Clears the sheet and starts it again with [title] on the left and a closing [done] button on the right. */
        fun header(title: String, done: String) {
            content.removeAllViews()
            content.addView(LinearLayout(ui.activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.text(18f, bold = true).apply { text = title }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ui.pill(done, filled = false) { dialog.dismiss() })
            })
        }

        fun row(gravity: Int, block: LinearLayout.() -> Unit) {
            content.addView(LinearLayout(ui.activity).apply {
                this.gravity = gravity
                block()
            })
        }
    }

    companion object {
        const val DISABLED_ALPHA = 0.38f
        const val MAX_SHEET_DP = 720
        /** The palette shows all four input groups side by side on the Thor's top screen. */
        const val WIDE_SHEET_DP = 820
        val DANGER = Color.parseColor("#E53935")
    }
}

/** A gap between controls in a row. */
internal fun LinearLayout.space(widthDp: Int) = addView(View(context), LinearLayout.LayoutParams(context.resources.displayMetrics.density.let { (widthDp * it).toInt() }, 1))

/**
 * The editor's row of steps and pauses. A long-pressed step lifts and follows the finger; an accent
 * line shows where it will land, and the [listener] decides on release whether it moves there or
 * goes to the bin. The row takes over the touch stream once a drag starts, so redrawing the steps
 * while dragging never cuts it off.
 */
class StepStrip(context: Context) : LinearLayout(context) {
    interface Listener {
        fun onDragStart()
        fun onDragMove(rawX: Float, rawY: Float)
        fun onDrop(from: Int, slot: Int, rawX: Float, rawY: Float)
        fun onDragEnd()
    }

    var listener: Listener? = null
    private var dragged: View? = null
    private var from = -1
    private var slot = -1
    private var grabX = 0f
    private var lastX = 0f
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerRect = RectF()
    private val density = resources.displayMetrics.density

    /** The views of the steps, left to right; pauses and the add card are left out. */
    val stepViews: List<View> get() = (0 until childCount).map { getChildAt(it) }.filter { it.tag == STEP }

    val dragging: Boolean get() = dragged != null

    fun setMarkerColor(color: Int) {
        marker.color = color
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastX = ev.x
        val handled = super.dispatchTouchEvent(ev)
        // A cancel can arrive before the row took the stream over; the lifted step must still land.
        if (ev.actionMasked == MotionEvent.ACTION_CANCEL && dragged != null) endDrag()
        return handled
    }

    /** Lifts step [index] ([view]) under the finger; called from the step's long press. */
    fun startDrag(view: View, index: Int) {
        dragged = view
        from = index
        slot = index
        grabX = lastX
        view.alpha = 0.85f
        view.translationY = -6 * density
        view.elevation = 8 * density
        parent?.requestDisallowInterceptTouchEvent(true)
        listener?.onDragStart()
        invalidate()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = dragged != null || super.onInterceptTouchEvent(ev)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val view = dragged ?: return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                view.translationX = event.x - grabX
                slot = slotAt(event.x)
                listener?.onDragMove(event.rawX, event.rawY)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                // The release counts as the last position: the move that started the drag is never seen here.
                val (start, target) = from to slotAt(event.x)
                endDrag()
                listener?.onDrop(start, target, event.rawX, event.rawY)
            }
            MotionEvent.ACTION_CANCEL -> endDrag()
        }
        return true
    }

    private fun slotAt(x: Float) = MacroEdits.slot(stepViews.map { (it.left + it.right) / 2f }, x)

    // The row itself has no click action; this keeps accessibility services' view of it consistent.
    override fun performClick(): Boolean = super.performClick()

    private fun endDrag() {
        dragged?.apply {
            alpha = 1f
            translationX = 0f
            translationY = 0f
            elevation = 0f
        }
        dragged = null
        parent?.requestDisallowInterceptTouchEvent(false)
        listener?.onDragEnd()
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val steps = stepViews
        // Dropping right before or behind itself would change nothing, so no line is shown there.
        if (dragged == null || slot == from || slot == from + 1 || steps.isEmpty()) return
        val x = when (slot) {
            0 -> steps.first().left - 4 * density
            steps.size -> steps.last().right + 4 * density
            else -> (steps[slot - 1].right + steps[slot].left) / 2f
        }
        markerRect.set(x - 1.5f * density, paddingTop.toFloat(), x + 1.5f * density, (height - paddingBottom).toFloat())
        canvas.drawRoundRect(markerRect, 2 * density, 2 * density, marker)
    }

    companion object {
        const val STEP = "macro_step"
    }
}

/**
 * A short track under a horizontally scrolling row, with a thumb for the part in view. It stays
 * hidden while everything fits, so a short macro shows no scrolling chrome at all.
 */
class ScrollIndicator(context: Context) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var start = 0f
    private var fraction = 1f

    init {
        visibility = INVISIBLE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setColors(trackColor: Int, thumbColor: Int) {
        track.color = trackColor
        thumb.color = thumbColor
    }

    /** The row shows [visible] px of [content] px, scrolled by [scroll] px. */
    fun update(scroll: Int, visible: Int, content: Int) {
        val scrolls = visible in 1 until content
        visibility = if (scrolls) VISIBLE else INVISIBLE
        if (!scrolls) return
        fraction = visible.toFloat() / content
        start = scroll.toFloat() / content
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, track)
        // Never thinner than the track is high, so a long macro still shows a visible thumb.
        val length = maxOf(height.toFloat(), width * fraction)
        val left = (width - length) * (start / (1f - fraction))
        rect.set(left, 0f, left + length, height.toFloat())
        canvas.drawRoundRect(rect, radius, radius, thumb)
    }
}
