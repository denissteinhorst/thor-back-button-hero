package dev.steinhorst.backbuttonhero

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

fun Activity.dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

/** Keep the visible range aligned with its labels while the thumb stays inside the track. */
fun SeekBar.alignSettingsRange() {
    setPaddingRelative(0, paddingTop, 0, paddingBottom)
    thumbOffset = 0
    minimumHeight = context.resources.displayMetrics.density.let { (48 * it).toInt() }
}

/** Whether the accessibility service is switched on in Android's settings, independent of
 *  whether it actually bound and responded (see [SwitcherService.isConnected]). */
fun Activity.isAccessibilityServiceEnabled(): Boolean {
    val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    return enabled?.split(':')?.any { it.startsWith("$packageName/") } == true
}

enum class ServiceStatus { ON, UNRESPONSIVE, OFF }

fun Activity.currentServiceStatus(): ServiceStatus = when {
    isAccessibilityServiceEnabled() && SwitcherService.isConnected -> ServiceStatus.ON
    isAccessibilityServiceEnabled() -> ServiceStatus.UNRESPONSIVE
    else -> ServiceStatus.OFF
}

fun Activity.serviceStatusText(status: ServiceStatus): String = when (status) {
    ServiceStatus.ON -> getString(R.string.status_on)
    ServiceStatus.UNRESPONSIVE -> getString(R.string.status_unresponsive)
    ServiceStatus.OFF -> getString(R.string.status_off, getString(R.string.app_name))
}

/**
 * Shared row/card builder for the settings screens: a grouped list of cards, each holding
 * related rows with consistent spacing, touch targets and light/dark-aware colors. Plain
 * framework Views only (no Preference library, no Compose) to match the rest of the app.
 */
class SettingsPage(private val activity: Activity, private val content: LinearLayout) {

    data class ToggleHandle(val row: View, val switch: CompoundButton)
    data class ChooserHandle(val row: View, val title: TextView, val value: TextView)
    data class ActionHandle(val row: View, val title: TextView)
    data class WarningHandle(val row: View, val label: TextView)
    data class FocusOptionsHandle(val row: View, val target: ChooserHandle, val position: ChooserHandle)
    data class SliderHandle(val bar: SeekBar, val label: TextView) {
        fun setEnabled(enabled: Boolean) {
            bar.isEnabled = enabled
            label.alpha = if (enabled) 1f else 0.5f
        }
    }

    fun dp(v: Int): Int = activity.dp(v)

    private val refreshers = mutableListOf<() -> Unit>()

    fun refresh() = refreshers.forEach { it() }

    fun sectionHeader(res: Int) {
        TextView(activity).apply {
            setText(res)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.04f
            setTextColor(accentColor())
            setPadding(dp(4), dp(24), dp(4), dp(8))
        }.also { content.addView(it) }
    }

    fun introduction(
        hintRes: Int,
        actions: List<Pair<Int, () -> Unit>>,
        primary: Int?,
    ) {
        card(activity.settingsSurfaceColor(SettingsSurface.INTRODUCTION)) {
            textRow(activity.getString(hintRes), verticalPaddingDp = 16).apply {
                tag = "settings_page_hint"
                textSize = 16f
                setTextColor(themeColor(android.R.attr.textColorPrimary, Color.BLACK))
                setTypeface(typeface, Typeface.BOLD)
                setLineSpacing(dp(2).toFloat(), 1f)
            }
            if (actions.isNotEmpty()) settingsActions(true, actions, primary)
        }
    }

