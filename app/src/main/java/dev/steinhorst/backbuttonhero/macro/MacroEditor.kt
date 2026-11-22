package dev.steinhorst.backbuttonhero.macro

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.SwitcherService
import dev.steinhorst.backbuttonhero.core.PService
import dev.steinhorst.backbuttonhero.dp
import dev.steinhorst.backbuttonhero.input.Stick
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.themedColor
import java.util.Locale

/**
 * The full-screen macro editor of one hotkey, built to need no explanation: one big record button,
 * steps as cards in a row, tap to change, hold and drag to move or to throw away, and undo for every
 * change instead of questions. Recording always adds to the end. Recording runs through
 * [SwitcherService.macroRecorder], because only the service talks to the root helper. Nothing is
 * stored until Save; closing leaves the hotkey's macro as it was.
 */
class MacroEditor(
    private val activity: Activity,
    private val title: String,
    initial: SavedMacro?,
    /** The name a new macro is offered under, such as "Macro R1". */
    private val defaultName: String,
    private val library: () -> List<SavedMacro>,
    private val flipped: () -> Boolean,
    /**
     * Saves [macro] as [name] under library [id] (null: as a new library macro). A null [macro] means
     * the editor was saved empty: the hotkey keeps no macro, the library stays as it is.
     */
    private val onSave: (id: String?, name: String, macro: Macro?) -> Unit,
) : StepStrip.Listener {
    private val ui = MacroUi(activity)
    private val full = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val handler = Handler(Looper.getMainLooper())

    /** The library macro being edited; null for a new one. */
    private var editing: SavedMacro? = initial
    private var savedSteps = initial?.macro?.steps.orEmpty()
    private var savedName: String? = initial?.name
    private var steps: List<MacroStep> = savedSteps
    /** The name so far; null for a new macro that was not named yet. */
    private var name: String? = savedName
    private val history = ArrayDeque<Pair<List<MacroStep>, String?>>()

    private var recorder: MacroRecorder? = null
    /** The steps a recording adds to. */
    private var recordBase: List<MacroStep> = emptyList()
    /** How long the recording may run before the macro reaches its maximum length. */
    private var recordBudget = 0L
    private var recordStartedAt = 0L
    private var lastInputAt = 0L
    private var lastInputUptime = 0L
    private var emptyHint = R.string.macro_empty_body
    /** The steps the timeline currently shows, so a live update only rebuilds what changed. */
    private var shown: List<MacroStep> = emptyList()
    /** Per step its icon box, and per pause (after step i) its badge, for the preview to light up. */
    private val stepBoxes = mutableListOf<View>()
    private val gapBadges = mutableListOf<View>()
    private var previewStep: Runnable? = null
    private var lit: View? = null

    private lateinit var dialog: Dialog
    private lateinit var nameView: TextView
    private lateinit var nameBlock: View
    private lateinit var saveButton: TextView
    private lateinit var emptyState: View
    private lateinit var emptyBody: TextView
    private lateinit var addByHand: View
    private lateinit var loadSaved: View
    private lateinit var timelineState: View
    private lateinit var summary: TextView
    private lateinit var strip: StepStrip
    private lateinit var timelineScroll: HorizontalScrollView
    private lateinit var editHint: TextView
    private lateinit var scrollIndicator: ScrollIndicator
    private lateinit var recordingState: View
    private lateinit var recordingLabel: TextView
    private lateinit var recordingHint: TextView
    private lateinit var controlBar: View
    private lateinit var bin: TextView
    private lateinit var undoButton: BarButton
    private lateinit var recordButton: BarButton
    private lateinit var previewButton: BarButton
    private lateinit var moreButton: BarButton
    private lateinit var snackbar: View
    private lateinit var snackText: TextView

    private class BarButton(val view: LinearLayout, val icon: ImageView, val label: TextView)

    private val ticker = object : Runnable {
        override fun run() {
            if (recorder == null) return
            val elapsed = SystemClock.uptimeMillis() - recordStartedAt
            if (elapsed >= recordBudget) {
                Toast.makeText(activity, R.string.macro_limit_reached, Toast.LENGTH_SHORT).show()
                stopRecording()
                return
            }
            val ready = SwitcherService.macroRecorder?.ready == true
            recordingLabel.text = if (ready) activity.getString(R.string.macro_recording, seconds(elapsed)) else activity.getString(R.string.macro_connecting)
            recordingHint.visibility = if (ready) View.VISIBLE else View.INVISIBLE
            renderLive()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val hideSnackbar = Runnable { snackbar.visibility = View.GONE }

    private fun dp(value: Int) = ui.dp(value)

    fun show(): Dialog {
        dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_DayNight).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(content())
            window?.setLayout(full, full)
            setOnKeyListener { _, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
                if (event.action == KeyEvent.ACTION_UP) requestCancel()
                true
            }
            setOnDismissListener {
                endRecording()
                stopPreview()
                handler.removeCallbacks(hideSnackbar)
            }
            show()
        }
        render()
        return dialog
    }

    /** The settings screen went to the background: a recording must not keep the pad from the game. */
    fun pause() {
        if (recorder != null) stopRecording()
        stopPreview()
    }

    // ---- Layout ----

    private fun content(): View = FrameLayout(activity).apply {
        setBackgroundColor(ui.windowColor)
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(16), dp(12))
            addView(header(), LinearLayout.LayoutParams(full, wrap))
            addView(FrameLayout(activity).apply {
                emptyState = emptyState()
                timelineState = timelineState()
                recordingState = recordingState()
                addView(emptyState, FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER))
                // While recording, the steps so far appear under the recording status as they are pressed.
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(recordingState, LinearLayout.LayoutParams(wrap, wrap))
                    addView(timelineState, LinearLayout.LayoutParams(full, wrap))
                }, FrameLayout.LayoutParams(full, wrap, Gravity.CENTER))
            }, LinearLayout.LayoutParams(full, 0, 1f))
            addView(FrameLayout(activity).apply {
                controlBar = controlBar()
                bin = binBar()
                addView(controlBar, FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER))
                addView(bin, FrameLayout.LayoutParams(full, dp(82)))
            }, LinearLayout.LayoutParams(full, wrap))
        }, FrameLayout.LayoutParams(full, full))
        snackbar = snackbar()
        addView(snackbar, FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(102) })
    }

    private fun header(): View = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        addView(ui.iconButton(R.drawable.ic_macro_close, activity.getString(R.string.macro_close)) { requestCancel() })
        nameBlock = ui.touchable(LinearLayout(activity), 12) { rename() }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                nameView = ui.text(20f, bold = true).apply {
                    maxLines = 1
                    maxWidth = dp(440)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                addView(nameView, LinearLayout.LayoutParams(wrap, wrap))
                addView(ui.icon(R.drawable.ic_macro_edit, ui.secondary, 18).apply {
                    (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
                })
            })
            addView(ui.text(13f, ui.secondary).apply { text = activity.getString(R.string.macro_editor_title, title) })
        }
        addView(nameBlock, LinearLayout.LayoutParams(0, wrap, 1f))
        saveButton = ui.pill(activity.getString(R.string.macro_save), filled = true) { save() }
        addView(saveButton)
    }

    private fun emptyState(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(8), dp(24), dp(8))
        // Every child wraps: a vertical LinearLayout that only wraps sizes itself by its fixed-size children.
        addView(ui.text(22f, bold = true).apply { setText(R.string.macro_empty_title); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(wrap, wrap))
        emptyBody = ui.text(15f, ui.secondary).apply {
            gravity = Gravity.CENTER
            maxWidth = dp(520)
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(14), 0, dp(28))
        }
        addView(emptyBody, LinearLayout.LayoutParams(wrap, wrap))
        addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            addByHand = ui.pill(activity.getString(R.string.macro_add_manual), filled = false) { palette(null) }
            addView(addByHand)
            loadSaved = ui.pill(activity.getString(R.string.macro_load_title), filled = false) { requestLoad() }
            addView(loadSaved, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(12) })
        }, LinearLayout.LayoutParams(wrap, wrap))
    }

    private fun timelineState(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        summary = ui.text(15f, bold = true).apply { gravity = Gravity.CENTER }
        addView(summary, LinearLayout.LayoutParams(full, wrap))
        // The hint sits with the summary, out of the way of the steps.
        editHint = ui.text(13f, ui.secondary).apply {
            setText(R.string.macro_edit_hint)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        addView(editHint, LinearLayout.LayoutParams(full, wrap))
        strip = StepStrip(activity).apply {
            gravity = Gravity.TOP
            setPadding(dp(28), dp(26), dp(28), dp(8))
            clipChildren = false
            setMarkerColor(ui.accent)
            listener = this@MacroEditor
        }
        scrollIndicator = ScrollIndicator(activity).apply { setColors(ui.alpha(ui.primary, 50), ui.alpha(ui.primary, 150)) }
        timelineScroll = HorizontalScrollView(activity).apply {
            isFillViewport = true
            clipChildren = false
            // Faded edges say there is more without a bar crowding the steps; the indicator below shows where.
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(56))
            setOnScrollChangeListener { _, _, _, _, _ -> syncIndicator() }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncIndicator() }
            addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER
                addView(strip)
            }, FrameLayout.LayoutParams(wrap, wrap))
        }
        addView(timelineScroll, LinearLayout.LayoutParams(full, wrap))
        addView(scrollIndicator, LinearLayout.LayoutParams(dp(48), dp(4)).apply { topMargin = dp(18); bottomMargin = dp(24) })
    }

    private fun syncIndicator() {
        val content = timelineScroll.getChildAt(0)?.width ?: 0
        scrollIndicator.update(timelineScroll.scrollX, timelineScroll.width, content)
    }

    private fun recordingState(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(View(activity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(MacroUi.DANGER) }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(10) })
            recordingLabel = ui.text(20f, bold = true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
            // Wrapped, not the vertical default of matching the parent: the dot would set the width.
            addView(recordingLabel, LinearLayout.LayoutParams(wrap, wrap))
        })
        recordingHint = ui.text(14f, ui.secondary).apply {
            setText(R.string.macro_recording_hint)
            gravity = Gravity.CENTER
            maxWidth = dp(460)
            setPadding(dp(24), dp(10), dp(24), dp(12))
        }
        addView(recordingHint, LinearLayout.LayoutParams(wrap, wrap))
    }

    private fun controlBar(): View = LinearLayout(activity).apply {
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(6), dp(20), dp(6))
        background = ui.rounded(ui.surface, 32)
        undoButton = barButton(R.string.macro_undo, ui.icon(R.drawable.ic_macro_undo)) { undo() }
        recordButton = barButton(R.string.macro_record, ImageView(activity).apply {
            setImageDrawable(recordIcon())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(dp(RECORD_DP), dp(RECORD_DP))
        }) { if (recorder == null) startRecording() else stopRecording() }
        previewButton = barButton(R.string.macro_preview, ui.icon(R.drawable.ic_macro_play)) { if (previewStep == null) startPreview() else stopPreview() }
        moreButton = barButton(R.string.macro_more, ui.icon(R.drawable.ic_macro_more)) { more() }
        listOf(undoButton, recordButton, previewButton, moreButton).forEachIndexed { index, button ->
            if (index > 0) space(12)
            addView(button.view)
        }
    }

    private fun barButton(label: Int, icon: ImageView, onClick: () -> Unit): BarButton {
        val text = ui.text(13f).apply {
            setText(label)
            // The record circle fills its slot, so its label needs this gap to breathe; the smaller icons get it on top.
            setPadding(0, dp(8), 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val view = ui.touchable(LinearLayout(activity), 16, onClick).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumWidth = dp(76)
            contentDescription = activity.getString(label)
            background = ui.ripple(borderless = false)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            // The record button sets the bar's height; the others keep their icons centred on it.
            addView(FrameLayout(activity).apply { addView(icon, FrameLayout.LayoutParams(icon.layoutParams.width, icon.layoutParams.height, Gravity.CENTER)) },
                LinearLayout.LayoutParams(dp(RECORD_DP), dp(RECORD_DP)))
            addView(text, LinearLayout.LayoutParams(wrap, wrap))
        }
        return BarButton(view, icon, text)
    }

    private fun setLabel(button: BarButton, label: Int) {
        button.label.setText(label)
        button.view.contentDescription = activity.getString(label)
    }

    private fun binBar(): TextView = ui.text(16f, MacroUi.DANGER, bold = true).apply {
        setText(R.string.macro_drop_delete)
        gravity = Gravity.CENTER
        compoundDrawablePadding = dp(10)
        setCompoundDrawablesRelativeWithIntrinsicBounds(activity.getDrawable(R.drawable.ic_macro_delete)?.mutate()?.apply { setTint(MacroUi.DANGER) }, null, null, null)
        visibility = View.GONE
    }

    private fun snackbar(): View = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(6), dp(8), dp(6))
        background = ui.rounded(ui.primary, 12)
        elevation = dp(6).toFloat()
        snackText = ui.text(15f, ui.windowColor)
        addView(snackText)
        addView(ui.touchable(ui.text(15f, ui.windowColor, bold = true), 12) { undo() }.apply {
            setText(R.string.macro_undo)
            minHeight = dp(44)
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(12), 0)
        })
        visibility = View.GONE
    }

    private fun recordIcon(): Drawable = LayerDrawable(arrayOf(
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2), MacroUi.DANGER) },
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(MacroUi.DANGER) },
    )).apply { setLayerInset(1, dp(6), dp(6), dp(6), dp(6)) }

    private fun stopIcon(): Drawable = LayerDrawable(arrayOf(
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2), MacroUi.DANGER) },
        GradientDrawable().apply { setColor(MacroUi.DANGER); cornerRadius = dp(2).toFloat() },
    )).apply { setLayerInset(1, dp(13), dp(13), dp(13), dp(13)) }

    // ---- Rendering ----

    private val dirty: Boolean get() = steps != savedSteps || name != savedName

    private fun render() {
        val recording = recorder != null
        emptyState.visibility = if (!recording && steps.isEmpty()) View.VISIBLE else View.GONE
        recordingState.visibility = if (recording) View.VISIBLE else View.GONE
        editHint.visibility = if (recording) View.GONE else View.VISIBLE
        emptyBody.setText(emptyHint)
        nameView.text = name ?: activity.getString(R.string.macro_new)
        nameBlock.contentDescription =
            activity.getString(R.string.macro_name_accessibility, nameView.text, activity.getString(R.string.macro_editor_title, title))
        ui.setEnabled(saveButton, !recording && dirty)
        ui.setEnabled(undoButton.view, !recording && history.isNotEmpty())
        ui.setEnabled(previewButton.view, !recording && steps.isNotEmpty())
        val hasLibrary = library().isNotEmpty()
        ui.setEnabled(moreButton.view, !recording && (steps.isNotEmpty() || hasLibrary))
        loadSaved.visibility = if (hasLibrary) View.VISIBLE else View.GONE
        recordButton.icon.setImageDrawable(if (recording) stopIcon() else recordIcon())
        setLabel(recordButton, if (recording) R.string.macro_stop else R.string.macro_record)
        // Recorded steps become editable now, and live ones were not: build the timeline afresh.
        shown = emptyList()
        strip.removeAllViews()
        if (recording) renderLive() else renderTimeline(steps, editable = true, resting = null)
    }

    /** The steps pressed so far; a held press and the pause after the last release keep counting. */
    private fun renderLive() {
        val active = recorder ?: return
        // Nothing arrived yet means no input clock to carry the current moment over to.
        val now = if (lastInputUptime == 0L) null else lastInputAt + (SystemClock.uptimeMillis() - lastInputUptime)
        val live = now?.let { active.finish(it)?.steps }.orEmpty()
        val list = if (live.isEmpty()) recordBase else MacroEdits.append(recordBase, live)
        renderTimeline(list, editable = false, resting = now?.takeIf { live.isNotEmpty() }?.let { active.restingMs(it) })
        timelineScroll.post { timelineScroll.scrollTo(strip.width, 0) }
    }

    /**
     * Shows [list]; with [resting] a trailing pause badge counts the time since the last release.
     * Steps equal to the ones shown keep their views: a live update rebuilds only the tail.
     */
    private fun renderTimeline(list: List<MacroStep>, editable: Boolean, resting: Long?) {
        timelineState.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        if (list.isEmpty()) {
            shown = emptyList()
            strip.removeAllViews()
            stepBoxes.clear()
            gapBadges.clear()
            return
        }
        val total = Macro(list).totalMs + (resting ?: 0L)
        summary.text = activity.resources.getQuantityString(R.plurals.macro_summary, list.size, list.size, seconds(total))
        // Children alternate: step 0, pause 1, step 1, pause 2, …, then the counting pause or the add card.
        val keep = list.indices.takeWhile { it < shown.size && shown[it] == list[it] }.count()
        val keepChildren = (2 * keep - 1).coerceAtLeast(0)
        if (strip.childCount > keepChildren) strip.removeViews(keepChildren, strip.childCount - keepChildren)
        while (stepBoxes.size > keep) stepBoxes.removeAt(stepBoxes.lastIndex)
        while (gapBadges.size > (keep - 1).coerceAtLeast(0)) gapBadges.removeAt(gapBadges.lastIndex)
        for (index in keep until list.size) {
            if (index > 0) strip.addView(gapBadge(index, list[index - 1].gapMs, editable))
            strip.addView(stepNode(index, list[index], editable))
        }
        resting?.let { strip.addView(gapBadge(list.size, Macro.clampGap(Macro.round(it)), editable = false, counted = false)) }
        if (editable) strip.addView(addCard())
        shown = list
    }

    private fun stepNode(index: Int, step: MacroStep, editable: Boolean): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        tag = StepStrip.STEP
        setPadding(dp(2), dp(2), dp(2), dp(2))
        val held = step.holdMs >= HOLD_EMPHASIS_MS
        val short = step.holdMs < SHORT_WARN_MS
        contentDescription = activity.getString(
            R.string.macro_step_accessibility, index + 1, step.inputs.joinToString(", ") { inputName(it) }, holdText(step),
        ).let { if (short) activity.getString(R.string.macro_short_accessibility, it) else it }
        if (editable) {
            ui.touchable(this, 16) { stepSheet(index) }
            setOnLongClickListener { strip.startDrag(this, index); true }
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            // A held press reads differently from a tap at a glance: it gets the accent outline.
            background = if (held) ui.rounded(ui.surface, 18, ui.accent, 2) else ui.rounded(ui.surface, 18, ui.alpha(ui.primary, 50), 1)
            step.inputs.forEachIndexed { i, input ->
                addView(MacroIconView(activity).apply { show(input, ui.primary, ui.accent) },
                    LinearLayout.LayoutParams(dp(40), dp(40)).apply { if (i > 0) topMargin = dp(4) })
            }
        }
        stepBoxes += box
        // Every child wraps: with the fixed-width box beside them, matching children would shrink to the box.
        addView(box, LinearLayout.LayoutParams(wrap, wrap))
        // One quiet line: a tap shows its time, a held press says so in the accent colour.
        addView(ui.text(12f, if (held) ui.accent else ui.secondary, bold = held).apply {
            text = if (held) holdText(step) else activity.getString(R.string.macro_ms, step.holdMs)
            setPadding(0, dp(10), 0, 0)
            if (short) warn(this)
        }, LinearLayout.LayoutParams(wrap, wrap))
    }

    /** The pause of [gap] ms in front of step [index] (0-based), which belongs to the step before it. */
    private fun gapBadge(index: Int, gap: Int, editable: Boolean, counted: Boolean = true): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        // Level with the middle of the step boxes; the whole height stays a comfortable touch target.
        setPadding(dp(4), dp(10), dp(4), dp(12))
        minimumWidth = dp(GAP_WIDTH_DP)
        // The pause still counting while recording is not finished yet, so it is never called too short.
        val short = counted && gap < SHORT_WARN_MS
        contentDescription = activity.getString(R.string.macro_gap_accessibility, gap, index + 1)
            .let { if (short) activity.getString(R.string.macro_short_accessibility, it) else it }
        if (editable) ui.touchable(this, 14) { pauseSheet(index - 1) }
        // Quieter than the steps: a small time over a thin arrow, so the steps stay what the eye follows.
        val badge = ui.text(12f, ui.secondary).apply {
            text = activity.getString(R.string.macro_ms, gap)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            if (short) warn(this)
        }
        if (counted) gapBadges += badge
        addView(badge, LinearLayout.LayoutParams(wrap, wrap))
        addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_macro_pause_arrow)
            imageTintList = ColorStateList.valueOf(ui.alpha(ui.primary, 110))
            // Stretched to the pause's width; a long time label only widens the arrow a little.
            scaleType = ImageView.ScaleType.FIT_XY
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(full, dp(8)).apply { topMargin = dp(3) })
    }

    /** The dashed card at the end that adds a step by hand. */
    private fun addCard(): View = ui.touchable(LinearLayout(activity), 16) { palette(null) }.apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        contentDescription = activity.getString(R.string.macro_add_manual)
        layoutParams = LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(24) }
        addView(LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setStroke(dp(2), ui.alpha(ui.primary, 90), dp(6).toFloat(), dp(4).toFloat())
            }
            addView(ui.icon(R.drawable.ic_macro_add, ui.secondary, 28))
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        addView(ui.text(12f, ui.secondary).apply {
            setText(R.string.macro_add)
            setPadding(0, dp(10), 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(wrap, wrap))
    }

    private fun holdText(step: MacroStep): String =
        "${activity.getString(if (step.holdMs >= HOLD_EMPHASIS_MS) R.string.macro_hold else R.string.macro_tap)} " +
            activity.getString(R.string.macro_ms, step.holdMs)

    private fun inputName(input: MacroInput): String = when (input) {
        is MacroInput.Button -> input.button.wire
        is MacroInput.Dpad -> activity.getString(R.string.macro_input_dpad, directionName(input.direction))
        is MacroInput.Analog -> activity.getString(
            if (input.stick == Stick.LEFT) R.string.macro_input_left_stick else R.string.macro_input_right_stick,
            directionName(input.direction),
        )
    }

    private fun directionName(direction: MacroDirection): String = activity.getString(when (direction) {
        MacroDirection.UP -> R.string.macro_dir_up
        MacroDirection.UP_RIGHT -> R.string.macro_dir_up_right
        MacroDirection.RIGHT -> R.string.macro_dir_right
        MacroDirection.DOWN_RIGHT -> R.string.macro_dir_down_right
        MacroDirection.DOWN -> R.string.macro_dir_down
        MacroDirection.DOWN_LEFT -> R.string.macro_dir_down_left
        MacroDirection.LEFT -> R.string.macro_dir_left
        MacroDirection.UP_LEFT -> R.string.macro_dir_up_left
    })

    private fun seconds(ms: Long): String = activity.getString(R.string.macro_seconds, ms / 1000f)

    // ---- Changes and undo ----

    /** Every edit goes through here, so each one can be undone. */
    private fun change(next: List<MacroStep>, nextName: String? = name) {
        if (next == steps && nextName == name) return
        stopPreview()
        history.addLast(steps to name)
        if (history.size > HISTORY_MAX) history.removeFirst()
        steps = next
        name = nextName
        emptyHint = R.string.macro_empty_body
        render()
    }

    /** [change], unless it would make the macro longer than allowed. */
    private fun tryChange(next: List<MacroStep>): Boolean {
        if (!MacroEdits.fits(next)) {
            Toast.makeText(activity, R.string.macro_limit_reached, Toast.LENGTH_SHORT).show()
            return false
        }
        change(next)
        return true
    }

    private fun undo() {
        val (previous, previousName) = history.removeLastOrNull() ?: return
        stopPreview()
        steps = previous
        name = previousName
        snackbar.visibility = View.GONE
        render()
    }

    private fun showSnackbar(message: Int) {
        snackText.setText(message)
        snackbar.visibility = View.VISIBLE
        handler.removeCallbacks(hideSnackbar)
        handler.postDelayed(hideSnackbar, SNACKBAR_MS)
    }

    private fun deleteStep(index: Int) {
        change(MacroEdits.delete(steps, index))
        showSnackbar(R.string.macro_step_deleted)
    }

    // ---- Drag and drop ----

    override fun onDragStart() {
        stopPreview()
        controlBar.visibility = View.INVISIBLE
        bin.visibility = View.VISIBLE
        bin.background = ui.rounded(ui.alpha(MacroUi.DANGER, 24), 20, ui.alpha(MacroUi.DANGER, 90), 2)
    }

    override fun onDragMove(rawX: Float, rawY: Float) {
        bin.background = ui.rounded(ui.alpha(MacroUi.DANGER, if (overBin(rawX, rawY)) 70 else 24), 20, ui.alpha(MacroUi.DANGER, 90), 2)
        // Near an edge the row scrolls along, so a step can travel the whole macro.
        val location = IntArray(2).also { timelineScroll.getLocationOnScreen(it) }
        when {
            rawX < location[0] + dp(EDGE_DP) -> timelineScroll.scrollBy(-dp(SCROLL_STEP_DP), 0)
            rawX > location[0] + timelineScroll.width - dp(EDGE_DP) -> timelineScroll.scrollBy(dp(SCROLL_STEP_DP), 0)
        }
    }

    override fun onDrop(from: Int, slot: Int, rawX: Float, rawY: Float) {
        if (overBin(rawX, rawY)) deleteStep(from) else change(MacroEdits.move(steps, from, slot))
    }

    override fun onDragEnd() {
        bin.visibility = View.GONE
        controlBar.visibility = View.VISIBLE
    }

    private fun overBin(rawX: Float, rawY: Float): Boolean {
        val location = IntArray(2).also { bin.getLocationOnScreen(it) }
        return rawX >= location[0] && rawX <= location[0] + bin.width && rawY >= location[1] && rawY <= location[1] + bin.height
    }

    // ---- Sheets ----

    /**
     * One step on a landscape sheet: the step as a large card with its buttons on the left, how long
     * it is held on the right, and the rarer actions below a line. ‹ and › walk through the steps.
     */
    private fun stepSheet(index: Int) {
        val sheet = ui.sheet()
        fun fill(at: Int) {
            val step = steps[at]
            sheet.content.removeAllViews()
            sheet.content.addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.iconButton(R.drawable.ic_macro_earlier, activity.getString(R.string.macro_step_previous)) { fill(at - 1) }.also {
                    ui.setEnabled(it, at > 0)
                })
                addView(ui.text(18f, bold = true).apply {
                    text = activity.getString(R.string.macro_step_title, at + 1, steps.size)
                    setPadding(dp(4), 0, dp(4), 0)
                })
                addView(ui.iconButton(R.drawable.ic_macro_later, activity.getString(R.string.macro_step_next)) { fill(at + 1) }.also {
                    ui.setEnabled(it, at < steps.lastIndex)
                })
                addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
                addView(ui.pill(activity.getString(R.string.macro_done), filled = false) { sheet.dialog.dismiss() })
            })
            sheet.content.addView(LinearLayout(activity).apply {
                setPadding(0, dp(18), 0, 0)
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(stepPreview(step) { sheet.dialog.dismiss(); palette(at) }, LinearLayout.LayoutParams(full, 0, 1f))
                    addView(ui.pill(activity.getString(R.string.macro_change), filled = false) { sheet.dialog.dismiss(); palette(at) },
                        LinearLayout.LayoutParams(full, wrap).apply { topMargin = dp(12) })
                }, LinearLayout.LayoutParams(dp(PREVIEW_DP), full).apply { marginEnd = dp(28) })
                addView(timeColumn(
                    activity.getString(R.string.macro_hold_time),
                    listOf(R.string.macro_tap to MacroEdits.TAP_MS, R.string.macro_preset_short to MacroEdits.SHORT_MS, R.string.macro_preset_long to MacroEdits.LONG_MS)
                        .map { (label, ms) -> activity.getString(label) to ms },
                    step.holdMs,
                    activity.getString(R.string.macro_hold_label, Macro.MIN_HOLD_MS, Macro.MAX_HOLD_MS),
                    R.string.macro_short_hold_hint,
                ) { value -> if (tryChange(MacroEdits.withHold(steps, at, value))) fill(at) }, LinearLayout.LayoutParams(0, wrap, 1f))
            })
            sheet.content.addView(actionRow().apply {
                addView(sheetAction(R.drawable.ic_macro_move_earlier, R.string.macro_move_earlier, at > 0) {
                    change(MacroEdits.move(steps, at, at - 1))
                    fill(at - 1)
                })
                addView(sheetAction(R.drawable.ic_macro_move_later, R.string.macro_move_later, at < steps.lastIndex) {
                    change(MacroEdits.move(steps, at, at + 2))
                    fill(at + 1)
                })
                addView(sheetAction(R.drawable.ic_macro_duplicate, R.string.macro_duplicate, true) {
                    if (tryChange(MacroEdits.duplicate(steps, at))) fill(at)
                })
                addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
                addView(sheetAction(R.drawable.ic_macro_delete, R.string.macro_delete, true, MacroUi.DANGER) {
                    sheet.dialog.dismiss()
                    deleteStep(at)
                })
            })
        }
        fill(index)
        sheet.dialog.show()
    }

    /** The step as the timeline shows it, only larger; tapping it changes its buttons. */
    private fun stepPreview(step: MacroStep, onClick: () -> Unit): View = ui.touchable(LinearLayout(activity), 16, onClick).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(14), dp(12), dp(14))
        val held = step.holdMs >= HOLD_EMPHASIS_MS
        background = ui.rounded(ui.surface, 16, if (held) ui.accent else ui.alpha(ui.primary, 40), if (held) 2 else 1)
        contentDescription = activity.getString(R.string.macro_change)
        // Up to eight inputs, three to a row, so the card keeps its width.
        step.inputs.chunked(PREVIEW_PER_ROW).forEachIndexed { row, inputs ->
            addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER
                inputs.forEachIndexed { i, input ->
                    addView(MacroIconView(activity).apply { show(input, ui.primary, ui.accent) },
                        LinearLayout.LayoutParams(dp(44), dp(44)).apply { if (i > 0) marginStart = dp(8) })
                }
            }, LinearLayout.LayoutParams(wrap, wrap).apply { if (row > 0) topMargin = dp(8) })
        }
        addView(FrameLayout(activity).apply {
            background = ui.rounded(ui.alpha(ui.primary, 40), 3)
            addView(View(activity).apply { background = ui.rounded(if (held) ui.accent else ui.alpha(ui.primary, 120), 3) },
                FrameLayout.LayoutParams(maxOf(dp(6), dp(120) * minOf(step.holdMs, MacroEdits.LONG_MS) / MacroEdits.LONG_MS), dp(6)))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(120), dp(6)).apply { topMargin = dp(14) })
        addView(ui.text(13f, ui.secondary).apply {
            text = holdText(step)
            setPadding(0, dp(8), 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(wrap, wrap))
    }

    /**
     * A time asked as a plain question: the usual [presets] as large tiles, then minus, the value
     * (tap it to type an exact one) and plus, in [MacroEdits.NUDGE_MS] steps.
     */
    private fun timeColumn(
        question: String,
        presets: List<Pair<String?, Int>>,
        value: Int,
        exactLabel: String,
        shortHint: Int,
        onValue: (Int) -> Unit,
    ): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.text(14f, ui.secondary).apply { text = question }, LinearLayout.LayoutParams(full, wrap))
            addView(LinearLayout(activity).apply {
                presets.forEachIndexed { i, (label, ms) ->
                    addView(presetTile(label, ms, value == ms) { onValue(ms) },
                        LinearLayout.LayoutParams(0, full, 1f).apply { if (i > 0) marginStart = dp(8) })
                }
            }, LinearLayout.LayoutParams(full, wrap).apply { topMargin = dp(10) })
            addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER
                // A light wash of the text colour: the floating surface matches the sheet in the dark theme.
                background = ui.rounded(ui.alpha(ui.primary, 18), 30)
                setPadding(dp(6), dp(6), dp(6), dp(6))
                addView(ui.iconButton(R.drawable.ic_macro_minus, activity.getString(R.string.macro_shorter)) { onValue(value - MacroEdits.NUDGE_MS) })
                addView(ui.touchable(ui.text(22f, bold = true), 12) { exact(exactLabel, value, onValue) }.apply {
                    text = activity.getString(R.string.macro_ms, value)
                    gravity = Gravity.CENTER
                    minWidth = dp(140)
                    minHeight = dp(48)
                    contentDescription = activity.getString(R.string.macro_exact_accessibility, text)
                })
                addView(ui.iconButton(R.drawable.ic_macro_add, activity.getString(R.string.macro_longer)) { onValue(value + MacroEdits.NUDGE_MS) })
            }, LinearLayout.LayoutParams(full, wrap).apply { topMargin = dp(16) })
            // A value some games may miss takes the hint's place, so the sheet keeps its height.
            val short = value < SHORT_WARN_MS
            addView(ui.text(12f, ui.secondary).apply {
                setText(if (short) shortHint else R.string.macro_exact_hint)
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
                if (short) warn(this)
            }, LinearLayout.LayoutParams(full, wrap))
        }

    /**
     * Marks [view]'s text as a time some games may miss: the warning colour and a small sign in
     * front. The sign is part of the text, so it stays beside the first word when the text wraps.
     */
    private fun warn(view: TextView) {
        view.setTextColor(ui.warning)
        val sign = activity.getDrawable(R.drawable.ic_macro_warning)!!.mutate().apply {
            setTint(ui.warning)
            setBounds(0, 0, dp(13), dp(13))
        }
        view.text = SpannableStringBuilder().append(WARNING_SIGN, ImageSpan(sign, ImageSpan.ALIGN_CENTER), 0).append(' ').append(view.text)
    }

    /** A preset as a tile: its name over its time, or the time alone when it has no name. */
    private fun presetTile(label: String?, ms: Int, selected: Boolean, onClick: () -> Unit): View =
        ui.touchable(LinearLayout(activity), 14, onClick).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumHeight = dp(56)
            setPadding(dp(4), dp(6), dp(4), dp(6))
            isSelected = selected
            background = if (selected) ui.rounded(ui.alpha(ui.accent, 40), 14, ui.accent, 2) else ui.rounded(Color.TRANSPARENT, 14, ui.alpha(ui.primary, 60), 1)
            val time = if (ms == 0) activity.getString(R.string.macro_preset_none) else activity.getString(R.string.macro_ms, ms)
            contentDescription = label?.let { activity.getString(R.string.macro_preset_label, it, time) } ?: time
            val color = if (selected) ui.accent else ui.primary
            label?.let {
                addView(ui.text(15f, color, bold = true).apply { text = it; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                    LinearLayout.LayoutParams(wrap, wrap))
            }
            addView(ui.text(if (label == null) 15f else 12f, if (label == null || selected) color else ui.secondary, bold = label == null).apply {
                text = time
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(wrap, wrap))
        }

    /** The row under a line at the bottom of a sheet, for actions used less often. */
    private fun actionRow(): LinearLayout = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, 0)
        layoutParams = LinearLayout.LayoutParams(full, wrap).apply { topMargin = dp(20) }
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), ui.alpha(ui.primary, 40))
        }.let { line ->
            // Only the top edge of the stroke shows: the other three sit outside the view.
            InsetDrawable(line, -dp(2), 0, -dp(2), -dp(2))
        }
    }

    private fun sheetAction(icon: Int, label: Int, enabled: Boolean, color: Int = ui.primary, onClick: () -> Unit): View =
        ui.touchable(LinearLayout(activity), 20, onClick).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
            background = ui.ripple(borderless = false)
            setPadding(dp(12), 0, dp(14), 0)
            contentDescription = activity.getString(label)
            addView(ui.icon(icon, color, 20))
            addView(ui.text(14f, color).apply {
                setText(label)
                setPadding(dp(8), 0, 0, 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            ui.setEnabled(this, enabled)
        }

    private fun exact(label: String, value: Int, onValue: (Int) -> Unit) {
        val field = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            // Plain ASCII digits: the field is parsed back with toIntOrNull, whatever the locale.
            setText(String.format(Locale.ROOT, "%d", value))
            setSelectAllOnFocus(true)
            maxLines = 1
        }
        AlertDialog.Builder(activity)
            .setView(textForm(label, field))
            .setNegativeButton(R.string.macro_cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> field.text.toString().toIntOrNull()?.let(onValue) }
            .show()
    }

    /** The pause after step [index], or with null every pause at once; laid out like the step sheet's time. */
    private fun pauseSheet(index: Int?) {
        val sheet = ui.sheet()
        fun fill(value: Int) {
            sheet.header(
                if (index == null) activity.getString(R.string.macro_all_pauses_title) else activity.getString(R.string.macro_gap_title, index + 2),
                activity.getString(R.string.macro_done),
            )
            fun set(ms: Int) {
                val next = if (index == null) MacroEdits.withAllGaps(steps, ms) else MacroEdits.withGap(steps, index, ms)
                if (tryChange(next)) fill(Macro.clampGap(Macro.round(ms.toLong())))
            }
            sheet.content.addView(timeColumn(
                activity.getString(R.string.macro_pause_time),
                MacroEdits.GAP_PRESETS.map { null to it },
                value,
                activity.getString(R.string.macro_gap_label, Macro.MIN_GAP_MS, Macro.MAX_GAP_MS),
                R.string.macro_short_gap_hint,
                ::set,
            ), LinearLayout.LayoutParams(full, wrap).apply { topMargin = dp(18) })
            if (index != null && steps.size > 2) sheet.content.addView(actionRow().apply {
                addView(sheetAction(R.drawable.ic_macro_duplicate, R.string.macro_apply_all, true) {
                    sheet.dialog.dismiss()
                    tryChange(MacroEdits.withAllGaps(steps, value))
                })
            })
        }
        // All pauses start from the one used most, which is what a user means to keep.
        fill(index?.let { steps[it].gapMs } ?: steps.dropLast(1).groupingBy { it.gapMs }.eachCount().maxByOrNull { it.value }?.key ?: 0)
        sheet.dialog.show()
    }

    /**
     * The inputs to press: every button, the D-pad and both sticks in eight directions. Several
     * chosen inputs are pressed together. For [index] it changes that step, for null it adds a step.
     */
    private fun palette(index: Int?) {
        val sheet = ui.sheet(MacroUi.WIDE_SHEET_DP)
        var chosen = index?.let { steps[it].inputs }.orEmpty()
        val tiles = mutableListOf<Pair<MacroInput, View>>()
        lateinit var confirm: View
        fun refresh() {
            tiles.forEach { (input, tile) ->
                val on = input in chosen
                tile.isSelected = on
                tile.background = if (on) ui.rounded(ui.alpha(ui.accent, 50), 14, ui.accent, 3) else ui.rounded(ui.surface, 14)
                tile.contentDescription = if (on) activity.getString(R.string.macro_selected, inputName(input)) else inputName(input)
            }
            ui.setEnabled(confirm, chosen.isNotEmpty())
        }
        fun tile(input: MacroInput): View = ui.touchable(FrameLayout(activity), 14) {
            chosen = MacroEdits.toggle(chosen, input)
            refresh()
        }.apply {
            addView(MacroIconView(activity).apply { show(input, ui.primary, ui.accent) }, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
            tiles += input to this
        }
        fun grid(label: Int, rows: List<List<MacroInput?>>): View = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.text(13f, ui.secondary, bold = true).apply { setText(label); setPadding(0, 0, 0, dp(6)) })
            rows.forEach { row ->
                addView(LinearLayout(activity).apply {
                    row.forEach { input ->
                        addView(input?.let(::tile) ?: View(activity), LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
                    }
                })
            }
        }
        fun directions(make: (MacroDirection) -> MacroInput): List<List<MacroInput?>> = (-1..1).map { dy ->
            (-1..1).map { dx -> MacroDirection.of(dx, dy)?.let(make) }
        }
        sheet.header(
            activity.getString(if (index == null) R.string.macro_palette_add_title else R.string.macro_palette_edit_title),
            activity.getString(R.string.macro_cancel),
        )
        sheet.content.addView(ui.text(14f, ui.secondary).apply { setText(R.string.macro_palette_hint); setPadding(0, dp(4), 0, dp(10)) })
        sheet.content.addView(HorizontalScrollView(activity).apply {
            addView(LinearLayout(activity).apply {
                addView(grid(R.string.macro_palette_buttons, MacroButton.entries.map { MacroInput.Button(it) }.chunked(4)))
                space(14)
                addView(grid(R.string.macro_palette_dpad, directions { MacroInput.Dpad(it) }))
                space(14)
                addView(grid(R.string.macro_palette_left, directions { MacroInput.Analog(Stick.LEFT, it) }))
                space(14)
                addView(grid(R.string.macro_palette_right, directions { MacroInput.Analog(Stick.RIGHT, it) }))
            })
        })
        sheet.row(Gravity.END) {
            setPadding(0, dp(12), 0, 0)
            confirm = ui.pill(activity.getString(if (index == null) R.string.macro_add else R.string.macro_apply), filled = true) {
                if (chosen.isEmpty()) return@pill
                if (index == null) {
                    if (tryChange(MacroEdits.append(steps, listOf(MacroStep(chosen, MacroEdits.TAP_MS))))) {
                        timelineScroll.post { timelineScroll.fullScroll(View.FOCUS_RIGHT) }
                    }
                } else {
                    change(MacroEdits.withInputs(steps, index, chosen))
                }
                sheet.dialog.dismiss()
            }
            addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(confirm)
        }
        refresh()
        sheet.dialog.show()
    }

    private fun textForm(label: String, field: EditText): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(8), dp(24), 0)
        field.id = View.generateViewId()
        addView(ui.text(14f, ui.secondary).apply {
            text = label
            labelFor = field.id
        })
        addView(field, LinearLayout.LayoutParams(full, wrap))
    }

    // ---- More menu ----

    private fun more() {
        PopupMenu(activity, moreButton.view).apply {
            if (library().isNotEmpty()) menu.add(0, MENU_LOAD, 0, R.string.macro_load_title)
            menu.add(0, MENU_PAUSES, 1, R.string.macro_all_pauses).isEnabled = steps.size > 1
            if (editing != null && steps.isNotEmpty()) menu.add(0, MENU_SAVE_AS_NEW, 2, R.string.macro_save_as_new)
            menu.add(0, MENU_CLEAR, 3, R.string.macro_clear_all).isEnabled = steps.isNotEmpty()
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_LOAD -> requestLoad()
                    MENU_PAUSES -> pauseSheet(null)
                    MENU_SAVE_AS_NEW -> nameDialog(R.string.macro_name_title, SavedMacro.freeName(name ?: defaultName, library().map { it.name })) { chosen ->
                        onSave(null, chosen, Macro(steps))
                        dialog.dismiss()
                    }
                    else -> {
                        change(emptyList())
                        showSnackbar(R.string.macro_cleared)
                    }
                }
                true
            }
            show()
        }
    }

    /** Opens a library macro for editing in place of what is shown; unsaved changes are asked about first. */
    private fun requestLoad() {
        val macros = library()
        if (recorder != null || macros.isEmpty()) return
        unlessChanged {
            AlertDialog.Builder(activity)
                .setTitle(R.string.macro_load_title)
                .setItems(macros.map { macroLine(activity, it) }.toTypedArray()) { _, which ->
                    val chosen = macros[which]
                    stopPreview()
                    editing = chosen
                    savedSteps = chosen.macro.steps
                    savedName = chosen.name
                    steps = savedSteps
                    name = savedName
                    history.clear()
                    emptyHint = R.string.macro_empty_body
                    render()
                }
                .setNegativeButton(R.string.macro_cancel, null)
                .show()
        }
    }

    // ---- Naming and saving ----

    private fun freeDefaultName(): String = SavedMacro.freeName(defaultName, library().map { it.name })

    private fun nameDialog(titleRes: Int, initial: String, onName: (String) -> Unit) {
        val field = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(SavedMacro.MAX_NAME))
            setText(initial)
            setSelectAllOnFocus(true)
            maxLines = 1
        }
        AlertDialog.Builder(activity)
            .setTitle(titleRes)
            .setView(textForm(activity.getString(R.string.macro_name_label), field))
            .setNegativeButton(R.string.macro_cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> onName(SavedMacro.cleanName(field.text.toString(), initial)) }
            .show()
    }

    private fun rename() {
        if (recorder != null) return
        nameDialog(R.string.macro_rename_title, name ?: freeDefaultName()) { change(steps, it) }
    }

    private fun save() {
        if (recorder != null || !dirty) return
        val current = editing
        when {
            steps.isEmpty() -> onSave(null, "", null)
            current != null -> onSave(current.id, name ?: current.name, Macro(steps))
            // A new macro is named once, when it is first saved.
            else -> {
                nameDialog(R.string.macro_name_title, name ?: freeDefaultName()) { chosen ->
                    onSave(null, chosen, Macro(steps))
                    dialog.dismiss()
                }
                return
            }
        }
        dialog.dismiss()
    }

    /** Runs [action] right away, or once the user agreed to drop unsaved changes. */
    private fun unlessChanged(action: () -> Unit) {
        if (!dirty) {
            action()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.macro_discard_title)
            .setNegativeButton(R.string.macro_keep_editing, null)
            .setPositiveButton(R.string.macro_discard) { _, _ -> action() }
            .show()
    }

    private fun requestCancel() {
        if (recorder != null) {
            stopRecording()
            return
        }
        unlessChanged { dialog.dismiss() }
    }

    // ---- Recording ----

    private fun startRecording() {
        if (!PService.cachedAvailable()) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.macro_unavailable_title)
                .setMessage(R.string.macro_unavailable_body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val bridge = SwitcherService.macroRecorder ?: run {
            Toast.makeText(activity, R.string.macro_helper_missing, Toast.LENGTH_SHORT).show()
            return
        }
        // A recording adds to the end; it may only run as long as the macro has room left.
        val budget = Macro.MAX_TOTAL_MS - if (steps.isEmpty()) 0L else Macro(steps).totalMs + MacroEdits.DEFAULT_GAP_MS
        if (steps.size >= Macro.MAX_STEPS || budget < Macro.MIN_HOLD_MS) {
            Toast.makeText(activity, R.string.macro_limit_reached, Toast.LENGTH_SHORT).show()
            return
        }
        stopPreview()
        snackbar.visibility = View.GONE
        val fresh = MacroRecorder(flipped())
        recorder = fresh
        recordBase = steps
        recordBudget = budget
        recordStartedAt = SystemClock.uptimeMillis()
        lastInputAt = 0L
        lastInputUptime = 0L
        bridge.start { parts ->
            if (recorder !== fresh) return@start
            fresh.accept(parts)
            parts.firstOrNull()?.toLongOrNull()?.let {
                lastInputAt = it
                lastInputUptime = SystemClock.uptimeMillis()
            }
        }
        render()
        handler.post(ticker)
    }

    private fun stopRecording() {
        val active = recorder ?: return
        endRecording()
        // The helper's times run on the input clock; carry the moment of Stop over onto that clock.
        val stopAt = lastInputAt + (SystemClock.uptimeMillis() - lastInputUptime)
        val macro = active.finish(stopAt)
        if (macro == null) {
            if (steps.isEmpty()) emptyHint = R.string.macro_nothing_recorded
            else Toast.makeText(activity, R.string.macro_nothing_recorded, Toast.LENGTH_SHORT).show()
            render()
            return
        }
        val joined = MacroEdits.append(steps, macro.steps)
        val fitted = MacroEdits.fit(joined)
        if (fitted.size < joined.size) Toast.makeText(activity, R.string.macro_limit_reached, Toast.LENGTH_SHORT).show()
        change(fitted)
        timelineScroll.post { timelineScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun endRecording() {
        if (recorder == null) return
        recorder = null
        handler.removeCallbacks(ticker)
        SwitcherService.macroRecorder?.stop()
    }

    // ---- Preview ----

    /** Lights the steps and pauses up one after another in real time; nothing is sent to the pad. */
    private fun startPreview() {
        if (steps.isEmpty()) return
        setLabel(previewButton, R.string.macro_preview_stop)
        previewButton.icon.setImageResource(R.drawable.ic_macro_stop)
        playStep(0)
    }

    private fun playStep(index: Int) {
        light(stepBoxes.getOrNull(index))
        val next = Runnable {
            val gap = steps.getOrNull(index)?.gapMs ?: 0
            if (index >= steps.lastIndex) {
                stopPreview()
            } else {
                light(gapBadges.getOrNull(index))
                val after = Runnable { playStep(index + 1) }
                previewStep = after
                handler.postDelayed(after, gap.toLong())
            }
        }
        previewStep = next
        handler.postDelayed(next, steps[index].holdMs.toLong())
    }

    private fun light(view: View?) {
        lit?.apply { scaleX = 1f; scaleY = 1f }
        lit = view
        view ?: return
        view.scaleX = PREVIEW_SCALE
        view.scaleY = PREVIEW_SCALE
        val holder = view.parent as View
        timelineScroll.smoothScrollTo(maxOf(0, holder.left + holder.width / 2 - timelineScroll.width / 2), 0)
    }

    private fun stopPreview() {
        val running = previewStep ?: return
        handler.removeCallbacks(running)
        previewStep = null
        light(null)
        setLabel(previewButton, R.string.macro_preview)
        previewButton.icon.setImageResource(R.drawable.ic_macro_play)
    }

    companion object {
        /** "Name · 10 steps · 7.4 s", as the library lists show a macro. */
        fun macroLine(context: Context, saved: SavedMacro): String =
            context.getString(R.string.macro_named_summary, saved.name, summary(context, saved.macro))

        fun summary(context: Context, macro: Macro): String = context.resources.getQuantityString(
            R.plurals.macro_summary, macro.steps.size, macro.steps.size, context.getString(R.string.macro_seconds, macro.totalMs / 1000f),
        )

        private const val TICK_MS = 100L
        private const val HISTORY_MAX = 50
        private const val SNACKBAR_MS = 4000L
        private const val PREVIEW_SCALE = 1.15f
        private const val EDGE_DP = 56
        private const val SCROLL_STEP_DP = 16
        private const val MENU_LOAD = 4
        private const val MENU_PAUSES = 1
        private const val MENU_SAVE_AS_NEW = 2
        private const val MENU_CLEAR = 3
        /** From this hold time on a step reads as held rather than tapped. */
        /** The step card on the step sheet, and how many inputs share one row on it. */
        /** The record button: a touch larger than the other bar icons, without dwarfing them. */
        const val RECORD_DP = 40
        /** The room a pause takes between two steps. */
        const val GAP_WIDTH_DP = 64
        /** Below this many ms a pause or press may fall between two frames a game reads its input in. */
        const val SHORT_WARN_MS = 50
        /** Stands in for the warning sign in the text; screen readers read it as "warning". */
        const val WARNING_SIGN = "\u26A0"
        const val PREVIEW_DP = 200
        const val PREVIEW_PER_ROW = 3
        const val HOLD_EMPHASIS_MS = 250
    }
}

/**
 * One input of a macro step as a 40 dp glyph: a lettered key, a D-pad with the pressed arm lit, or a
 * stick as a ring with a dot pushed towards its direction.
 */
class MacroIconView(context: Context) : View(context) {
    private var input: MacroInput? = null
    private var glyph: Drawable? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lit = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val rect = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show(input: MacroInput, color: Int, accent: Int) {
        this.input = input
        fill.color = Color.argb(34, Color.red(color), Color.green(color), Color.blue(color))
        lit.color = accent
        ring.color = Color.argb(150, Color.red(color), Color.green(color), Color.blue(color))
        ring.strokeWidth = resources.displayMetrics.density * 2
        text.color = color
        glyph = (input as? MacroInput.Button)?.button?.let {
            when (it) {
                MacroButton.START -> context.getDrawable(R.drawable.ic_exc_start)
                MacroButton.SELECT -> context.getDrawable(R.drawable.ic_exc_select)
                else -> null
            }
        }?.mutate()?.apply { setTint(color) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val cy = h / 2
        when (val current = input) {
            is MacroInput.Button -> {
                rect.set(0f, 0f, w, h)
                canvas.drawRoundRect(rect, w / 2, h / 2, fill)
                val icon = glyph
                if (icon != null) {
                    val inset = (w * 0.22f).toInt()
                    icon.setBounds(inset, inset, width - inset, height - inset)
                    icon.draw(canvas)
                } else {
                    text.textSize = h * if (current.button.wire.length > 1) 0.34f else 0.44f
                    canvas.drawText(current.button.wire, cx, cy - (text.descent() + text.ascent()) / 2, text)
                }
            }
            is MacroInput.Dpad -> {
                val arm = w * 0.3f
                val reach = w * 0.48f
                rect.set(cx - arm / 2, cy - reach, cx + arm / 2, cy + reach)
                canvas.drawRoundRect(rect, arm / 4, arm / 4, fill)
                rect.set(cx - reach, cy - arm / 2, cx + reach, cy + arm / 2)
                canvas.drawRoundRect(rect, arm / 4, arm / 4, fill)
                val d = current.direction
                if (d.dy != 0) {
                    rect.set(cx - arm / 2, if (d.dy < 0) cy - reach else cy + arm / 2, cx + arm / 2, if (d.dy < 0) cy - arm / 2 else cy + reach)
                    canvas.drawRoundRect(rect, arm / 4, arm / 4, lit)
                }
                if (d.dx != 0) {
                    rect.set(if (d.dx < 0) cx - reach else cx + arm / 2, cy - arm / 2, if (d.dx < 0) cx - arm / 2 else cx + reach, cy + arm / 2)
                    canvas.drawRoundRect(rect, arm / 4, arm / 4, lit)
                }
            }
            is MacroInput.Analog -> {
                val radius = w / 2 - ring.strokeWidth
                canvas.drawCircle(cx, cy, radius, fill)
                canvas.drawCircle(cx, cy, radius, ring)
                text.textSize = h * 0.26f
                canvas.drawText(current.stick.wire, cx, cy - (text.descent() + text.ascent()) / 2, text)
                val d = current.direction
                val scale = if (d.dx != 0 && d.dy != 0) 0.707f else 1f
                val reach = radius * 0.58f * scale
                canvas.drawCircle(cx + d.dx * reach, cy + d.dy * reach, w * 0.13f, lit)
            }
            null -> Unit
        }
    }
}

/**
 * The saved macros for one hotkey: tap one to play it on that hotkey, or start a new one; each
 * entry's menu renames or deletes it. Deleting asks first, and says when hotkeys still play it.
 */
class MacroLibraryDialog(
    private val activity: Activity,
    private val prefs: Prefs,
    private val currentId: String?,
    /** A library macro was chosen, or null for a new recording. */
    private val onChoose: (SavedMacro?) -> Unit,
    /** Opens [SavedMacro] in the editor without changing which macro the hotkey plays. */
    private val onEdit: (SavedMacro) -> Unit,
    /** The library changed (renamed or deleted), so rows describing macros must refresh. */
    private val onChanged: () -> Unit,
) {
    private val primary = activity.themedColor(android.R.attr.textColorPrimary, Color.BLACK)
    private val secondary = activity.themedColor(android.R.attr.textColorSecondary, Color.GRAY)
    private val accent = activity.themedColor(android.R.attr.colorAccent, Color.parseColor("#6750A4"))
    private lateinit var dialog: AlertDialog
    private lateinit var list: LinearLayout

    private fun dp(value: Int) = activity.dp(value)

    fun show(): AlertDialog {
        list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.macro_library_title)
            .setView(ScrollView(activity).apply { addView(list) })
            .setNegativeButton(R.string.macro_cancel, null)
            .show()
        fill()
        return dialog
    }

    private fun fill() {
        list.removeAllViews()
        list.addView(entry(activity.getString(R.string.macro_new), activity.getString(R.string.macro_new_hint), current = false, menu = null) {
            dialog.dismiss()
            onChoose(null)
        })
        val library = prefs.macroLibrary
        if (library.isEmpty()) list.addView(TextView(activity).apply {
            setText(R.string.macro_library_empty)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(secondary)
            setPadding(dp(24), dp(12), dp(24), dp(12))
        })
        library.forEach { saved ->
            list.addView(entry(saved.name, MacroEditor.summary(activity, saved.macro), saved.id == currentId, saved) {
                dialog.dismiss()
                onChoose(saved)
            })
        }
    }

    private fun entry(name: String, detail: String, current: Boolean, menu: SavedMacro?, onClick: () -> Unit): View =
        LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                background = selectable(borderless = false)
                setPadding(dp(24), dp(8), dp(8), dp(8))
                contentDescription = if (current) activity.getString(R.string.macro_library_current, name, detail) else "$name, $detail"
                setOnClickListener { onClick() }
                addView(TextView(activity).apply {
                    text = name
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(if (current) accent else primary)
                    if (current) setTypeface(typeface, Typeface.BOLD)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                addView(TextView(activity).apply {
                    text = detail
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(secondary)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            menu?.let { saved ->
                addView(ImageView(activity).apply {
                    setImageResource(R.drawable.ic_macro_more)
                    imageTintList = ColorStateList.valueOf(secondary)
                    scaleType = ImageView.ScaleType.CENTER
                    isClickable = true
                    isFocusable = true
                    background = selectable(borderless = true)
                    contentDescription = activity.getString(R.string.macro_options, saved.name)
                    setOnClickListener { view -> showMenu(view, saved) }
                }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })
            }
        }

    private fun selectable(borderless: Boolean): Drawable? = TypedValue().let { value ->
        activity.theme.resolveAttribute(
            if (borderless) android.R.attr.selectableItemBackgroundBorderless else android.R.attr.selectableItemBackground, value, true,
        )
        activity.getDrawable(value.resourceId)
    }

    private fun showMenu(anchor: View, saved: SavedMacro) {
        PopupMenu(activity, anchor).apply {
            menu.add(0, MENU_EDIT, 0, R.string.macro_edit)
            menu.add(0, MENU_RENAME, 1, R.string.macro_rename)
            menu.add(0, MENU_DUPLICATE, 2, R.string.macro_duplicate)
            menu.add(0, MENU_DELETE, 3, R.string.macro_delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_EDIT -> {
                        dialog.dismiss()
                        onEdit(saved)
                    }
                    MENU_RENAME -> rename(saved)
                    MENU_DUPLICATE -> duplicate(saved)
                    else -> delete(saved)
                }
                true
            }
            show()
        }
    }

    private fun rename(saved: SavedMacro) {
        val field = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(SavedMacro.MAX_NAME))
            setText(saved.name)
            setSelectAllOnFocus(true)
            maxLines = 1
            id = View.generateViewId()
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.macro_rename_title)
            .setView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(8), dp(24), 0)
                addView(TextView(activity).apply {
                    setText(R.string.macro_name_label)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(secondary)
                    labelFor = field.id
                })
                addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
            .setNegativeButton(R.string.macro_cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.renameMacro(saved.id, field.text.toString())
                fill()
                onChanged()
            }
            .show()
    }

    private fun duplicate(saved: SavedMacro) {
        prefs.saveMacro(null, SavedMacro.freeName(saved.name, prefs.macroLibrary.map { it.name }), saved.macro)
        fill()
        onChanged()
    }

    private fun delete(saved: SavedMacro) {
        val users = prefs.macroUsers(saved.id).size
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.macro_delete_title, saved.name))
            .setMessage(
                if (users == 0) activity.getString(R.string.macro_delete_body)
                else activity.resources.getQuantityString(R.plurals.macro_delete_used, users, users),
            )
            .setNegativeButton(R.string.macro_cancel, null)
            .setPositiveButton(R.string.macro_delete) { _, _ ->
                prefs.deleteMacro(saved.id)
                fill()
                onChanged()
            }
            .show()
    }

    private companion object {
        const val MENU_RENAME = 1
        const val MENU_DUPLICATE = 2
        const val MENU_DELETE = 3
        const val MENU_EDIT = 4
    }
}
