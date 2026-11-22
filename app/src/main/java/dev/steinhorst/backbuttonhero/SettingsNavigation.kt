package dev.steinhorst.backbuttonhero

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.steinhorst.backbuttonhero.macro.space

enum class SettingsSection(val titleRes: Int, val hintRes: Int, val iconRes: Int) {
    OVERVIEW(R.string.nav_overview, R.string.nav_overview_hint, R.drawable.ic_settings_overview),
    BASIC(R.string.nav_basic, R.string.nav_basic_hint, R.drawable.ic_settings_android),
    FACE(R.string.nav_face, R.string.nav_face_hint, R.drawable.ic_settings_face),
    SHOULDERS(R.string.nav_shoulders, R.string.nav_shoulders_hint, R.drawable.ic_settings_shoulders),
    STICKS(R.string.nav_sticks, R.string.nav_sticks_hint, R.drawable.ic_settings_stick),
    DPAD(R.string.nav_dpad, R.string.nav_dpad_hint, R.drawable.ic_settings_dpad),
    EXCEPTIONS(R.string.nav_exceptions, R.string.nav_exceptions_hint, R.drawable.ic_settings_exceptions),
    SETTINGS(R.string.nav_settings, R.string.nav_settings_hint, R.drawable.ic_settings_gear),
    HELP(R.string.nav_help, R.string.nav_help_hint, R.drawable.ic_settings_help),
}

/**
 * The wide rail's column. The keyboard shrinks the window so a page's field stays in view; the rail
 * keeps the height it had without it instead, so its entries hold their size and the lowest simply
 * sit behind the keyboard until it goes.
 */
internal class RailColumn @JvmOverloads constructor(
    context: Context,
    private val keyboardUp: (View) -> Boolean = { it.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true },
) : LinearLayout(context) {
    private var fullHeight = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val offered = MeasureSpec.getSize(heightMeasureSpec)
        if (!keyboardUp(this)) fullHeight = offered
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxOf(offered, fullHeight), MeasureSpec.EXACTLY))
    }
}