    /** Starts a new rounded, elevated group; add rows to the returned [Card]. */
    fun card(backgroundColor: Int? = null, block: Card.() -> Unit): Card {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground().apply { backgroundColor?.let(::setColor) }
        }
        content.addView(
            container,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
            },
        )
        return Card(container).apply(block)
    }

    private fun themeColor(attr: Int, fallback: Int): Int = runCatching {
        val ta = activity.theme.obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, fallback)
        ta.recycle()
        color
    }.getOrDefault(fallback)

    private fun accentColor() = themeColor(android.R.attr.colorAccent, Color.parseColor("#6750A4"))
    private fun errorColor() = themeColor(android.R.attr.colorError, Color.parseColor("#B3261E"))
    private fun secondaryTextColor() = themeColor(android.R.attr.textColorSecondary, Color.GRAY)

    private fun cardBackground() = GradientDrawable().apply {
        setColor(themeColor(android.R.attr.colorBackgroundFloating, Color.parseColor("#F3F3F3")))
        cornerRadius = dp(16).toFloat()
    }

    private fun selectableBackground() = TypedValue().let { value ->
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        activity.getDrawable(value.resourceId)
    }

    /** One grouped card of related rows, each separated by a hairline divider. */
    inner class Card(private val container: LinearLayout, private val subordinate: Boolean = false) {

        private fun addRow(view: View) {
            if (container.childCount > 0) {
                container.addView(
                    View(activity).apply { setBackgroundColor(secondaryTextColor()); alpha = 0.12f },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                        marginStart = dp(16)
                        marginEnd = dp(16)
                    },
                )
            }
            container.addView(view)
        }

        fun focusOptions(
            targetValue: String,
            positionValue: String,
            onTarget: () -> Unit,
            onPosition: () -> Unit,
        ): FocusOptionsHandle {
            lateinit var target: ChooserHandle
            lateinit var position: ChooserHandle
            val group = subOptions {
                target = chooserRow(R.string.focus_target_title, targetValue, onTarget)
                position = chooserRow(R.string.focus_position_title, positionValue, onPosition)
            }.apply { tag = "settings_focus_options" }
            target.row.tag = "settings_focus_target"
            position.row.tag = "settings_focus_indicator"
            return FocusOptionsHandle(group, target, position)
        }

        /** Dependent controls share the focus options' branches, spacing and trailing edge. */
        fun subOptions(endControl: View? = null, block: Card.() -> Unit): View {
            endControl?.let { control ->
                val row = control.parent as ViewGroup
                val text = row.getChildAt(0)
                text.setPaddingRelative(text.paddingStart, text.paddingTop, dp(12), text.paddingBottom)
            }
            val children = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            Card(children, subordinate = true).apply(block)
            val rows = (0 until children.childCount).map { children.getChildAt(it) }
                .filter { it.layoutParams.height != dp(1) }
            return SettingsOptionsTree(activity).apply {
                tag = "settings_sub_options"
                setPaddingRelative(dp(16), 0, 0, dp(8))
                trailingControl = endControl
                setOptions(children, rows, secondaryTextColor())
                container.addView(this)
            }
        }

        fun customRow(view: View) = addRow(view)

        fun settingsActions(
            enabled: Boolean,
            actions: List<Pair<Int, () -> Unit>>,
            primary: Int?,
        ) {
            val row = LinearLayout(activity).apply {
                tag = "settings_page_actions"
                isBaselineAligned = false
                setPadding(dp(4), 0, dp(4), dp(4))
            }
            val colors = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(accentColor(), secondaryTextColor()),
            )
            actions.forEach { (label, action) ->
                row.addView(Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
                    setText(label)
                    isAllCaps = false
                    textSize = 14f
                    setTextColor(colors)
                    minimumHeight = dp(48)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    gravity = Gravity.CENTER
                    background = selectableBackground()
                    // The page's main action stands out by weight alone.
                    if (label == primary) setTypeface(typeface, Typeface.BOLD)
                    isEnabled = enabled
                    alpha = if (enabled) 1f else 0.5f
                    setOnClickListener { action() }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            addRow(row)
        }

        /** Title + optional supporting text on the left, a [Switch] on the right. The whole row
         *  toggles the switch, so the touch target is the full row height, not just the thumb. */
        fun toggleRow(titleRes: Int, summaryRes: Int?, checked: Boolean, readValue: (() -> Boolean)? = null, onChange: (Boolean) -> Unit): ToggleHandle {
            lateinit var switch: Switch
            var refreshing = false
            val row = rowContainer(minHeightDp = if (summaryRes != null) 72 else 56) {
                addView(
                    textBlock(titleRes, summaryRes),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                switch = Switch(activity).apply {
                    isChecked = checked
                    setOnCheckedChangeListener { _, on -> if (!refreshing) onChange(on) }
                }
                addView(switch)
            }
            row.isClickable = true
            row.isFocusable = true
            row.background = selectableBackground()
            row.setOnClickListener { switch.toggle() }
            readValue?.let { read ->
                refreshers += {
                    refreshing = true
                    switch.isChecked = read()
                    refreshing = false
                }
            }
            switch.minimumHeight = dp(48)
            switch.minimumWidth = dp(48)
            switch.contentDescription = activity.getString(titleRes)
            addRow(row)
            return ToggleHandle(row, switch)
        }

        /** Title + current value with a chevron; tapping anywhere on the row runs [onClick]
         *  (used both for choice dialogs and for navigating to a sub-screen). */
        fun chooserRow(titleRes: Int, value: String, onClick: () -> Unit): ChooserHandle {
            lateinit var titleView: TextView
            lateinit var valueView: TextView
            val row = rowContainer(minHeightDp = 56) {
                addView(
                    LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        titleView = TextView(activity).apply { setText(titleRes); setTextSize(TypedValue.COMPLEX_UNIT_SP, if (subordinate) 14f else 16f) }
                        addView(titleView)
                        valueView = TextView(activity).apply {
                            text = value
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (subordinate) 13f else 14f)
                            setTextColor(secondaryTextColor())
                            setPadding(0, dp(2), 0, 0)
                        }
                        addView(valueView)
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(chevron())
            }
            row.isClickable = true
            row.isFocusable = true
            row.background = selectableBackground()
            row.setOnClickListener { onClick() }
            addRow(row)
            return ChooserHandle(row, titleView, valueView)
        }

        /** A tappable action line, optionally with supporting text below the title. */
        fun actionRow(titleRes: Int, summaryRes: Int? = null, onClick: () -> Unit): ActionHandle {
            lateinit var title: TextView
            val row = rowContainer(minHeightDp = if (summaryRes != null) 72 else 56) {
                val block = textBlock(titleRes, summaryRes)
                title = block.getChildAt(0) as TextView
                addView(block, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            row.isClickable = true
            row.isFocusable = true
            row.background = selectableBackground()
            row.setOnClickListener { onClick() }
            addRow(row)
            return ActionHandle(row, title)
        }

        /** A static, non-interactive line of text (status, version, ...). */
        fun textRow(text: String, verticalPaddingDp: Int = 10): TextView {
            lateinit var label: TextView
            val row = rowContainer(minHeightDp = 48) {
                label = TextView(activity).apply { this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) }
                addView(label)
            }
            row.setPadding(row.paddingLeft, dp(verticalPaddingDp), row.paddingRight, dp(verticalPaddingDp))
            addRow(row)
            return label
        }

        /** The one row style meant to stand out: used only for a service problem that needs
         *  attention. Purely informational unless [onClick] is given. */
        fun warningRow(text: String, onClick: (() -> Unit)? = null): WarningHandle {
            lateinit var label: TextView
            val row = rowContainer(minHeightDp = 56) {
                label = TextView(activity).apply {
                    this.text = text
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(errorColor())
                    setTypeface(typeface, Typeface.BOLD)
                }
                addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (onClick != null) addView(chevron(errorColor()))
            }
            if (onClick != null) {
                row.isClickable = true
                row.isFocusable = true
                row.background = selectableBackground()
                row.setOnClickListener { onClick() }
            }
            addRow(row)
            return WarningHandle(row, label)
        }

        /** A labelled, snapping [SeekBar]; [format] receives the live value in ms and supplies
         *  both the row's title and its current-value text, exactly like the preference it edits. */
        fun sliderRow(format: Int, min: Int, max: Int, step: Int, value: Long, hintRes: Int?, readValue: (() -> Long)? = null, onChange: (Long) -> Unit): SliderHandle {
            val label = TextView(activity).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, if (subordinate) 14f else 15f) }
            fun show(ms: Int) { label.text = activity.getString(format, ms) }
            val bar = SeekBar(activity).apply {
                alignSettingsRange()
                this.max = (max - min) / step
                progress = (value.toInt().coerceIn(min, max) - min) / step
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        val ms = min + progress * step
                        show(ms)
                        bar.contentDescription = label.text
                        if (fromUser) onChange(ms.toLong())
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                })
            }
            show(min + bar.progress * step)
            bar.minimumHeight = dp(48)
            bar.contentDescription = label.text
            readValue?.let { read ->
                refreshers += {
                    bar.progress = (read().toInt().coerceIn(min, max) - min) / step
                    bar.contentDescription = label.text
                }
            }
            val column = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPaddingRelative(dp(if (subordinate) 8 else 16), dp(if (subordinate) 6 else 12), dp(16), dp(4))
                addView(label)
                addView(
                    bar,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(4)
                    },
                )
                hintRes?.let {
                    addView(TextView(activity).apply {
                        setText(it)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTextColor(secondaryTextColor())
                        setPadding(0, dp(4), 0, dp(8))
                    })
                }
            }
            addRow(column)
            return SliderHandle(bar, label)
        }

        /** A clickable, underlined link line. */
        fun linkRow(text: String, contentDescriptionRes: Int, onClick: () -> Unit) {
            val row = rowContainer(minHeightDp = 48) {
                addView(TextView(activity).apply {
                    this.text = text
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                    setTextColor(accentColor())
                })
            }
            row.isClickable = true
            row.isFocusable = true
            row.background = selectableBackground()
            row.contentDescription = activity.getString(contentDescriptionRes)
            row.setOnClickListener { onClick() }
            addRow(row)
        }

        private fun textBlock(titleRes: Int, summaryRes: Int?): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply { setText(titleRes); setTextSize(TypedValue.COMPLEX_UNIT_SP, if (subordinate) 14f else 16f) })
            summaryRes?.let {
                addView(TextView(activity).apply {
                    setText(it)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(secondaryTextColor())
                    setPadding(0, dp(2), 0, 0)
                })
            }
        }

        private fun chevron(color: Int = secondaryTextColor()) = ImageView(activity).apply {
            tag = "settings_chooser_chevron"
            setImageDrawable(activity.getDrawable(R.drawable.ic_settings_chevron)!!.mutate().apply { setTint(color) })
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(24))
            setPadding(dp(8), 0, 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        private fun rowContainer(minHeightDp: Int, block: LinearLayout.() -> Unit): LinearLayout =
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(if (subordinate) 48 else minHeightDp)
                setPaddingRelative(dp(if (subordinate) 8 else 16), dp(if (subordinate) 6 else 10), dp(16), dp(if (subordinate) 6 else 10))
                block()
            }
    }
}

