package dev.steinhorst.backbuttonhero

import android.app.Activity
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

fun Activity.dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

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

    fun dp(v: Int): Int = activity.dp(v)

    companion object {
        /** Label-to-label pitch of sub-rows stacked under the focus action. */
        const val STACKED_ROW_DP = 28
    }

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

    fun hintText(res: Int) {
        TextView(activity).apply {
            setText(res)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(secondaryTextColor())
            setPadding(dp(4), dp(4), dp(4), dp(16))
        }.also { content.addView(it) }
    }

    /** Starts a new rounded, elevated group; add rows to the returned [Card]. */
    fun card(block: Card.() -> Unit): Card {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
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
    inner class Card(private val container: LinearLayout) {

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

        /** Title + optional supporting text on the left, a [Switch] on the right. The whole row
         *  toggles the switch, so the touch target is the full row height, not just the thumb. */
        fun toggleRow(titleRes: Int, summaryRes: Int?, checked: Boolean, onChange: (Boolean) -> Unit): ToggleHandle {
            lateinit var switch: Switch
            val row = rowContainer(minHeightDp = if (summaryRes != null) 72 else 56) {
                addView(
                    textBlock(titleRes, summaryRes),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                switch = Switch(activity).apply {
                    isChecked = checked
                    setOnCheckedChangeListener { _, on -> onChange(on) }
                }
                addView(switch)
            }
            row.isClickable = true
            row.isFocusable = true
            row.background = selectableBackground()
            row.setOnClickListener { switch.toggle() }
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
                        titleView = TextView(activity).apply { setText(titleRes); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) }
                        addView(titleView)
                        valueView = TextView(activity).apply {
                            text = value
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
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

        /** A secondary, single-line chooser for an option that belongs to the row above it: smaller
         *  muted text, label on the left and current value on the right. */
        fun subChooserRow(titleRes: Int, value: String, onClick: () -> Unit): ChooserHandle {
            lateinit var titleView: TextView
            lateinit var valueView: TextView
            val row = rowContainer(minHeightDp = 48) {
                titleView = TextView(activity).apply {
                    setText(titleRes)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                }
                addView(
                    titleView,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                valueView = TextView(activity).apply {
                    text = value
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                }
                addView(valueView)
                addView(chevron())
            }
            // Tucked up against the row it belongs to; the rest of the touch target extends below.
            row.gravity = Gravity.TOP
            row.setPadding(dp(16), 0, dp(16), dp(8))
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
        fun textRow(text: String): TextView {
            lateinit var label: TextView
            val row = rowContainer(minHeightDp = 48) {
                label = TextView(activity).apply { this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) }
                addView(label)
            }
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
        fun sliderRow(format: Int, min: Int, max: Int, step: Int, value: Long, hintRes: Int?, onChange: (Long) -> Unit) {
            val label = TextView(activity).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) }
            fun show(ms: Int) { label.text = activity.getString(format, ms) }
            val bar = SeekBar(activity).apply {
                this.max = (max - min) / step
                progress = (value.toInt().coerceIn(min, max) - min) / step
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                        val ms = min + progress * step
                        show(ms)
                        if (fromUser) onChange(ms.toLong())
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                })
            }
            show(min + bar.progress * step)
            val column = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(4))
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
            addView(TextView(activity).apply { setText(titleRes); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) })
            summaryRes?.let {
                addView(TextView(activity).apply {
                    setText(it)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(secondaryTextColor())
                    setPadding(0, dp(2), 0, 0)
                })
            }
        }

        private fun chevron(color: Int = secondaryTextColor()) = TextView(activity).apply {
            text = "›"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(color)
            setPadding(dp(8), 0, 0, 0)
        }

        private fun rowContainer(minHeightDp: Int, block: LinearLayout.() -> Unit): LinearLayout =
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(minHeightDp)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                block()
            }
    }
}

const val POSITION_GRID_WIDTH_DP = 224
const val POSITION_GRID_HEIGHT_DP = 126

class PositionGrid(val view: View, val update: (selected: String, enabled: Boolean) -> Unit)

/** A 16:9 screen outline (the top display) holding a 3x3 grid of dots; tapping a dot calls [onSelect].
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
