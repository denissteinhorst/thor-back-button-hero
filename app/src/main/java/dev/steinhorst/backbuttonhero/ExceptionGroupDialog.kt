package dev.steinhorst.backbuttonhero

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * One input group of one app's exception as a modal: a switch per control, each with a glyph and a
 * name, under an all-switch. Every switch writes through [onChange] at once; the dialog itself only
 * mirrors the current exception, so the page behind it never disagrees with what is shown here.
 */
class ExceptionGroupDialog(
    private val activity: Activity,
    private val group: ExceptionGroup,
    private val packageName: String,
    private val current: () -> AppException?,
    private val onChange: (Int) -> Unit,
) {
    private class RowViews(val row: View, val toggle: Switch)
    private class SectionViews(val group: List<ExceptionControl>, val toggle: Switch, val count: TextView)

    private val full = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val rows = LinkedHashMap<ExceptionControl, RowViews>()
    private val sections = mutableListOf<SectionViews>()
    private lateinit var allToggle: Switch
    private lateinit var allCount: TextView
    private lateinit var banner: TextView
    private var binding = false
    private var dialog: AlertDialog? = null

    private fun dp(value: Int) = activity.dp(value)
    private val primary = activity.themedColor(android.R.attr.textColorPrimary, Color.BLACK)
    private val secondary = activity.themedColor(android.R.attr.textColorSecondary, Color.GRAY)
    private val accent = activity.themedColor(android.R.attr.colorAccent, Color.parseColor("#6750A4"))

    fun show(): AlertDialog {
        val exception = current() ?: return AlertDialog.Builder(activity).create()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
            addView(heading(exception), LinearLayout.LayoutParams(full, wrap))
            addView(TextView(activity).apply {
                setText(R.string.exceptions_modal_hint)
                textSize = 13f
                setTextColor(secondary)
                setPadding(dp(16), dp(4), dp(16), dp(8))
            }, LinearLayout.LayoutParams(full, wrap))
            banner = TextView(activity).apply {
                textSize = 13f
                setTextColor(primary)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.argb(30, Color.red(accent), Color.green(accent), Color.blue(accent)))
                    cornerRadius = dp(10).toFloat()
                }
            }
            addView(banner, LinearLayout.LayoutParams(full, wrap).apply { setMargins(dp(8), 0, dp(8), dp(8)) })
            addView(allRow(), LinearLayout.LayoutParams(full, wrap))
            group.sections.forEach { section ->
                section.titleRes?.let { addView(sectionHeader(section), LinearLayout.LayoutParams(full, wrap)) }
                section.controls.forEach { addView(controlRow(it), LinearLayout.LayoutParams(full, wrap)) }
            }
        }
        refresh()
        val shown = AlertDialog.Builder(activity)
            .setView(ScrollView(activity).apply { tag = "exceptions_group_scroll"; addView(content) })
            .setPositiveButton(R.string.exceptions_done, null)
            .create()
        dialog = shown
        shown.show()
        shown.window?.setLayout(
            minOf(dp(480), dp(activity.resources.configuration.screenWidthDp - 32)),
            minOf(dp(640), dp(activity.resources.configuration.screenHeightDp - 32)),
        )
        return shown
    }

    fun dismiss() {
        dialog?.dismiss()
    }

    /** Pulls every switch from the current exception; called after any change and when the page changes. */
    fun refresh() {
        val exception = current() ?: return dismiss()
        binding = true
        val editable = group.editable(exception)
        rows.forEach { (control, views) ->
            views.toggle.isChecked = exception.allowed and control.bit != 0
            views.toggle.isEnabled = editable
            views.row.alpha = if (editable) 1f else 0.38f
            views.row.isEnabled = editable
        }
        sections.forEach { section ->
            val on = section.group.count { exception.allowed and it.bit != 0 }
            section.toggle.isChecked = on == section.group.size
            section.toggle.isEnabled = editable
            section.count.text = activity.getString(R.string.exceptions_count, on, section.group.size)
        }
        allToggle.isChecked = group.allowedCount(exception) == group.controls.size
        allToggle.isEnabled = editable
        allCount.text = activity.getString(R.string.exceptions_count, group.allowedCount(exception), group.controls.size)
        banner.text = activity.getString(if (!exception.active) R.string.exceptions_modal_inactive else R.string.exceptions_modal_native)
        banner.visibility = if (!exception.active || (group != ExceptionGroup.BACK && exception.backNative)) View.VISIBLE else View.GONE
        binding = false
    }

    private fun heading(exception: AppException) = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(4))
        addView(FrameBadge(group.iconRes), LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) })
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                setText(group.titleRes)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(primary)
            })
            addView(TextView(activity).apply {
                text = exception.name
                textSize = 13f
                setTextColor(secondary)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, wrap, 1f))
    }

    /** A rounded tonal tile holding the group's own page icon. */
    private fun FrameBadge(iconRes: Int) = android.widget.FrameLayout(activity).apply {
        background = GradientDrawable().apply {
            setColor(Color.argb(36, Color.red(accent), Color.green(accent), Color.blue(accent)))
            cornerRadius = dp(14).toFloat()
        }
        addView(ImageView(activity).apply {
            setImageDrawable(activity.getDrawable(iconRes)!!.mutate().apply { setTint(accent) })
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, android.widget.FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
    }

    private fun switchRow(tag: String, label: String, description: String, bold: Boolean, leading: View?): Pair<View, Switch> {
        val toggle = Switch(activity).apply { this.tag = tag; contentDescription = label }
        val row = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(16), dp(6), dp(12), dp(6))
            val value = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
            background = activity.getDrawable(value.resourceId)
            isClickable = true
            isFocusable = true
            leading?.let { addView(it, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) }) }
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = label
                    textSize = 16f
                    if (bold) setTypeface(typeface, Typeface.BOLD)
                    setTextColor(primary)
                })
                if (description.isNotEmpty()) addView(TextView(activity).apply {
                    text = description
                    textSize = 12f
                    setTextColor(secondary)
                })
            }, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(toggle, LinearLayout.LayoutParams(wrap, wrap))
            setOnClickListener { if (toggle.isEnabled) toggle.toggle() }
        }
        return row to toggle
    }

    private fun write(mask: Int, on: Boolean) {
        if (binding) return
        val exception = current() ?: return
        onChange(if (on) exception.allowed or mask else exception.allowed and mask.inv())
        refresh()
    }

    private fun allRow(): View {
        val (row, toggle) = switchRow(
            "exceptions_group_all", activity.getString(R.string.exceptions_all_column), "", true, null,
        )
        allToggle = toggle
        allCount = TextView(activity).apply { textSize = 13f; setTextColor(secondary); setPadding(0, 0, dp(12), 0) }
        (row as LinearLayout).addView(allCount, row.childCount - 1, LinearLayout.LayoutParams(wrap, wrap))
        toggle.setOnCheckedChangeListener { _, checked -> write(group.mask, checked) }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(View(activity).apply { setBackgroundColor(secondary); alpha = 0.16f }, LinearLayout.LayoutParams(full, dp(1)).apply { setMargins(dp(16), 0, dp(16), 0) })
        }
    }

    private fun sectionHeader(section: ExceptionGroup.Section): View {
        val (row, toggle) = switchRow(
            "exceptions_section_${section.controls.first().ordinal}", activity.getString(section.titleRes!!), "", true, null,
        )
        val count = TextView(activity).apply { textSize = 13f; setTextColor(secondary); setPadding(0, 0, dp(12), 0) }
        (row as LinearLayout).addView(count, row.childCount - 1, LinearLayout.LayoutParams(wrap, wrap))
        row.setPadding(dp(16), dp(14), dp(12), dp(2))
        ((row.getChildAt(0) as LinearLayout).getChildAt(0) as TextView).apply { setTextColor(accent); textSize = 14f }
        val mask = section.controls.sumOf { it.bit }
        toggle.setOnCheckedChangeListener { _, checked -> write(mask, checked) }
        sections += SectionViews(section.controls, toggle, count)
        return row
    }

    private fun controlRow(control: ExceptionControl): View {
        val info = describe(control)
        val (row, toggle) = switchRow("exceptions_control_${control.ordinal}", info.title, info.subtitle, false, glyph(info))
        toggle.setOnCheckedChangeListener { _, checked -> write(control.bit, checked) }
        rows[control] = RowViews(row, toggle)
        return row
    }

    private class Info(val title: String, val subtitle: String, val icon: Int?, val rotation: Float, val badge: String?)

    private fun describe(control: ExceptionControl): Info {
        fun s(res: Int) = activity.getString(res)
        fun combo(label: String) = activity.getString(R.string.settings_binding_label, label)
        val arrow = R.drawable.ic_exc_arrow
        return when (control) {
            ExceptionControl.SINGLE -> Info(s(R.string.exceptions_single_column), s(R.string.single_section_title), R.drawable.ic_exc_press, 0f, null)
            ExceptionControl.DOUBLE -> Info(s(R.string.exceptions_double_column), s(R.string.double_section_title), R.drawable.ic_exc_double, 0f, null)
            ExceptionControl.TAP_HOLD -> Info(s(R.string.exceptions_tap_hold_column), s(R.string.tap_hold_section_title), R.drawable.ic_exc_tap_hold, 0f, null)
            ExceptionControl.HOLD -> Info(s(R.string.exceptions_hold_column), s(R.string.hold_section_title), R.drawable.ic_exc_hold, 0f, null)
            ExceptionControl.L1 -> Info(s(R.string.shoulder_l1), combo(s(R.string.shoulder_l1)), null, 0f, "L1")
            ExceptionControl.R1 -> Info(s(R.string.shoulder_r1), combo(s(R.string.shoulder_r1)), null, 0f, "R1")
            ExceptionControl.L2 -> Info(s(R.string.shoulder_l2), combo(s(R.string.shoulder_l2)), null, 0f, "L2")
            ExceptionControl.R2 -> Info(s(R.string.shoulder_r2), combo(s(R.string.shoulder_r2)), null, 0f, "R2")
            ExceptionControl.UP -> Info(s(R.string.dpad_up), combo(s(R.string.dpad_up)), arrow, 0f, null)
            ExceptionControl.DOWN -> Info(s(R.string.dpad_down), combo(s(R.string.dpad_down)), arrow, 180f, null)
            ExceptionControl.LEFT -> Info(s(R.string.dpad_left), combo(s(R.string.dpad_left)), arrow, 270f, null)
            ExceptionControl.RIGHT -> Info(s(R.string.dpad_right), combo(s(R.string.dpad_right)), arrow, 90f, null)
            else -> stick(control)
        }
    }

    private fun stick(control: ExceptionControl): Info {
        val left = control.name.startsWith("LEFT")
        fun s(res: Int) = activity.getString(res)
        fun info(title: Int, icon: Int, rotation: Float = 0f) =
            Info(s(title), activity.getString(R.string.settings_binding_label, s(title)), icon, rotation, null)
        val arrow = R.drawable.ic_exc_arrow
        return when (control.name.substringAfter("STICK_")) {
            "UP" -> info(if (left) R.string.stick_left_up else R.string.stick_right_up, arrow)
            "DOWN" -> info(if (left) R.string.stick_left_down else R.string.stick_right_down, arrow, 180f)
            "LEFT" -> info(if (left) R.string.stick_left_left else R.string.stick_right_left, arrow, 270f)
            "RIGHT" -> info(if (left) R.string.stick_left_right else R.string.stick_right_right, arrow, 90f)
            "PRESS" -> info(if (left) R.string.stick_left_press else R.string.stick_right_press, R.drawable.ic_exc_press)
            "DOUBLE" -> info(if (left) R.string.stick_left_double else R.string.stick_right_double, R.drawable.ic_exc_double)
            else -> info(if (left) R.string.stick_left_tap_hold else R.string.stick_right_tap_hold, R.drawable.ic_exc_tap_hold)
        }
    }

    /** A glyph or a lettered badge in a quiet rounded tile, so rows read at a glance. */
    private fun glyph(info: Info): View = android.widget.FrameLayout(activity).apply {
        background = GradientDrawable().apply {
            setColor(Color.argb(20, Color.red(primary), Color.green(primary), Color.blue(primary)))
            cornerRadius = dp(10).toFloat()
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        if (info.badge != null) addView(TextView(activity).apply {
            text = info.badge
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primary)
            gravity = Gravity.CENTER
        }, android.widget.FrameLayout.LayoutParams(full, full))
        else addView(ImageView(activity).apply {
            setImageDrawable(activity.getDrawable(checkNotNull(info.icon))!!.mutate().apply { setTint(primary) })
            rotation = info.rotation
        }, android.widget.FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
    }
}
