package dev.steinhorst.backbuttonhero

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView

/** The OEM's own drawables and lockicon_ext geometry, including its literal 80-pixel offset. */
class RecentsBadges private constructor(
    private val context: Context,
    private val lock: Drawable,
    private val unlocked: Drawable,
    private val display: Drawable,
    private val size: Int,
    private val padding: Int,
    private val topMarginPx: Int,
    private val rightMarginPx: Int,
) {
    fun addTo(header: FrameLayout): Pair<ImageView, ImageView> {
        fun image(offset: Int) = ImageView(header.context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val inset = if (offset == 0) padding else 0
            setPadding(inset, inset, inset, inset)
            visibility = android.view.View.INVISIBLE
            header.addView(this, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
                topMargin = topMarginPx
                rightMargin = rightMarginPx + offset
            })
        }
        return image(0) to image(80)
    }

    fun update(views: Pair<ImageView, ImageView>, state: RecentsTaskState?) {
        if (state == null) {
            views.first.visibility = android.view.View.INVISIBLE
            views.second.visibility = android.view.View.INVISIBLE
            return
        }
        views.first.setImageDrawable((if (state.locked) lock else unlocked).constantState?.newDrawable(context.resources, context.theme)?.mutate())
        views.second.setImageDrawable(display.constantState?.newDrawable(context.resources, context.theme)?.mutate()?.apply {
            level = if (state.displayId > android.view.Display.DEFAULT_DISPLAY) 2 else 1
        })
        views.first.visibility = android.view.View.VISIBLE
        views.second.visibility = android.view.View.VISIBLE
    }

    companion object {
        @SuppressLint("DiscouragedApi") // Resolve original OEM assets by name across firmware versions.
        fun load(context: Context): RecentsBadges? = runCatching {
            val launcher = requireNotNull(RecentsTheme.launcherContext(context))
            val resources = launcher.resources
            fun id(name: String, type: String) = resources.getIdentifier(name, type, "com.android.launcher3").also { require(it != 0) }
            fun dimension(name: String) = resources.getDimensionPixelSize(id(name, "dimen"))
            fun drawable(name: String) = requireNotNull(resources.getDrawable(id(name, "drawable"), launcher.theme))
            RecentsBadges(launcher, drawable("lock_icon_ext"), drawable("unlock_icon_ext"), drawable("task_ext_display_state"),
                dimension("recent_view_lock_size"), dimension("task_lock_icon_padding"),
                dimension("recent_view_lock_top_margin"), dimension("recent_view_lock_right_margin"))
        }.getOrNull()
    }
}
