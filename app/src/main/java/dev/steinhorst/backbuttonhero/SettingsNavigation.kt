package dev.steinhorst.backbuttonhero

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

enum class SettingsSection(val titleRes: Int, val hintRes: Int, val iconRes: Int) {
    BASIC(R.string.nav_basic, R.string.nav_basic_hint, R.drawable.ic_settings_back),
    SHOULDERS(R.string.nav_shoulders, R.string.nav_shoulders_hint, R.drawable.ic_settings_shoulders),
    DPAD(R.string.nav_dpad, R.string.nav_dpad_hint, R.drawable.ic_settings_dpad),
    EXCEPTIONS(R.string.nav_exceptions, R.string.nav_exceptions_hint, R.drawable.ic_settings_exceptions),
    SETTINGS(R.string.nav_settings, R.string.nav_settings_hint, R.drawable.ic_settings_gear),
    HELP(R.string.nav_help, R.string.nav_help_hint, R.drawable.ic_settings_help),
}

/** Keeps the navigation chrome independent of lazily built, cached settings pages. */
class SettingsNavigation(
    private val activity: Activity,
    savedState: Bundle?,
    val warnings: LinearLayout,
    serviceWarning: TextView,
    private val buildPage: (SettingsSection, LinearLayout) -> Unit,
    private val onSelected: () -> Unit,
) {
    private val full = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val pages = mutableMapOf<SettingsSection, ScrollView>()
    private val positions = mutableMapOf<SettingsSection, Int>()
    private val navButtons = mutableMapOf<SettingsSection, Button>()
    private val pageHost = FrameLayout(activity)
    private val body = LinearLayout(activity)
    private val bar = LinearLayout(activity)
    private val titleRow = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
    private var menuDialog: AlertDialog? = null
    private var wide: Boolean? = null
    private val title = TextView(activity).apply {
        textSize = 20f
        setTextColor(activity.themedColor(android.R.attr.textColorPrimary, Color.BLACK))
        setTypeface(typeface, Typeface.BOLD)
        isAccessibilityHeading = true
        setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(8))
    }
    private val menu = Button(activity).apply {
        setText(R.string.settings_menu)
        isAllCaps = false
        minimumHeight = activity.dp(48)
        tag = "settings_menu"
        setCompoundDrawablesRelativeWithIntrinsicBounds(icon(R.drawable.ic_settings_menu), null, null, null)
        compoundDrawablePadding = activity.dp(8)
        setOnClickListener { showMenu() }
    }
    var selected: SettingsSection = SettingsSection.entries.firstOrNull {
        it.name == savedState?.getString("settings_section")
    } ?: SettingsSection.BASIC
        private set

    val view = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    init {
        SettingsSection.entries.forEach { positions[it] = savedState?.getInt("settings_scroll_${it.name}") ?: 0 }
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.minimumHeight = activity.dp(56)
        bar.setPadding(activity.dp(8), activity.dp(4), activity.dp(8), activity.dp(4))
        titleRow.addView(menu)
        titleRow.addView(title, LinearLayout.LayoutParams(0, wrap, 1f))
        titleRow.addView(serviceWarning, LinearLayout.LayoutParams(0, wrap, 1f))
        bar.tag = "settings_top_bar"
        bar.setBackgroundColor(activity.settingsSurfaceColor(SettingsSurface.TOP_BAR))
        view.setBackgroundColor(activity.themedColor(android.R.attr.colorBackground, Color.WHITE))
        bar.addView(titleRow, LinearLayout.LayoutParams(full, wrap))
        body.addView(pageHost, LinearLayout.LayoutParams(0, full, 1f))
        view.addView(bar, LinearLayout.LayoutParams(full, wrap))
        view.addView(warnings, LinearLayout.LayoutParams(full, wrap))
        view.addView(body, LinearLayout.LayoutParams(full, 0, 1f))
        view.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ -> adapt(right - left) }
        adapt(activity.resources.configuration.screenWidthDp.let(activity::dp))
        show(selected)
    }

    private fun icon(res: Int) = activity.getDrawable(res)!!.mutate().apply {
        setTint(activity.themedColor(android.R.attr.textColorPrimary, Color.BLACK))
    }

    private fun adapt(width: Int) {
        val expanded = width >= activity.dp(720)
        if (wide != expanded) {
            wide = expanded
            menuDialog?.dismiss()
            navButtons.clear()
            if (body.childCount > 1) body.removeViewAt(0)
            menu.visibility = if (expanded) View.GONE else View.VISIBLE
            if (expanded) body.addView(navigation(), 0, LinearLayout.LayoutParams(activity.dp(240), full))
        }
    }

    private fun navigation(): ScrollView {
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(16), activity.dp(16), activity.dp(16), activity.dp(16))
        }
        val accent = activity.themedColor(android.R.attr.colorAccent, Color.parseColor("#6750A4"))
        SettingsSection.entries.forEach { section ->
            fun surface(color: Int) = GradientDrawable().apply {
                cornerRadius = activity.dp(12).toFloat()
                setColor(color)
            }
            val states = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), surface((accent and 0x00FFFFFF) or 0x44000000))
                addState(intArrayOf(android.R.attr.state_selected), surface((accent and 0x00FFFFFF) or 0x22000000))
                addState(intArrayOf(), surface(Color.TRANSPARENT))
            }
            val button = Button(activity).apply {
                setText(section.titleRes)
                textSize = 14f
                isAllCaps = false
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                minimumHeight = activity.dp(56)
                setPadding(activity.dp(12), activity.dp(12), activity.dp(12), activity.dp(12))
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon(section.iconRes), null, null, null)
                compoundDrawablePadding = activity.dp(12)
                background = RippleDrawable(ColorStateList.valueOf((accent and 0x00FFFFFF) or 0x33000000), states, null)
                isSelected = selected == section
                tag = "settings_nav_${section.name}"
                setOnClickListener {
                    menuDialog?.dismiss()
                    show(section, resetScroll = true)
                }
            }
            navButtons[section] = button
            column.addView(button, LinearLayout.LayoutParams(full, wrap).apply {
                if (section != SettingsSection.entries.last()) bottomMargin = activity.dp(4)
            })
        }
        return ScrollView(activity).apply {
            tag = "settings_navigation"
            setBackgroundColor(activity.settingsSurfaceColor(SettingsSurface.NAVIGATION))
            isFillViewport = true
            addView(column)
        }
    }

    private fun showMenu() {
        navButtons.clear()
        menuDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.settings_menu)
            .setView(navigation())
            .setNegativeButton(android.R.string.cancel, null)
            .create().also { it.setCanceledOnTouchOutside(true); it.show() }
    }

    fun show(section: SettingsSection, resetScroll: Boolean = false) {
        pages[selected]?.let { positions[selected] = it.scrollY }
        if (resetScroll) positions[section] = 0
        selected = section
        val page = pages.getOrPut(section) {
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(activity.dp(16), activity.dp(16), activity.dp(16), activity.dp(16))
            }
            buildPage(section, content)
            ScrollView(activity).apply {
                tag = "settings_page_${section.name}"
                isFillViewport = true
                addView(content)
            }
        }
        if (page.parent == null) {
            pageHost.removeAllViews()
            pageHost.addView(page, FrameLayout.LayoutParams(full, full))
            page.post { if (page.parent != null) page.scrollTo(0, positions[section] ?: 0) }
        }
        if (resetScroll) {
            page.clearFocus()
            page.scrollTo(0, 0)
        }
        title.setText(section.titleRes)
        navButtons.forEach { (destination, button) -> button.isSelected = destination == section }
        onSelected()
    }

    fun saveState(state: Bundle) {
        pages[selected]?.let { positions[selected] = it.scrollY }
        state.putString("settings_section", selected.name)
        positions.forEach { (section, y) -> state.putInt("settings_scroll_${section.name}", y) }
    }

    fun invalidatePages() {
        pages[selected]?.let { positions[selected] = it.scrollY }
        pageHost.removeAllViews()
        pages.clear()
        show(selected)
    }

    fun dismiss() {
        menuDialog?.dismiss()
    }
}
