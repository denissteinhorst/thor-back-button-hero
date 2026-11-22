package dev.steinhorst.backbuttonhero

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import dev.steinhorst.backbuttonhero.capture.PushToTalk
import dev.steinhorst.backbuttonhero.input.Stick
import dev.steinhorst.backbuttonhero.pad.GyroAxis
import dev.steinhorst.backbuttonhero.pad.GyroSettings
import dev.steinhorst.backbuttonhero.pad.GyroStatus

/**
 * The gyro hotkey's setup in one popup: a ball on a surface that moves exactly as the stick would,
 * whenever the hotkey has the gyro steering, next to every switch and slider. Every change is stored
 * at once through [onChange], like the pictogram's popup.
 */
class GyroSettingsDialog(
    private val activity: Activity,
    /** The hotkey this setup belongs to, so the preview can tell whether its gyro is the one running. */
    prefKey: String,
    initial: GyroSettings,
    /** Waits for a button press; with a key already chosen it also offers none, handed over as 0. */
    private val chooseKey: (hasKey: Boolean, onChosen: (Int) -> Unit) -> Unit,
    private val onChange: (GyroSettings) -> Unit,
) {
    private var settings = initial
    private val preview = GyroPreview(activity, initial, prefKey)
    private val refreshers = mutableListOf<() -> Unit>()

    private fun dp(value: Int) = activity.dp(value)
    private val accent get() = activity.themedColor(android.R.attr.colorAccent, Color.parseColor("#6750A4"))

    private fun change(next: GyroSettings) {
        if (next == settings) return
        settings = next
        preview.settings = next
        onChange(next)
        refreshers.forEach { it() }
    }

    fun show(onDismiss: () -> Unit) {
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val full = ViewGroup.LayoutParams.MATCH_PARENT
        val width = activity.resources.configuration.screenWidthDp
        val previewColumn = column().apply {
            addView(header(R.string.gyro_header_preview))
            preview.contentDescription = activity.getString(R.string.gyro_preview_description)
            addView(preview, LinearLayout.LayoutParams(dp(PREVIEW_WIDTH_DP), dp(PREVIEW_HEIGHT_DP)).apply { topMargin = dp(8) })
            addView(Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
                setText(R.string.gyro_calibrate)
                setTextColor(accent)
                setOnClickListener { preview.calibrate() }
            }, LinearLayout.LayoutParams(dp(PREVIEW_WIDTH_DP), wrap).apply { topMargin = dp(4) })
            addView(hint(R.string.gyro_calibrate_hint), LinearLayout.LayoutParams(dp(PREVIEW_WIDTH_DP), wrap))
        }
        val directions = column().apply {
            addView(header(R.string.gyro_header_mode))
            addView(switch(R.string.gyro_rate, { settings.rate }) { change(settings.copy(rate = it)) })
            addView(TextView(activity).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(activity.themedColor(android.R.attr.textColorSecondary, Color.GRAY))
                refreshers += { setText(if (settings.rate) R.string.gyro_rate_hint_on else R.string.gyro_rate_hint_off) }
            })
            addView(header(R.string.gyro_header_stick), spaced())
            addView(choice(
                listOf(R.string.gyro_stick_left to Stick.LEFT, R.string.gyro_stick_right to Stick.RIGHT),
                { settings.stick }, { change(settings.copy(stick = it)) },
            ))
            addView(header(R.string.gyro_header_axis), spaced())
            addView(choice(
                listOf(R.string.gyro_axis_yaw to GyroAxis.YAW, R.string.gyro_axis_roll to GyroAxis.ROLL),
                { settings.axis }, { change(settings.copy(axis = it)) },
            ))
            addView(header(R.string.gyro_header_invert), spaced())
            addView(switch(R.string.gyro_invert_x, { settings.invertX }) { change(settings.copy(invertX = it)) })
            addView(switch(R.string.gyro_invert_y, { settings.invertY }) { change(settings.copy(invertY = it)) })
        }
        val behaviour = column().apply {
            addView(header(R.string.gyro_header_activation))
            addView(keyRow())
            addView(TextView(activity).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(activity.themedColor(android.R.attr.textColorSecondary, Color.GRAY))
                refreshers += {
                    text = settings.holdKeyInUse?.let { activity.getString(R.string.gyro_hold_hint_key, PushToTalk.label(it)) }
                        ?: activity.getString(R.string.gyro_hold_hint_none)
                }
            })
            addView(switch(R.string.gyro_hold_passes, { settings.holdPasses }, visible = { settings.holdKeyInUse != null }) {
                change(settings.copy(holdPasses = it))
            })
            addView(slider(R.string.gyro_header_sensitivity, GyroSettings.MIN_SENSITIVITY, GyroSettings.MAX_SENSITIVITY, 10,
                { settings.sensitivity }, { activity.getString(R.string.gyro_percent, it) }) { change(settings.copy(sensitivity = it)) }, spaced())
            addView(slider(R.string.gyro_header_deadzone, 0, GyroSettings.MAX_DEADZONE, 5,
                { settings.deadzone }, { activity.getString(if (settings.rate) R.string.gyro_degrees else R.string.gyro_degrees_tilt, it / 10f) }) { change(settings.copy(deadzone = it)) }, spaced(8))
            addView(slider(R.string.gyro_header_min_output, 0, GyroSettings.MAX_MIN_OUTPUT, 5,
                { settings.minOutput }, { activity.getString(R.string.gyro_percent, it) }) { change(settings.copy(minOutput = it)) }, spaced(8))
        }
        // Three columns where there is room, the controls stacked beside the preview below that, and
        // everything stacked on a narrow screen.
        fun row(vararg views: View) = LinearLayout(activity).apply {
            views.forEachIndexed { index, view ->
                addView(view, LinearLayout.LayoutParams(if (index == 0) wrap else 0, wrap, if (index == 0) 0f else 1f).apply {
                    if (index > 0) marginStart = dp(24)
                })
            }
        }
        val content = when {
            width >= THREE_COLUMNS_DP -> row(previewColumn, directions, behaviour)
            width >= TWO_COLUMNS_DP -> row(previewColumn, column().apply {
                addView(directions)
                addView(behaviour, spaced(24))
            })
            else -> column().apply {
                addView(previewColumn)
                addView(directions, spaced(24))
                addView(behaviour, spaced(24))
            }
        }.apply { setPadding(dp(16), dp(12), dp(16), dp(4)) }
        refreshers.forEach { it() }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.gyro_setup_title)
            .setView(ScrollView(activity).apply { addView(content) })
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.gyro_reset, null)
            .setOnDismissListener {
                SwitcherService.gyroStatusListener = null
                onDismiss()
            }
            .show()
        dialog.window?.setLayout(minOf(dp(MAX_WIDTH_DP), dp(width - 32)), wrap)
        // Resetting keeps the popup open, so the defaults can be tried at once.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            change(GyroSettings())
            preview.recenter()
        }
        preview.status = SwitcherService.gyroStatus
        SwitcherService.gyroStatusListener = { preview.status = it }
    }

    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    private fun spaced(top: Int = 16) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(top) }

    private fun header(res: Int) = TextView(activity).apply {
        setText(res)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.04f
        setTextColor(accent)
    }

    private fun hint(res: Int) = TextView(activity).apply {
        setText(res)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(activity.themedColor(android.R.attr.textColorSecondary, Color.GRAY))
    }

    private fun <T> choice(options: List<Pair<Int, T>>, read: () -> T, write: (T) -> Unit): RadioGroup =
        RadioGroup(activity).apply {
            orientation = RadioGroup.HORIZONTAL
            val buttons = options.map { (label, value) ->
                RadioButton(activity).apply {
                    id = View.generateViewId()
                    setText(label)
                    minimumHeight = dp(44)
                    setOnClickListener { write(value) }
                }.also { addView(it, RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
            }
            refreshers += { options.indexOfFirst { it.second == read() }.let { check(buttons[it].id) } }
        }

    private fun switch(res: Int, read: () -> Boolean, visible: () -> Boolean = { true }, write: (Boolean) -> Unit): Switch =
        Switch(activity).apply {
            setText(res)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            minimumHeight = dp(44)
            setOnCheckedChangeListener { _, on -> write(on) }
            refreshers += {
                if (isChecked != read()) isChecked = read()
                visibility = if (visible()) View.VISIBLE else View.GONE
            }
        }

    /** The hold key, named; no key means the hotkey alone turns the gyro on and off. */
    private fun keyRow(): View {
        val value = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(accent)
            gravity = Gravity.END
        }
        return LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            isClickable = true
            isFocusable = true
            background = TypedValue().let { tv ->
                activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                activity.getDrawable(tv.resourceId)
            }
            addView(TextView(activity).apply {
                setText(R.string.gyro_hold_key_title)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(value)
            setOnClickListener { chooseKey(settings.holdKeyInUse != null) { key -> change(settings.copy(holdKey = key)) } }
            refreshers += {
                value.text = settings.holdKeyInUse?.let(PushToTalk::label) ?: activity.getString(R.string.gyro_hold_none)
                contentDescription = activity.getString(R.string.gyro_hold_key_accessibility, value.text)
            }
        }
    }

    private fun slider(titleRes: Int, min: Int, max: Int, step: Int, read: () -> Int, format: (Int) -> String, write: (Int) -> Unit): View {
        val value = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.END
        }
        val bar = SeekBar(activity).apply {
            alignSettingsRange()
            this.max = (max - min) / step
            contentDescription = activity.getString(titleRes)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    value.text = format(min + progress * step)
                    if (fromUser) write(min + progress * step)
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        refreshers += {
            bar.progress = (read() - min) / step
            value.text = format(read())
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(activity).apply {
                addView(header(titleRes), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(value)
            })
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        }
    }

    companion object {
        private const val PREVIEW_WIDTH_DP = 240
        private const val PREVIEW_HEIGHT_DP = 170
        private const val THREE_COLUMNS_DP = 800
        private const val TWO_COLUMNS_DP = 600
        private const val MAX_WIDTH_DP = 960
    }
}

/** Shows the service's actual stick samples; no second sensor integration can drift apart from it. */
class GyroPreview @JvmOverloads constructor(
    context: Context,
    initial: GyroSettings = GyroSettings(),
    private val prefKey: String? = null,
) : View(context) {
    var settings = initial
        set(value) {
            val switched = value.rate != field.rate
            field = value
            if (switched) recenter()
        }
    private val density = resources.displayMetrics.density
    private val accent = TypedValue().let { tv ->
        if (context.theme.resolveAttribute(android.R.attr.colorAccent, tv, true)) tv.data else Color.parseColor("#6750A4")
    }
    private val textColor = TypedValue().let { tv ->
        if (context.theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true) && tv.resourceId != 0)
            context.getColor(tv.resourceId) else Color.GRAY
    }
    private val surface = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(28, 128, 128, 128) }
    private val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 128, 128, 128) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 128, 128, 128)
        strokeWidth = density
        style = Paint.Style.STROKE
    }
    private val radius = 14 * density
    // Built once around the origin; the ball is drawn there with the canvas moved to its place.
    private val ball = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(-radius / 3, -radius / 3, radius * 1.4f, Color.WHITE, accent, Shader.TileMode.CLAMP)
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(50, 0, 0, 0) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        textSize = 15 * resources.displayMetrics.scaledDensity
        typeface = Typeface.DEFAULT_BOLD
    }
    private val subtitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        textSize = 12 * resources.displayMetrics.scaledDensity
    }
    private val bounds = RectF()

    var status = GyroStatus()
        set(value) {
            val newSample = value.sequence != field.sequence
            val started = !steering
            field = value
            if (!steering) {
                deflection = intArrayOf(0, 0)
                if (!settings.rate) { x = 0f; y = 0f }
            } else {
                if (started) { x = 0f; y = 0f }
                deflection = intArrayOf(value.x, value.y)
                if (settings.rate) {
                    if (newSample) {
                        x = (x + value.x / 1000f * SPEED * value.seconds).coerceIn(-1f, 1f)
                        y = (y + value.y / 1000f * SPEED * value.seconds).coerceIn(-1f, 1f)
                    }
                } else {
                    x = value.x / 1000f
                    y = value.y / 1000f
                }
            }
            invalidate()
        }

    private val steering get() = status.prefKey == prefKey && status.steering
    private var x = 0f
    private var y = 0f
    private var deflection = intArrayOf(0, 0)

    fun recenter() {
        x = 0f
        y = 0f
        deflection = intArrayOf(0, 0)
        if (status.prefKey == prefKey) SwitcherService.gyroRecenter?.invoke()
        invalidate()
    }

    fun calibrate() {
        val start = SwitcherService.gyroCalibrate
        if (start != null) start()
        else android.widget.Toast.makeText(context, R.string.gyro_service_needed, android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            recenter()
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        val corner = 16 * density
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(bounds, corner, corner, surface)
        canvas.drawRoundRect(bounds, corner, corner, line)
        canvas.drawLine(width / 2f, corner, width / 2f, height - corner, line)
        canvas.drawLine(corner, height / 2f, width - corner, height / 2f, line)
        val cx = width / 2f + x * (width / 2f - radius - 4 * density)
        val cy = height / 2f + y * (height / 2f - radius - 4 * density)
        canvas.drawCircle(cx + 2 * density, cy + 3 * density, radius, shadow)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.drawCircle(0f, 0f, radius, ball)
        canvas.restore()
        // The stick itself, small in the corner: what the game receives at this moment.
        val gauge = 16 * density
        val gx = width - gauge - 10 * density
        val gy = height - gauge - 10 * density
        canvas.drawCircle(gx, gy, gauge, line)
        canvas.drawCircle(gx + deflection[0] / 1000f * gauge, gy + deflection[1] / 1000f * gauge, 3.5f * density, dot)
        val message = when {
            status.calibrating -> R.string.gyro_preview_calibrating to R.string.gyro_preview_calibrating_hint
            status.prefKey != prefKey -> R.string.gyro_preview_inactive to R.string.gyro_preview_inactive_hint
            !status.steering -> R.string.gyro_preview_waiting to R.string.gyro_preview_waiting_hint
            else -> null
        } ?: return
        canvas.drawRoundRect(bounds, corner, corner, scrim)
        val holdKey = settings.holdKeyInUse?.let(PushToTalk::label).orEmpty()
        canvas.drawText(context.getString(message.first, holdKey), width / 2f, height / 2f - 4 * density, title)
        canvas.drawText(context.getString(message.second, holdKey), width / 2f, height / 2f + 16 * density, subtitle)
    }

    companion object {
        /** Surface half-widths per second at full deflection. */
        private const val SPEED = 1.6f
    }
}
