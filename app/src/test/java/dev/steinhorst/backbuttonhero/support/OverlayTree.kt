package dev.steinhorst.backbuttonhero.support

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import org.robolectric.util.ReflectionHelpers

/**
 * Serializes accessibility overlays, which never pass through an activity: the window parameters
 * they were added with, the unchanged [ViewTree] text of their content, and per view the alpha,
 * rotation, elevation and background that [ViewTree] leaves out. A separate format so existing
 * goldens keep their exact bytes.
 */
object OverlayTree {

    private val global: Any get() = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)!!

    /** Every window currently added through any WindowManager, oldest first, with its parameters. */
    fun windows(): List<Pair<View, WindowManager.LayoutParams>> {
        val views = ReflectionHelpers.getField<List<View>>(global, "mViews")
        val params = ReflectionHelpers.getField<List<WindowManager.LayoutParams>>(global, "mParams")
        return views.zip(params)
    }

    /** [state] adds laid-out size, scroll position and focus/selection, for overlays whose selection moves. */
    fun describe(windows: List<Pair<View, WindowManager.LayoutParams>>, state: Boolean = false): String =
        windows.joinToString("") { (root, params) -> describe(root, params, state) }

    fun describe(root: View, params: WindowManager.LayoutParams, state: Boolean = false): String {
        val sb = StringBuilder()
        sb.append("window type=").append(params.type)
            .append(" size=").append(params.width).append('x').append(params.height)
            .append(" x=").append(params.x).append(" y=").append(params.y)
            .append(" gravity=").append(String.format("0x%X", params.gravity))
            .append(" flags=").append(String.format("0x%08X", params.flags))
            .append(" format=").append(params.format)
            .append(" alpha=").append(params.alpha)
            .append(" title=\"").append(params.title.toString().replace(UUID, "<uuid>")).append("\"\n")
        sb.append(ViewTree.describe(root, "  "))
        sb.append("  extras\n")
        var index = 0
        fun walk(view: View) {
            sb.append("    #").append(index++)
                .append(" alpha=").append(view.alpha)
                .append(" rotation=").append(view.rotation)
                .append(" elevation=").append(view.elevation)
                .append(" background=").append(background(view))
            if (state) {
                sb.append(" size=").append(view.width).append('x').append(view.height)
                    .append(" scroll=").append(view.scrollX).append(',').append(view.scrollY)
                    .append(" focused=").append(view.isFocused)
                    .append(" selected=").append(view.isSelected)
            }
            sb.append('\n')
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
        return sb.toString()
    }

    private fun background(view: View): String = when (val d = view.background) {
        null -> "none"
        is ColorDrawable -> String.format("color#%08X", d.color)
        is GradientDrawable -> "gradient(color=" + (d.color?.defaultColor?.let { String.format("#%08X", it) } ?: "none") +
            " radius=" + d.cornerRadius + " shape=" + d.shape + ")"
        else -> d.javaClass.simpleName
    }

    private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
}
