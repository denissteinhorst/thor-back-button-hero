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
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

enum class SettingsSection(val titleRes: Int, val hintRes: Int, val iconRes: Int) {
    BASIC(R.string.nav_basic, R.string.nav_basic_hint, R.drawable.ic_settings_android),
    FACE(R.string.nav_face, R.string.nav_face_hint, R.drawable.ic_settings_face),
    SHOULDERS(R.string.nav_shoulders, R.string.nav_shoulders_hint, R.drawable.ic_settings_shoulders),
    STICKS(R.string.nav_sticks, R.string.nav_sticks_hint, R.drawable.ic_settings_stick),
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
    blockerCard: View,
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
    // Everything below the header, so the blocker can sit over the warnings and the page alike.
    private val below = FrameLayout(activity)
    private val belowColumn = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    /** A tinted scrim with one card in its middle; it swallows every touch, so the card is the only way on. */
    private val blocker = FrameLayout(activity).apply {
        tag = "settings_service_blocker"
        setBackgroundColor(Color.argb(170, 0, 0, 0))
        isClickable = true
        isFocusable = true
        visibility = View.GONE
        addView(blockerCard, FrameLayout.LayoutParams(activity.dp(360), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }
    /** The wide layout's menu column: the full window height, beside the header and the page. */
    private val navHost = FrameLayout(activity)
    // The menu sits beside the header now, so the blocker cannot reach it; this scrim covers it instead.
    private val navScrim = View(activity).apply {
        tag = "settings_navigation_scrim"
        setBackgroundColor(Color.argb(170, 0, 0, 0))
        isClickable = true
        isFocusable = true
        visibility = View.GONE
    }
    /** The header and everything below it. */
    private val main = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val bar = LinearLayout(activity)
    private val titleRow = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
    private var menuDialog: AlertDialog? = null
    private var wide: Boolean? = null
    private val prefs = Prefs(activity)
    /** The owner's choice for the wide panel; only the chevron changes it. */
    private var collapsed = prefs.navigationCollapsed
    /** A collapsed panel opened by one of its icons; it lies over the page until the page is used. */
    private var peeking = false
    private val railWidth = activity.dp(RAIL_DP)
    private val panelWidth = activity.dp(PANEL_DP)
    private val title = TextView(activity).apply {
        textSize = 20f
        setTextColor(activity.themedColor(android.R.attr.textColorPrimary, Color.BLACK))
        setTypeface(typeface, Typeface.BOLD)
        isAccessibilityHeading = true
        setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(8))
    }
    // Decorative: the page title next to it already names the screen for TalkBack.
    private val appIcon = ImageView(activity).apply {
        tag = "settings_app_icon"
        setImageResource(R.mipmap.ic_launcher)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
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
    private val toggle = ImageButton(activity).apply {
        tag = "settings_nav_toggle"
        setImageDrawable(icon(R.drawable.ic_settings_chevron))
        background = activity.getDrawable(TypedValue().also {
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
        }.resourceId)
        minimumWidth = activity.dp(48)
        minimumHeight = activity.dp(48)
        // From a peek this pins the panel open, so the page the icon led to stays readable.
        setOnClickListener { setCollapsed(!collapsed) }
    }
    var selected: SettingsSection = SettingsSection.entries.firstOrNull {
        it.name == savedState?.getString("settings_section")
    } ?: SettingsSection.BASIC
        private set

    val view = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }

    init {
        SettingsSection.entries.forEach { positions[it] = savedState?.getInt("settings_scroll_${it.name}") ?: 0 }
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.minimumHeight = activity.dp(56)
        bar.setPadding(activity.dp(8), activity.dp(4), activity.dp(8), activity.dp(4))
        titleRow.addView(toggle)
        titleRow.addView(menu)
        titleRow.addView(appIcon, LinearLayout.LayoutParams(activity.dp(32), activity.dp(32)).apply {
            marginStart = activity.dp(8)
        })
        titleRow.addView(title,LinearLayout.LayoutParams(0, wrap, 1f))
        titleRow.addView(serviceWarning, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = activity.dp(8); marginEnd = activity.dp(8) })
        bar.tag = "settings_top_bar"
        bar.setBackgroundColor(activity.settingsSurfaceColor(SettingsSurface.TOP_BAR))
        view.setBackgroundColor(activity.themedColor(android.R.attr.colorBackground, Color.WHITE))
        bar.addView(titleRow, LinearLayout.LayoutParams(full, wrap))
        body.addView(pageHost, LinearLayout.LayoutParams(0, full, 1f))
        main.addView(bar, LinearLayout.LayoutParams(full, wrap))
        belowColumn.addView(warnings, LinearLayout.LayoutParams(full, wrap))
        belowColumn.addView(body, LinearLayout.LayoutParams(full, 0, 1f))
        below.addView(belowColumn, FrameLayout.LayoutParams(full, full))
        below.addView(blocker, FrameLayout.LayoutParams(full, full))
        main.addView(below, LinearLayout.LayoutParams(full, 0, 1f))
        view.addView(main, LinearLayout.LayoutParams(0, full, 1f))
        view.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ -> adapt(right - left) }
        // A controller moving on into the page counts as using it, like a touch there.
        view.viewTreeObserver.addOnGlobalFocusChangeListener { _, focused ->
            if (focused != null && focused !== toggle && !contains(navHost, focused)) endPeek()
        }
        navHost.outlineProvider = ViewOutlineProvider.BOUNDS
        adapt(activity.resources.configuration.screenWidthDp.let(activity::dp))
        show(selected)
    }

    /** While blocked, nothing behind the scrim is reachable by touch, focus or a screen reader. */
    fun setBlocked(blocked: Boolean) {
        blocker.visibility = if (blocked) View.VISIBLE else View.GONE
        navScrim.visibility = blocker.visibility
        listOfNotNull(belowColumn, navHost.getChildAt(0) as? ViewGroup).forEach { column ->
            column.importantForAccessibility =
                if (blocked) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            column.descendantFocusability = if (blocked) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
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
            navHost.removeAllViews()
            view.removeView(navHost)
            menu.visibility = if (expanded) View.GONE else View.VISIBLE
            toggle.visibility = if (expanded) View.VISIBLE else View.GONE
            peeking = false
            if (expanded) {
                navHost.addView(navigation(), FrameLayout.LayoutParams(full, full))
                navHost.addView(navScrim, FrameLayout.LayoutParams(full, full))
                view.addView(navHost, 0, LinearLayout.LayoutParams(panelWidth, full))
                setBlocked(blocker.visibility == View.VISIBLE)
            }
            applyCollapse()
        }
    }

    private fun setCollapsed(value: Boolean) {
        collapsed = value
        prefs.navigationCollapsed = value
        peeking = false
        applyCollapse()
    }

    private fun endPeek() {
        if (!peeking) return
        peeking = false
        applyCollapse()
    }

    /** Called with every touch reaching the window, before the views see it. */
    fun onWindowTouch(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !hits(navHost, event) && !hits(toggle, event)) endPeek()
    }

    private fun hits(target: View, event: MotionEvent): Boolean {
        val at = IntArray(2).also(target::getLocationOnScreen)
        return event.rawX >= at[0] && event.rawX < at[0] + target.width &&
            event.rawY >= at[1] && event.rawY < at[1] + target.height
    }

    private fun contains(parent: ViewGroup, child: View): Boolean =
        generateSequence(child.parent) { it.parent }.any { it === parent }

    private fun applyCollapse() {
        val rail = collapsed && !peeking
        toggle.rotation = if (collapsed) 0f else 180f
        toggle.contentDescription = activity.getString(if (collapsed) R.string.settings_nav_expand else R.string.settings_nav_collapse)
        navButtons.forEach { (section, button) -> style(button, section, rail) }
        (navHost.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.width = if (rail) railWidth else panelWidth
            navHost.layoutParams = it
        }
        // A peek keeps the page where the rail left it and draws the full panel over it instead.
        (main.layoutParams as LinearLayout.LayoutParams).let {
            it.marginStart = if (peeking) railWidth - panelWidth else 0
            main.layoutParams = it
        }
        navHost.elevation = if (peeking) activity.dp(8).toFloat() else 0f
    }

    private fun style(button: Button, section: SettingsSection, rail: Boolean) {
        val label = activity.getString(section.titleRes)
        button.text = if (rail) "" else label
        button.contentDescription = if (rail) label else null
        button.tooltipText = if (rail) label else null
        button.gravity = if (rail) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
        button.compoundDrawablePadding = if (rail) 0 else activity.dp(12)
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
                textSize = 14f
                isAllCaps = false
                minimumHeight = activity.dp(44)
                setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8))
                // Eight entries must fit the full-height panel without scrolling on the Thor's top screen.
                setCompoundDrawablesRelative(icon(section.iconRes).apply { setBounds(0, 0, activity.dp(20), activity.dp(20)) }, null, null, null)
                style(this, section, rail = false)
                background = RippleDrawable(ColorStateList.valueOf((accent and 0x00FFFFFF) or 0x33000000), states, null)
                isSelected = selected == section
                tag = "settings_nav_${section.name}"
                setOnClickListener {
                    menuDialog?.dismiss()
                    if (collapsed && !peeking && wide == true) {
                        peeking = true
                        applyCollapse()
                    }
                    show(section, resetScroll = true)
                }
            }
            navButtons[section] = button
            column.addView(button, LinearLayout.LayoutParams(full, wrap).apply {
                if (section != SettingsSection.entries.last()) bottomMargin = activity.dp(2)
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

    private companion object {
        const val PANEL_DP = 240
        // Column and button padding around the 20 dp icon: 16 + 12 + 20 + 12 + 16.
        const val RAIL_DP = 76
    }
}