/** Decorative branches follow the actual row heights, including wrapped text and large fonts. */
private class SettingsOptionsTree(context: Context) : FrameLayout(context) {
    var trailingControl: View? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        trailingControl?.let { control ->
            // The preceding option has already measured its trailing control at this font scale.
            val end = control.measuredWidth + dp(12)
            if (paddingEnd != end) setPaddingRelative(paddingStart, paddingTop, end, paddingBottom)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val stemInset = dp(8).toFloat()
    private val branchWidth = dp(24).toFloat()
    private var rows: List<View> = emptyList()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(1).toFloat()
        strokeCap = Paint.Cap.SQUARE
    }

    fun setOptions(children: View, options: List<View>, color: Int) {
        rows = options
        line.color = color
        line.alpha = 112
        removeAllViews()
        addView(children, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = branchWidth.toInt()
        })
    }

    override fun dispatchDraw(canvas: Canvas) {
        val children = getChildAt(0)
        if (children != null && rows.isNotEmpty()) {
            val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
            val start = if (rtl) width - paddingRight.toFloat() else paddingLeft.toFloat()
            val stem = start + if (rtl) -stemInset else stemInset
            val end = start + if (rtl) -branchWidth else branchWidth
            val last = rows.last()
            canvas.drawLine(stem, paddingTop.toFloat(), stem, children.top + last.top + last.height / 2f, line)
            for (i in rows.indices) {
                val row = rows[i]
                val middle = children.top + row.top + row.height / 2f
                canvas.drawLine(stem, middle, end, middle, line)
            }
        }
        super.dispatchDraw(canvas)
    }
}