/** Keeps the navigation chrome independent of lazily built, cached settings pages. */
class SettingsNavigation(
    private val activity: Activity,
    savedState: Bundle?,
    val warnings: LinearLayout,
    private val serviceWarning: TextView,
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
    /** The page name and service chip laid over the overview; it lets every other touch through. */
    private val floating = LinearLayout(activity).apply {
        tag = "settings_floating_header"
        gravity = Gravity.CENTER_VERTICAL
        setPadding(activity.dp(8), activity.dp(4), activity.dp(8), activity.dp(4))
    }
    private var menuDialog: AlertDialog? = null
    private var wide: Boolean? = null
    /** The rail opened by one of its icons; the full panel lies over the page until the page is used. */
    private var peeking = false
    /** The press that closed a peek, kept until it either ends as a tap or moves far enough to scroll. */
    private var held: MotionEvent? = null
    private val slop = ViewConfiguration.get(activity).scaledTouchSlop
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
    /** The app's name beside the logo at the panel's top; the rail shows the logo alone. */
    private var logoName: TextView? = null
    var selected: SettingsSection = SettingsSection.entries.firstOrNull {
        it.name == savedState?.getString("settings_section")
    } ?: SettingsSection.OVERVIEW
        private set

    val view = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }

    init {
        SettingsSection.entries.forEach { positions[it] = savedState?.getInt("settings_scroll_${it.name}") ?: 0 }
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.minimumHeight = activity.dp(56)
        bar.setPadding(activity.dp(8), activity.dp(4), activity.dp(8), activity.dp(4))
        titleRow.addView(menu)
        titleRow.addView(appIcon, LinearLayout.LayoutParams(activity.dp(32), activity.dp(32)).apply {
            marginStart = activity.dp(8)
        })
        titleRow.addView(title,LinearLayout.LayoutParams(0, wrap, 1f))
        titleRow.addView(serviceWarning, chipParams())
        bar.tag = "settings_top_bar"
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
            if (focused != null && !contains(navHost, focused)) endPeek()
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
            peeking = false
            if (expanded) {
                navHost.addView(navigation(rail = true), FrameLayout.LayoutParams(full, full))
                navHost.addView(navScrim, FrameLayout.LayoutParams(full, full))
                view.addView(navHost, 0, LinearLayout.LayoutParams(panelWidth, full))
                setBlocked(blocker.visibility == View.VISIBLE)
            }
            applyCollapse()
            applyBar()
        }
    }

    /**
     * The wide layout has the rail to name and switch pages, so it drops the header band: only the page's
     * name stays at the top left, with the service chip at the right. The overview needs every pixel of
     * height for its drawing, so there the two float over the page's top edge instead of taking a row.
     */
    private fun applyBar() {
        val bare = wide == true
        val hidden = bare && selected == SettingsSection.OVERVIEW
        bar.visibility = if (hidden) View.GONE else View.VISIBLE
        bar.background = if (bare) null else ColorDrawable(activity.settingsSurfaceColor(SettingsSurface.TOP_BAR))
        bar.minimumHeight = if (bare) 0 else activity.dp(56)
        appIcon.visibility = if (bare) View.GONE else View.VISIBLE
        title.textSize = if (bare) 18f else 20f
        val vertical = activity.dp(if (bare) 4 else 8)
        title.setPadding(activity.dp(8), vertical, activity.dp(8), vertical)
        pageHost.accessibilityPaneTitle = if (hidden) activity.getString(selected.titleRes) else null
        if (hidden == (serviceWarning.parent === floating)) {
            if (hidden && floating.parent == null) pageHost.addView(floating, FrameLayout.LayoutParams(full, wrap, Gravity.TOP))
            return
        }
        (serviceWarning.parent as? ViewGroup)?.removeView(serviceWarning)
        (title.parent as? ViewGroup)?.removeView(title)
        if (hidden) {
            floating.addView(title, LinearLayout.LayoutParams(0, wrap, 1f))
            floating.addView(serviceWarning, chipParams())
            pageHost.addView(floating, FrameLayout.LayoutParams(full, wrap, Gravity.TOP))
        } else {
            floating.removeAllViews()
            pageHost.removeView(floating)
            titleRow.addView(title, 2, LinearLayout.LayoutParams(0, wrap, 1f))
            titleRow.addView(serviceWarning, chipParams())
        }
    }

    private fun chipParams() = LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = activity.dp(8); marginEnd = activity.dp(8) }

    private fun endPeek() {
        if (!peeking) return
        peeking = false
        applyCollapse()
    }

    /**
     * Called with every touch reaching the window, before the views see it; true means the window keeps
     * [event] to itself. A finger landing on the page beside a peek only closes it: the gesture is held
     * back, so a tap opens nothing, and handed to the page through [deliver] once it turns into a scroll.
     */
    fun onWindowTouch(event: MotionEvent, deliver: (MotionEvent) -> Unit): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            held?.recycle()
            held = null
            if (!peeking || hits(navHost, event)) return false
            endPeek()
            held = MotionEvent.obtain(event)
            return true
        }
        val down = held ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (Math.hypot((event.rawX - down.rawX).toDouble(), (event.rawY - down.rawY).toDouble()) > slop) {
                held = null
                deliver(down)
                down.recycle()
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                held = null
                down.recycle()
            }
        }
        return true
    }

    private fun hits(target: View, event: MotionEvent): Boolean {
        val at = IntArray(2).also(target::getLocationOnScreen)
        return event.rawX >= at[0] && event.rawX < at[0] + target.width &&
            event.rawY >= at[1] && event.rawY < at[1] + target.height
    }

    private fun contains(parent: ViewGroup, child: View): Boolean =
        generateSequence(child.parent) { it.parent }.any { it === parent }

    private fun applyCollapse() {
        val rail = !peeking
        navButtons.forEach { (section, button) -> style(button, section, rail) }
        logoName?.visibility = if (rail) View.GONE else View.VISIBLE
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

    /**
     * The menu's entries. The wide [rail] panel carries the logo on top and never scrolls: its pages
     * split the height left below logo and rule, at least enough for the Thor's top screen; the
     * compact menu dialog may scroll.
     */
    private fun navigation(rail: Boolean): View {
        // The entries span the column's full width and touch each other, so no tap beside an icon or
        // between two rows lands on dead space; their backgrounds keep the visible 16 dp side and 2 dp gap.
        val column = (if (rail) RailColumn(activity) else LinearLayout(activity)).apply {
            orientation = LinearLayout.VERTICAL
            val vertical = activity.dp(if (rail) 8 else 16)
            setPadding(0, vertical, 0, vertical - activity.dp(GAP_DP / 2))
        }
        val side = activity.dp(16)
        val accent = activity.themedColor(android.R.attr.colorAccent, Color.parseColor("#6750A4"))
        val entryHeight = activity.dp(if (rail) ENTRY_DP else 44) + activity.dp(GAP_DP)
        logoName = null
        if (rail) {
            // Centred over the rail's icons: 16 dp side + 6 dp + half of 32 dp = the rail's middle.
            val logo = LinearLayout(activity).apply {
                tag = "settings_nav_logo"
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(activity).apply {
                    setImageResource(R.mipmap.ic_launcher)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(activity.dp(32), activity.dp(32)).apply { marginStart = side + activity.dp(6) })
                addView(TextView(activity).apply {
                    setText(R.string.app_name)
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(activity.themedColor(android.R.attr.textColorPrimary, Color.BLACK))
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    logoName = this
                }, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = activity.dp(12) })
            }
            column.addView(logo, LinearLayout.LayoutParams(full, activity.dp(LOGO_DP)))
            // The logo names the app; a rule sets it apart from the pages.
            column.addView(View(activity).apply {
                tag = "settings_nav_divider"
                setBackgroundColor((activity.themedColor(android.R.attr.textColorPrimary, Color.BLACK) and 0x00FFFFFF) or 0x33000000)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(full, activity.dp(1)).apply {
                topMargin = activity.dp(4)
                bottomMargin = activity.dp(4) - activity.dp(GAP_DP / 2)
                marginStart = side
                marginEnd = side
            })
        }
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
                // The Button style's own 48 dp minimum would push the rail's rows off the screen.
                minHeight = entryHeight
                minimumHeight = entryHeight
                setCompoundDrawablesRelative(icon(section.iconRes).apply { setBounds(0, 0, activity.dp(20), activity.dp(20)) }, null, null, null)
                style(this, section, rail = false)
                val gap = activity.dp(GAP_DP / 2)
                background = InsetDrawable(RippleDrawable(ColorStateList.valueOf((accent and 0x00FFFFFF) or 0x33000000), states, null), side, gap, side, gap)
                // After the background, which would otherwise replace the padding with its own insets.
                setPadding(side + activity.dp(12), 0, side + activity.dp(12), 0)
                isSelected = selected == section
                tag = "settings_nav_${section.name}"
                setOnClickListener {
                    menuDialog?.dismiss()
                    if (!peeking && wide == true) {
                        peeking = true
                        applyCollapse()
                    }
                    show(section, resetScroll = true)
                }
                // The rail's tooltip would otherwise take a slow tap as a long press and drop the click.
                setOnLongClickListener { performClick() }
            }
            navButtons[section] = button
            // In the rail the pages share all the height below the logo; 36 dp each is the floor.
            column.addView(button, LinearLayout.LayoutParams(full, if (rail) 0 else wrap, if (rail) 1f else 0f))
        }
        if (rail) logoName?.visibility = if (peeking) View.VISIBLE else View.GONE
        if (rail) return column.apply {
            tag = "settings_navigation"
            setBackgroundColor(activity.settingsSurfaceColor(SettingsSurface.NAVIGATION))
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
            .setView(navigation(rail = false))
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
        applyBar()
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
        // Least the wide panel needs: 8 + 40 logo + 9 rule + 9 x (36 + 2) + 8 - 2 = 405 dp of the Thor's 421 dp.
        const val LOGO_DP = 40
        const val ENTRY_DP = 36
        const val GAP_DP = 2
    }
}
