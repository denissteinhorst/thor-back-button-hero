package dev.steinhorst.backbuttonhero.macro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.settings.dp

/**
 * One input of a macro step as a 40 dp glyph: a lettered key, a D-pad with the pressed arm lit, or a
 * stick as a ring with a dot pushed towards its direction.
 */
class MacroIconView(context: Context) : View(context) {
    private var input: MacroInput? = null
    private var glyph: Drawable? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lit = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val rect = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show(input: MacroInput, color: Int, accent: Int) {
        this.input = input
        fill.color = Color.argb(34, Color.red(color), Color.green(color), Color.blue(color))
        lit.color = accent
        ring.color = Color.argb(150, Color.red(color), Color.green(color), Color.blue(color))
        ring.strokeWidth = resources.displayMetrics.density * 2
        text.color = color
        glyph = (input as? MacroInput.Button)?.button?.let {
            when (it) {
                MacroButton.START -> context.getDrawable(R.drawable.ic_exc_start)
                MacroButton.SELECT -> context.getDrawable(R.drawable.ic_exc_select)
                else -> null
            }
        }?.mutate()?.apply { setTint(color) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val cy = h / 2
        when (val current = input) {
            is MacroInput.Button -> {
                rect.set(0f, 0f, w, h)
                canvas.drawRoundRect(rect, w / 2, h / 2, fill)
                val icon = glyph
                if (icon != null) {
                    val inset = (w * 0.22f).toInt()
                    icon.setBounds(inset, inset, width - inset, height - inset)
                    icon.draw(canvas)
                } else {
                    text.textSize = h * if (current.button.wire.length > 1) 0.34f else 0.44f
                    canvas.drawText(current.button.wire, cx, cy - (text.descent() + text.ascent()) / 2, text)
                }
            }
            is MacroInput.Dpad -> {
                val arm = w * 0.3f
                val reach = w * 0.48f
                rect.set(cx - arm / 2, cy - reach, cx + arm / 2, cy + reach)
                canvas.drawRoundRect(rect, arm / 4, arm / 4, fill)
                rect.set(cx - reach, cy - arm / 2, cx + reach, cy + arm / 2)
                canvas.drawRoundRect(rect, arm / 4, arm / 4, fill)
                val d = current.direction
                if (d.dy != 0) {
                    rect.set(cx - arm / 2, if (d.dy < 0) cy - reach else cy + arm / 2, cx + arm / 2, if (d.dy < 0) cy - arm / 2 else cy + reach)
                    canvas.drawRoundRect(rect, arm / 4, arm / 4, lit)
                }
                if (d.dx != 0) {
                    rect.set(if (d.dx < 0) cx - reach else cx + arm / 2, cy - arm / 2, if (d.dx < 0) cx - arm / 2 else cx + reach, cy + arm / 2)
                    canvas.drawRoundRect(rect, arm / 4, arm / 4, lit)
                }
            }
            is MacroInput.Analog -> {
                val radius = w / 2 - ring.strokeWidth
                canvas.drawCircle(cx, cy, radius, fill)
                canvas.drawCircle(cx, cy, radius, ring)
                text.textSize = h * 0.26f
                canvas.drawText(current.stick.wire, cx, cy - (text.descent() + text.ascent()) / 2, text)
                val d = current.direction
                val scale = if (d.dx != 0 && d.dy != 0) 0.707f else 1f
                val reach = radius * 0.58f * scale
                canvas.drawCircle(cx + d.dx * reach, cy + d.dy * reach, w * 0.13f, lit)
            }
            null -> Unit
        }
    }
}