const val POSITION_GRID_WIDTH_DP = 288
const val POSITION_GRID_HEIGHT_DP = 162

class PositionGrid(val view: View, val update: (selected: String, enabled: Boolean) -> Unit)

/** A screen outline holding a 3x3 grid of dots; tapping a dot calls [onSelect].
 *  [positions] lists the nine values row by row, each with its spoken label. [PositionGrid.update]
 *  moves the highlight and dims the grid, ignoring taps, while it is disabled. */
fun Activity.positionGrid(
    positions: List<Pair<String, String>>,
    selectedLabel: (String) -> String,
    onSelect: (String) -> Unit,
): PositionGrid {
    val accent = themedColor(android.R.attr.colorAccent, Color.parseColor("#6750A4"))
    val idle = themedColor(android.R.attr.textColorSecondary, Color.GRAY)
    val cells = LinkedHashMap<String, Pair<View, GradientDrawable>>()
    var enabled = true
    val screen = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = GradientDrawable().apply {
            setStroke(dp(2), idle)
            cornerRadius = dp(10).toFloat()
        }
        positions.chunked(3).forEach { line ->
            addView(
                LinearLayout(context).apply {
                    line.forEach { (value, label) ->
                        val dot = GradientDrawable().apply { shape = GradientDrawable.OVAL }
                        val cell = FrameLayout(context).apply {
                            contentDescription = label
                            setOnClickListener { if (enabled) onSelect(value) }
                            addView(View(context).apply { background = dot }, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
                        }
                        cells[value] = cell to dot
                        addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }
    }
    return PositionGrid(screen) { selected, isEnabled ->
        enabled = isEnabled
        screen.alpha = if (enabled) 1f else 0.35f
        cells.forEach { (value, pair) ->
            val (cell, dot) = pair
            val on = value == selected
            dot.setColor(if (on) accent else Color.TRANSPARENT)
            dot.setStroke(dp(2), if (on) accent else idle)
            cell.isClickable = enabled
            cell.isFocusable = enabled
            val label = positions.first { it.first == value }.second
            cell.contentDescription = if (on) selectedLabel(label) else label
        }
    }
}

fun Activity.themedColor(attr: Int, fallback: Int): Int = runCatching {
    val ta = theme.obtainStyledAttributes(intArrayOf(attr))
    ta.getColor(0, fallback).also { ta.recycle() }
}.getOrDefault(fallback)

/** Distinct, opaque surfaces derived from the current theme, without per-frame drawing work. */
enum class SettingsSurface { TOP_BAR, NAVIGATION, INTRODUCTION }

fun Activity.settingsSurfaceColor(surface: SettingsSurface): Int {
    val background = themedColor(android.R.attr.colorBackground, Color.WHITE)
    val text = themedColor(android.R.attr.textColorPrimary, Color.BLACK)
    val accent = themedColor(android.R.attr.colorAccent, Color.parseColor("#6750A4"))
    val foreground = if (surface == SettingsSurface.INTRODUCTION) accent else text
    val fraction = when (surface) {
        SettingsSurface.TOP_BAR -> 0.16f
        SettingsSurface.NAVIGATION -> 0.08f
        SettingsSurface.INTRODUCTION -> 0.12f
    }
    fun channel(back: Int, front: Int) = (back + (front - back) * fraction + 0.5f).toInt()
    return Color.rgb(channel(Color.red(background), Color.red(foreground)),
        channel(Color.green(background), Color.green(foreground)),
        channel(Color.blue(background), Color.blue(foreground)))
}
