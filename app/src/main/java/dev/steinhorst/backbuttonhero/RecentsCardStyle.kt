package dev.steinhorst.backbuttonhero

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import kotlin.math.sqrt
import kotlin.math.roundToInt

/** Read installed Quickstep's dimensions without copying or executing OEM code. */
data class RecentsCardStyle(
    val iconSize: Int,
    val iconDrawableSize: Int,
    val iconGap: Int,
    val pageGap: Int,
    val cornerRadius: Float,
    val snapDuration: Int,
    val minFlingVelocity: Int,
    val minSnapVelocity: Int,
) {
    /** Quickstep normalizes adaptive masks before drawing them in its 44 dp icon slot. */
    fun normalizedDrawableSize(icon: Drawable?): Int {
        if (icon !is AdaptiveIconDrawable) return iconDrawableSize
        val bounds = Rect(icon.bounds)
        val size = iconDrawableSize.coerceAtLeast(1) * 2
        return try {
            icon.setBounds(0, 0, size, size)
            val region = Region().apply { setPath(icon.iconMask, Region(0, 0, size, size)) }
            val iterator = RegionIterator(region)
            val rect = Rect()
            var area = 0
            while (iterator.next(rect)) area += rect.width() * rect.height()
            if (area == 0) iconDrawableSize else {
                val scale = sqrt(0.6510417f * size * size / area).coerceAtMost(1f)
                (iconDrawableSize * scale).roundToInt()
            }
        } finally { icon.bounds = bounds }
    }

    companion object {
        @SuppressLint("DiscouragedApi") // OEM resource names are optional; firmware changes use defaults.
        fun load(context: Context): RecentsCardStyle {
            val density = context.resources.displayMetrics.density
            fun px(dp: Int) = (dp * density).roundToInt()
            val launcher = RecentsTheme.launcherContext(context)
            fun dimension(name: String, fallback: Int): Float {
                val resources = launcher?.resources ?: return px(fallback).toFloat()
                val id = resources.getIdentifier(name, "dimen", "com.android.launcher3")
                return if (id == 0) px(fallback).toFloat() else resources.getDimension(id)
            }
            val durationId = launcher?.resources?.getIdentifier("config_pageSnapAnimationDuration", "integer", "com.android.launcher3") ?: 0
            val snapDuration = if (durationId == 0) 750 else launcher!!.resources.getInteger(durationId)
            val radius = if (launcher == null) px(20).toFloat() else {
                val values = launcher.theme.obtainStyledAttributes(intArrayOf(android.R.attr.dialogCornerRadius))
                try { values.getDimension(0, px(20).toFloat()) } finally { values.recycle() }
            }
            return RecentsCardStyle(
                dimension("task_thumbnail_icon_size", 48).roundToInt(),
                dimension("task_thumbnail_icon_drawable_size", 44).roundToInt(),
                dimension("overview_task_margin", 16).roundToInt(),
                dimension("overview_page_spacing", 16).roundToInt(),
                radius,
                snapDuration,
                dimension("min_fling_velocity", 250).roundToInt(),
                dimension("min_page_snap_velocity", 1500).roundToInt(),
            )
        }
    }
}
