package dev.steinhorst.backbuttonhero.support

import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.TextView

/**
 * Deterministic, platform-independent text serialization of a View tree: enough detail to
 * catch an accidental layout/styling/text regression, nothing that varies between machines
 * (no absolute pixel positions unless pinned by a Robolectric density qualifier).
 */
object ViewTree {

    fun describe(root: View, indent: String = ""): String {
        val sb = StringBuilder()
        describeInto(root, indent, sb)
        return sb.toString()
    }

    private fun describeInto(view: View, indent: String, sb: StringBuilder) {
        sb.append(indent).append(lineFor(view)).append('\n')
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                describeInto(view.getChildAt(i), "$indent  ", sb)
            }
        }
    }

    private fun lineFor(view: View): String {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
        val parts = mutableListOf(
            view.javaClass.simpleName,
            "visibility=${visibilityName(view.visibility)}",
            "minHeight=${view.minimumHeight}",
            "padding=[${view.paddingLeft},${view.paddingTop},${view.paddingRight},${view.paddingBottom}]",
            "clickable=${view.isClickable}",
            "focusable=${view.isFocusable}",
        )
        if (lp != null) {
            parts += "margin=[${lp.marginStart},${lp.topMargin},${lp.marginEnd},${lp.bottomMargin}]"
        }
        view.contentDescription?.let { parts += "contentDescription=\"$it\"" }
        // CompoundButton (Switch, CheckBox, ...) is itself a TextView subclass in the Android
        // widget hierarchy, so this more specific branch must be checked first - otherwise
        // `is TextView` always wins and `checked=...` is never emitted for any Switch.
        when (view) {
            is CompoundButton -> {
                parts += "text=\"${view.text}\""
                parts += "textSizePx=${view.textSize}"
                parts += "textColor=${String.format("#%08X", view.currentTextColor)}"
                parts += "maxLines=${view.maxLines}"
                parts += "bold=${view.typeface?.isBold == true}"
                parts += "checked=${view.isChecked}"
            }
            is TextView -> {
                parts += "text=\"${view.text}\""
                parts += "textSizePx=${view.textSize}"
                parts += "textColor=${String.format("#%08X", view.currentTextColor)}"
                parts += "maxLines=${view.maxLines}"
                parts += "bold=${view.typeface?.isBold == true}"
            }
            is SeekBar -> {
                parts += "max=${view.max}"
                parts += "progress=${view.progress}"
            }
        }
        return parts.joinToString(" ")
    }

    private fun visibilityName(v: Int) = when (v) {
        View.VISIBLE -> "VISIBLE"
        View.INVISIBLE -> "INVISIBLE"
        View.GONE -> "GONE"
        else -> "UNKNOWN($v)"
    }
}
