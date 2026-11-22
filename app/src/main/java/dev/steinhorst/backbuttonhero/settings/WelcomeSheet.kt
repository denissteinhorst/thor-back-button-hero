package dev.steinhorst.backbuttonhero.settings

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.input.AndroidButton
import dev.steinhorst.backbuttonhero.input.BackGestureButton
import dev.steinhorst.backbuttonhero.input.DpadDirection
import dev.steinhorst.backbuttonhero.input.ShoulderButton
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.prefs.SettingsChangeAction
import dev.steinhorst.backbuttonhero.prefs.SettingsChangePlan
import dev.steinhorst.backbuttonhero.prefs.SettingsState

/** The pause shortcut's hold time in the defaults, a little longer than the factory 300 ms. */
private const val DEFAULT_SETUP_TOGGLE_HOLD_MS = 500L

/** What the welcome sheet's follow-up sets up. Keeping the current settings is no setup at all. */
enum class StartSetup { DEFAULTS, CLEAN }

/**
 * The whole app as the welcome sheet's [setup] leaves it: factory settings like the whole-app reset
 * (so macros survive and exceptions go), plus the shortcuts installs had before everything started
 * unassigned and the tested Start/Select shortcuts: the app's defaults. A clean start keeps only Back's
 * own gestures; the factory settings themselves stay empty.
 */
fun startSetup(before: SettingsState, setup: StartSetup): SettingsState {
    val reset = SettingsChangePlan.create(before, null, SettingsChangeAction.PRESET).after
    val earlier = buildMap {
        BackGestureButton.entries.forEach { put(it, it.earlierDefault) }
        if (setup == StartSetup.DEFAULTS) {
            put(ShoulderButton.L1, Prefs.SHOULDER_FLICK_LEFT)
            put(ShoulderButton.R1, Prefs.SHOULDER_FLICK_RIGHT)
            put(ShoulderButton.L2, Prefs.SHOULDER_SCREENSHOT)
            put(ShoulderButton.R2, Prefs.SHOULDER_CONTROLLER_FOCUS)
            DpadDirection.entries.forEach { put(it, it.earlierDefault) }
            put(AndroidButton.START, Prefs.SHOULDER_CLOSE_APPS)
            put(AndroidButton.SELECT, Prefs.SHOULDER_LAST_APP)
        }
    }
    return reset.copy(
        bindings = reset.bindings.toMutableMap().apply {
            earlier.forEach { (button, action) -> put(button.prefKey, getValue(button.prefKey).copy(action = action)) }
        },
        toggleButtons = if (setup == StartSetup.DEFAULTS) Prefs.TOGGLE_START_SELECT else Prefs.TOGGLE_NONE,
        toggleMs = if (setup == StartSetup.DEFAULTS) DEFAULT_SETUP_TOGGLE_HOLD_MS else reset.toggleMs,
    )
}

/** Whether anything differs from the factory settings, so a start setup would take something away. */
fun SettingsState.isCustomized(): Boolean = copy(macros = emptyList()) != SettingsState()

/**
 * The 2.0 welcome: an advert for the app with nothing to tap but its one button, then the choice of
 * a start setup. Backing out of either keeps [Prefs.welcomeSeen] false, so both return next time.
 */
