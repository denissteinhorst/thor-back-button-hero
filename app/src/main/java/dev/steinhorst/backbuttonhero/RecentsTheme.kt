package dev.steinhorst.backbuttonhero

import android.annotation.SuppressLint
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.ContextThemeWrapper

/** Resolves the same Launcher theme and dynamic system colors as OverviewState. */
data class RecentsTheme(val background: Int, val text: Int, val memory: Int, val selection: Int) {
    companion object {
        @SuppressLint("DiscouragedApi") // Read optional OEM theme/resource names, not unstable numeric IDs.
        fun launcherContext(context: Context): Context? = runCatching {
            val launcher = context.createPackageContext("com.android.launcher3", 0)
                .createConfigurationContext(context.resources.configuration)
            val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val hints = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching {
                context.getSystemService(WallpaperManager::class.java).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.colorHints ?: 0
            }.getOrDefault(0) else 0
            val suffix = when {
                hints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0 -> ".DarkText"
                hints and WallpaperColors.HINT_SUPPORTS_DARK_THEME != 0 -> ".DarkMainColor"
                else -> ""
            }
            val name = "AppTheme" + (if (dark) ".Dark" else "") + suffix
            val id = launcher.resources.getIdentifier(name, "style", "com.android.launcher3")
            ContextThemeWrapper(launcher, if (id != 0) id else launcher.applicationInfo.theme)
        }.getOrNull()

        @SuppressLint("DiscouragedApi") // Use the original action glyph, retaining the local fallback.
        fun actionDrawable(context: Context, name: String, fallback: Int): Drawable? {
            val launcher = launcherContext(context)
            val id = launcher?.resources?.getIdentifier(name, "drawable", "com.android.launcher3") ?: 0
            return if (id == 0) context.getDrawable(fallback) else launcher!!.getDrawable(id)
        }

        @SuppressLint("DiscouragedApi") // Theme colors are resolved through the installed package.
        fun load(context: Context): RecentsTheme {
            val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val fallbackText = if (dark) Color.WHITE else Color.BLACK
            val fallback = RecentsTheme(if (dark) Color.rgb(82, 82, 82) else Color.rgb(226, 226, 226),
                fallbackText, fallbackText, if (dark) 0x33FFFFFF else 0x33000000)
            val launcher = launcherContext(context) ?: return fallback
            fun attribute(id: Int, default: Int): Int {
                if (id == 0) return default
                val values = launcher.obtainStyledAttributes(intArrayOf(id))
                return try { values.getColor(0, default) } finally { values.recycle() }
            }
            val resources = launcher.resources
            val scrim = resources.getIdentifier("overviewScrimColor", "attr", "com.android.launcher3")
            val memory = resources.getIdentifier("neminfo_text_color", "color", "com.android.launcher3")
            return RecentsTheme(attribute(scrim, fallback.background),
                attribute(android.R.attr.textColorPrimary, fallback.text),
                if (memory == 0) fallback.memory else resources.getColor(memory, launcher.theme),
                attribute(android.R.attr.colorControlHighlight, fallback.selection))
        }
    }
}
