package dev.steinhorst.backbuttonhero

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.roundToInt

/** Quickstep's empty icon and StaticLayout, centered within the same area as the task cards. */
class RecentsEmptyView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var icon: Drawable? = null
    private var message: CharSequence = ""
    private var gap = 0
    private var textLayout: StaticLayout? = null

    init { refreshTheme() }

    @SuppressLint("DiscouragedApi") // Resolve the installed Quickstep's original, themed empty-state resources.
    fun refreshTheme() {
        val launcher = RecentsTheme.launcherContext(context)
        fun id(name: String, type: String) = launcher?.resources?.getIdentifier(name, type, "com.android.launcher3") ?: 0
        val iconId = id("ic_empty_recents", "drawable")
        icon?.callback = null
        icon = if (iconId == 0) null else launcher?.getDrawable(iconId)?.mutate()?.apply { callback = this@RecentsEmptyView }
        val textId = id("recents_empty_message", "string")
        message = if (textId == 0) context.getText(R.string.no_recent_apps) else launcher!!.getText(textId)
        val gapId = id("recents_empty_message_text_padding", "dimen")
        gap = if (gapId == 0) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).roundToInt()
            else launcher!!.resources.getDimensionPixelSize(gapId)
        val sizeId = id("recents_empty_message_text_size", "dimen")
        paint.textSize = if (sizeId == 0) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
            else launcher!!.resources.getDimension(sizeId)
        paint.color = RecentsTheme.load(context).text
        val font = (launcher ?: context).obtainStyledAttributes(android.R.style.TextAppearance_Material_Body1,
            intArrayOf(android.R.attr.fontFamily))
        try { paint.typeface = Typeface.create(font.getString(0), Typeface.NORMAL) } finally { font.recycle() }
        contentDescription = message
        rebuildLayout()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLayout()
    }

    private fun rebuildLayout() {
        if (width <= 0 || height <= 0) return
        val layout = StaticLayout.Builder.obtain(message, 0, message.length, paint, (width - 2 * gap).coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        textLayout = layout
        icon?.let {
            val left = (width - it.intrinsicWidth) / 2
            val top = (height - layout.height - gap - it.intrinsicHeight) / 2
            it.setBounds(left, top, left + it.intrinsicWidth, top + it.intrinsicHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val layout = textLayout ?: return
        icon?.draw(canvas)
        val top = icon?.bounds?.bottom?.plus(gap) ?: ((height - layout.height) / 2)
        canvas.save()
        canvas.translate(gap.toFloat(), top.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === icon || super.verifyDrawable(who)
}