class WelcomeSheet(
    private val activity: Activity,
    private val prefs: Prefs,
    private val onApplied: () -> Unit,
) {
    private var dialog: AlertDialog? = null

    private fun dp(value: Int) = activity.dp(value)
    private val primary get() = activity.themedColor(android.R.attr.textColorPrimary, Color.BLACK)
    private val secondary get() = activity.themedColor(android.R.attr.textColorSecondary, Color.GRAY)

    fun show() {
        dialog = AlertDialog.Builder(activity)
            .setView(ScrollView(activity).apply { addView(content()) })
            .setPositiveButton(R.string.welcome_start) { _, _ -> chooseSetup() }
            .show()
        // The list has to fit without scrolling, so a wide screen gets a wide sheet instead of a tall one.
        val metrics = activity.resources.displayMetrics
        if (columns() > 1) dialog?.window?.setLayout(
            minOf((metrics.widthPixels * WIDE_SHARE).toInt(), dp(WIDE_MAX_DP)), ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun columns(): Int = activity.resources.configuration.screenWidthDp.let { width ->
        when {
            width >= THREE_COLUMNS_DP -> 3
            width >= TWO_COLUMNS_DP -> 2
            else -> 1
        }
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    private fun content() = LinearLayout(activity).apply {
        tag = "welcome_sheet"
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(20), dp(24), dp(4))
        addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(activity).apply {
                setImageResource(R.mipmap.ic_launcher)
                importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPaddingRelative(dp(16), 0, 0, 0)
                addView(text(R.string.welcome_title, 22f, primary, bold = true))
                addView(text(R.string.welcome_subtitle, 16f, secondary))
            })
        })
        addView(text(R.string.welcome_features_title, 20f, primary, bold = true), spaced(16))
        addView(text(R.string.welcome_features_subtitle, 15f, secondary))
        // Columns wherever they fit keep the list within the Thor's short top screen.
        val columns = columns()
        val rows = (FEATURES.size + columns - 1) / columns
        addView(LinearLayout(activity).apply {
            repeat(columns) { column ->
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    FEATURES.drop(column * rows).take(rows).forEach { (title, detail) ->
                        addView(text(title, 17f, primary, bold = true), spaced(10))
                        addView(text(detail, 14f, secondary))
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }, spaced(4))
        addView(text(R.string.welcome_more, 15f, secondary), spaced(12))
    }

    private fun chooseSetup() {
        val customized = prefs.snapshot().isCustomized()
        val choices = buildList {
            add(StartSetup.DEFAULTS to (R.string.setup_defaults to R.string.setup_defaults_detail))
            add(StartSetup.CLEAN to (R.string.setup_clean to R.string.setup_clean_detail))
            if (customized) add(null to (R.string.setup_keep to R.string.setup_keep_detail))
        }
        val labels = choices.map { (_, texts) -> twoLines(texts.first, texts.second) }.toTypedArray()
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.setup_title)
            .setItems(labels) { _, which -> finish(choices[which].first) }
            .show()
    }

    private fun finish(setup: StartSetup?) {
        dialog = null
        if (setup != null) {
            val before = prefs.snapshot()
            prefs.applySettings(before, startSetup(before, setup))
        }
        prefs.welcomeSeen = true
        onApplied()
    }

    private fun twoLines(title: Int, detail: Int): CharSequence = SpannableStringBuilder().apply {
        append(activity.getString(title), StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append("\n")
        append(activity.getString(detail), ForegroundColorSpan(secondary), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun text(res: Int, size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
        setText(res)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun spaced(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    companion object {
        private const val TWO_COLUMNS_DP = 600
        private const val THREE_COLUMNS_DP = 720
        private const val WIDE_SHARE = 0.92f
        private const val WIDE_MAX_DP = 860

        /** The advertised top features as title and detail, in the order of the 2.0 announcement. */
        val FEATURES = listOf(
            R.string.welcome_feature_swap to R.string.welcome_feature_swap_detail,
            R.string.welcome_feature_switch to R.string.welcome_feature_switch_detail,
            R.string.welcome_feature_screenshots to R.string.welcome_feature_screenshots_detail,
            R.string.welcome_feature_recording to R.string.welcome_feature_recording_detail,
            R.string.welcome_feature_both_screens to R.string.welcome_feature_both_screens_detail,
            R.string.welcome_feature_levels to R.string.welcome_feature_levels_detail,
            R.string.welcome_feature_launch to R.string.welcome_feature_launch_detail,
            R.string.welcome_feature_pause to R.string.welcome_feature_pause_detail,
            R.string.welcome_feature_gyro to R.string.welcome_feature_gyro_detail,
        )
    }
}
