package dev.steinhorst.backbuttonhero

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.InsetDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import kotlin.math.sqrt

/** Per-app exceptions; every change is written to [Prefs] at once, so the service follows immediately. */
class ExceptionSettingsPage(private val activity: Activity, page: SettingsPage, private val prefs: Prefs) {
    private data class App(val name: String, val packageName: String, val system: Boolean = false)
    private data class Column(val control: ExceptionControl, val label: Int, val description: Int)
    private class RowViews(val texts: List<TextView>, val checks: List<CheckBox>, val all: CheckBox)

    private val columns = listOf(
        Column(ExceptionControl.SINGLE, R.string.exceptions_single_column, R.string.single_section_title),
        Column(ExceptionControl.DOUBLE, R.string.exceptions_double_column, R.string.double_section_title),
        Column(ExceptionControl.TAP_HOLD, R.string.exceptions_tap_hold_column, R.string.tap_hold_section_title),
        Column(ExceptionControl.HOLD, R.string.exceptions_hold_column, R.string.hold_section_title),
        Column(ExceptionControl.L1, R.string.shoulder_l1, R.string.shoulder_l1),
        Column(ExceptionControl.R1, R.string.shoulder_r1, R.string.shoulder_r1),
        Column(ExceptionControl.L2, R.string.shoulder_l2, R.string.shoulder_l2),
        Column(ExceptionControl.R2, R.string.shoulder_r2, R.string.shoulder_r2),
        Column(ExceptionControl.UP, R.string.exceptions_dpad_up_column, R.string.dpad_up),
        Column(ExceptionControl.DOWN, R.string.exceptions_dpad_down_column, R.string.dpad_down),
        Column(ExceptionControl.LEFT, R.string.exceptions_dpad_left_column, R.string.dpad_left),
        Column(ExceptionControl.RIGHT, R.string.exceptions_dpad_right_column, R.string.dpad_right),
    )
    private val groups = listOf(
        R.string.exceptions_group_back, R.string.exceptions_group_shoulders, R.string.exceptions_group_dpad,
    )
    private val full = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private var exceptions = prefs.appExceptions
    private val rows = mutableMapOf<String, RowViews>()
    // Rows are refreshed in place, so their own listeners must not see those programmatic changes.
    private var binding = false
    private val matrix = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var horizontal: HorizontalScrollView? = null
    private var picker: AlertDialog? = null
    private var dialog: AlertDialog? = null
    private val cell = dp(48)
    private val activeSlot = dp(64)
    private val removeSlot = dp(64)
    private val groupHeight = dp((32 * activity.resources.configuration.fontScale.coerceAtLeast(1f)).toInt())
    private val rowHeight = dp((56 * activity.resources.configuration.fontScale.coerceAtLeast(1f)).toInt())

    init {
        page.sectionHeader(R.string.exceptions_apps_title)
        page.card { customRow(matrix) }
        render()
    }

    private fun dp(value: Int) = activity.dp(value)
    private fun primary() = activity.themedColor(android.R.attr.textColorPrimary, Color.BLACK)
    private fun secondary() = activity.themedColor(android.R.attr.textColorSecondary, Color.GRAY)

    fun dispose() {
        picker?.dismiss()
        dialog?.dismiss()
    }

    private fun save(updated: List<AppException>) {
        exceptions = updated
        prefs.appExceptions = updated
    }

    private fun update(pkg: String, change: (AppException) -> AppException) {
        val before = exceptions.firstOrNull { it.packageName == pkg } ?: return
        val after = change(before)
        save(exceptions.map { if (it.packageName == pkg) after else it })
        bind(after)
        // Telling the user once, at the moment it happens, explains why the other boxes went grey.
        if (after.backNative && !before.backNative) explainNativeBack(after)
    }

    private fun explainNativeBack(exception: AppException) {
        dialog?.dismiss()
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.exceptions_native_title)
            .setMessage(activity.getString(R.string.exceptions_native_message, exception.name))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmRemove(exception: AppException) {
        dialog?.dismiss()
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.exceptions_remove_title)
            .setMessage(activity.getString(R.string.exceptions_remove_message, exception.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.exceptions_remove_confirm) { _, _ ->
                save(exceptions.filter { it.packageName != exception.packageName })
                render()
            }
            .show()
    }

    private fun separator(column: LinearLayout) {
        column.addView(View(activity).apply { setBackgroundColor(secondary()); alpha = 0.12f }, LinearLayout.LayoutParams(full, dp(1)))
    }

    private fun header(text: String, description: String = text) = TextView(activity).apply {
        this.text = text
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(primary())
        gravity = Gravity.CENTER
        setPadding(dp(2), dp(4), dp(2), dp(4))
        contentDescription = description
        tooltipText = description
    }

    /** Column labels rise at 45 degrees from their cell, so every column stays one checkbox wide. */
    /** Paints full-height backgrounds behind the column groups without covering the tilted labels. */
    private class GroupBands(private val width: Int, private val colors: List<Int>) : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint()
        override fun draw(canvas: Canvas) {
            colors.forEachIndexed { index, color ->
                paint.color = color
                canvas.drawRect((bounds.left + index * width).toFloat(), bounds.top.toFloat(),
                    (bounds.left + (index + 1) * width).toFloat(), bounds.bottom.toFloat(), paint)
            }
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private class DiagonalLabel(context: Context) : TextView(context) {
        override fun onDraw(canvas: Canvas) {
            paint.color = currentTextColor
            canvas.save()
            canvas.translate(width / 2f - textSize / 3f, height - paddingBottom.toFloat())
            canvas.rotate(-45f)
            canvas.drawText(text.toString(), 0f, 0f, paint)
            canvas.restore()
        }
    }

    private fun diagonal(text: String, description: String) = DiagonalLabel(activity).apply {
        this.text = text
        textSize = 12f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(primary())
        setPadding(0, 0, 0, dp(6))
        contentDescription = description
        tooltipText = description
    }

    private fun arrow(text: String, description: String) = TextView(activity).apply {
        this.text = text
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        // Arrow glyphs have no bold weight in the system font; a stroke gives them one.
        paint.style = android.graphics.Paint.Style.FILL_AND_STROKE
        paint.strokeWidth = dp(1).toFloat()
        setTextColor(primary())
        gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        setPadding(0, 0, 0, dp(4))
        contentDescription = description
        tooltipText = description
    }

    private fun render() {
        val oldScroll = horizontal?.scrollX ?: 0
        matrix.removeAllViews()
        rows.clear()
        val labels = columns.filter { it.control !in DPAD_CONTROLS }.map { activity.getString(it.label) } +
            activity.getString(R.string.exceptions_all_column)
        val probe = diagonal("", "")
        val diagonalHeight = (labels.maxOf { probe.paint.measureText(it) } / sqrt(2f) + probe.textSize + dp(12)).toInt()
        val names = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val cells = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            // Diagonal labels lean into the neighbouring columns; the trailing padding keeps the last one visible.
            clipChildren = false
            setPadding(0, 0, dp(48), 0)
            // One soft band per group, fading from Back to the D-pad; the text colour keeps it subtle in both themes.
            background = GroupBands(cell * 4, listOf(0.10f, 0.065f, 0.035f).map {
                Color.argb((it * 255).toInt(), Color.red(primary()), Color.green(primary()), Color.blue(primary()))
            })
        }
        // Each label sits right above its column, at the baseline the diagonal labels rise from.
        names.addView(LinearLayout(activity).apply {
            gravity = Gravity.BOTTOM
            fun label(res: Int) = header(activity.getString(res)).apply {
                maxLines = 1
                setPadding(dp(2), dp(4), dp(2), dp(8))
            }
            addView(label(R.string.exceptions_active_column), LinearLayout.LayoutParams(activeSlot, wrap))
            addView(label(R.string.exceptions_remove_column), LinearLayout.LayoutParams(removeSlot, wrap))
            addView(label(R.string.exceptions_app_column).apply { gravity = Gravity.START },
                LinearLayout.LayoutParams(0, wrap, 1f))
        }, LinearLayout.LayoutParams(full, groupHeight + diagonalHeight))
        cells.addView(LinearLayout(activity).apply {
            groups.forEach {
                addView(header(activity.getString(it)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                    maxLines = 1
                }, LinearLayout.LayoutParams(cell * 4, groupHeight))
            }
        })
        cells.addView(LinearLayout(activity).apply {
            clipChildren = false
            // Baseline alignment would push the tilted labels down to the arrows' bottom baseline.
            isBaselineAligned = false
            columns.forEach { column ->
                val label = activity.getString(column.label)
                val description = activity.getString(column.description)
                // Arrows already point somewhere; tilting them would change what they say.
                val view = if (column.control in DPAD_CONTROLS) arrow(label, description) else diagonal(label, description)
                addView(view, LinearLayout.LayoutParams(cell, diagonalHeight))
            }
            addView(diagonal(activity.getString(R.string.exceptions_all_column), activity.getString(R.string.exceptions_all_column)),
                LinearLayout.LayoutParams(cell, diagonalHeight))
        })
        separator(names)
        separator(cells)
        exceptions.forEach { exception ->
            val app = exception.name
            val pkg = exception.packageName
            val texts = mutableListOf<TextView>()
            names.addView(LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, dp(8), 0)
                addView(android.widget.FrameLayout(activity).apply { addView(Switch(activity).apply {
                    tag = "exception_active_$pkg"
                    contentDescription = activity.getString(R.string.exceptions_active, app)
                    tooltipText = contentDescription
                    isChecked = exception.active
                    minimumHeight = dp(48)
                    setOnCheckedChangeListener { _, checked -> if (!binding) update(pkg) { it.copy(active = checked) } }
                }, android.widget.FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER)) }, LinearLayout.LayoutParams(activeSlot, wrap))
                addView(ImageButton(activity).apply {
                    tag = "exception_remove_$pkg"
                    contentDescription = activity.getString(R.string.exceptions_remove_app, app)
                    tooltipText = contentDescription
                    setImageDrawable(activity.getDrawable(R.drawable.ic_settings_remove)!!.mutate().apply { setTint(activity.getColor(R.color.settings_destructive)) })
                    val value = android.util.TypedValue()
                    activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                    background = activity.getDrawable(value.resourceId)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    setOnClickListener { confirmRemove(exception) }
                }, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                    marginStart = (removeSlot - dp(48)) / 2
                    marginEnd = (removeSlot - dp(48)) / 2
                })
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    tooltipText = "$app\n$pkg"
                    addView(TextView(activity).apply {
                        text = app
                        textSize = 14f
                        setTextColor(primary())
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }.also(texts::add))
                    addView(TextView(activity).apply {
                        text = pkg
                        textSize = 11f
                        setTextColor(secondary())
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }.also(texts::add))
                }, LinearLayout.LayoutParams(0, wrap, 1f))
            }, LinearLayout.LayoutParams(full, rowHeight))
            val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            fun checkBox(tag: String, description: String) = CheckBox(activity).apply {
                this.tag = tag
                contentDescription = description
                minimumWidth = cell
                minimumHeight = cell
                gravity = Gravity.CENTER_VERTICAL
                buttonDrawable?.let { drawable ->
                    val inset = ((cell - drawable.intrinsicWidth) / 2).coerceAtLeast(0)
                    buttonDrawable = InsetDrawable(drawable, inset, 0, inset, 0)
                }
            }.also { row.addView(it, LinearLayout.LayoutParams(cell, rowHeight)) }
            val checks = columns.map { column ->
                checkBox("exception_${pkg}_${column.control.ordinal}",
                    activity.getString(R.string.exceptions_cell, app, activity.getString(column.description))).apply {
                    setOnCheckedChangeListener { _, checked ->
                        if (!binding) update(pkg) {
                            it.copy(allowed = if (checked) it.allowed or column.control.bit else it.allowed and column.control.bit.inv())
                        }
                    }
                }
            }
            val all = checkBox("exception_all_$pkg", activity.getString(R.string.exceptions_all_cell, app)).apply {
                setOnCheckedChangeListener { _, checked ->
                    if (!binding) update(pkg) { it.copy(allowed = if (checked) ExceptionControl.ALL else 0) }
                }
            }
            rows[pkg] = RowViews(texts, checks, all)
            bind(exception)
            cells.addView(row)
            separator(names)
            separator(cells)
        }
        val scroll = HorizontalScrollView(activity).apply {
            tag = "exceptions_matrix_scroll"
            addView(cells, android.widget.FrameLayout.LayoutParams(wrap, wrap))
        }
        horizontal = scroll
        matrix.addView(LinearLayout(activity).apply {
            addView(names, LinearLayout.LayoutParams(dp(288), wrap))
            addView(scroll, LinearLayout.LayoutParams(0, wrap, 1f))
        }, LinearLayout.LayoutParams(full, wrap))
        scroll.post { scroll.scrollTo(oldScroll, 0) }
        if (exceptions.isEmpty()) matrix.addView(TextView(activity).apply {
            setText(R.string.exceptions_empty)
            textSize = 14f
            setTextColor(secondary())
            setPadding(dp(16), dp(16), dp(16), dp(16))
        })
    }

    /** An inactive row fades out; with every Back gesture off only the Back columns stay editable. */
    private fun bind(exception: AppException) {
        val views = rows[exception.packageName] ?: return
        binding = true
        views.texts.forEach { it.alpha = if (exception.active) 1f else 0.38f }
        views.checks.forEachIndexed { index, box ->
            val control = columns[index].control
            box.isChecked = exception.allowed and control.bit != 0
            box.isEnabled = exception.active && (control in ExceptionControl.BACK || !exception.backNative)
        }
        views.all.isChecked = exception.allowed == ExceptionControl.ALL
        views.all.isEnabled = exception.active
        binding = false
    }

    private inner class AppsAdapter : BaseAdapter() {
        var apps: List<App> = emptyList()
        override fun getCount() = apps.size
        override fun getItem(position: Int) = apps[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? LinearLayout ?: LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                addView(TextView(activity).apply { textSize = 16f; setTextColor(primary()) })
                addView(TextView(activity).apply { textSize = 12f; setTextColor(secondary()) })
            }
            val app = getItem(position)
            (row.getChildAt(0) as TextView).text = app.name
            (row.getChildAt(1) as TextView).text = app.packageName
            return row
        }
    }

    private fun add(app: App, allowed: Int, picker: AlertDialog) {
        if (exceptions.none { it.packageName == app.packageName }) {
            val exception = AppException(app.packageName, app.name, allowed)
            save(exceptions + exception)
            if (exception.backNative) explainNativeBack(exception)
        }
        render()
        matrix.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(view: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                view.removeOnLayoutChangeListener(this)
                view.requestRectangleOnScreen(android.graphics.Rect(0, (view.height - rowHeight).coerceAtLeast(0), view.width, view.height), true)
            }
        })
        picker.dismiss()
    }

    private fun chooseStart(app: App, picker: AlertDialog) {
        dialog?.dismiss()
        dialog = AlertDialog.Builder(activity)
            .setTitle(app.name)
            .setItems(arrayOf(
                activity.getString(R.string.exceptions_allow_all),
                activity.getString(R.string.exceptions_block_all),
            )) { _, which -> add(app, if (which == 0) ExceptionControl.ALL else 0, picker) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Opened from the page's introduction, like every other page action. */
    fun addApp() = showPicker()

    private fun showPicker() {
        val adapter = AppsAdapter()
        var loaded: List<App> = emptyList()
        val status = TextView(activity).apply {
            setText(R.string.exceptions_loading)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val search = EditText(activity).apply {
            setHint(R.string.exceptions_search)
            contentDescription = activity.getString(R.string.exceptions_search)
            tag = "exceptions_search"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_FILTER
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { field, action, _ ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .hideSoftInputFromWindow(field.windowToken, 0)
                    true
                } else false
            }
        }
        val list = ListView(activity).apply { tag = "exceptions_app_list"; this.adapter = adapter }
        val showSystem = Switch(activity).apply {
            tag = "exceptions_show_system"
            setText(R.string.exceptions_show_system)
            minimumHeight = dp(48)
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(search, LinearLayout.LayoutParams(full, wrap))
            addView(showSystem, LinearLayout.LayoutParams(full, wrap))
            addView(status, LinearLayout.LayoutParams(full, wrap))
            addView(list, LinearLayout.LayoutParams(full, 0, 1f))
        }
        list.emptyView = status
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.exceptions_add_app)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        picker = dialog
        fun filter() {
            val query = search.text.toString().trim()
            // System apps crowd the list; a search is deliberate, so it always looks at them too.
            adapter.apps = loaded.filter { app ->
                exceptions.none { it.packageName == app.packageName } &&
                    (!app.system || showSystem.isChecked || query.isNotEmpty()) &&
                    (app.name.contains(query, ignoreCase = true) || app.packageName.contains(query, ignoreCase = true))
            }
            adapter.notifyDataSetChanged()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = filter()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        showSystem.setOnCheckedChangeListener { _, _ -> filter() }
        list.setOnItemClickListener { _, _, position, _ -> chooseStart(adapter.getItem(position), dialog) }
        dialog.setOnDismissListener { if (picker === dialog) picker = null }
        dialog.show()
        dialog.window?.apply {
            setLayout(minOf(dp(720), dp(activity.resources.configuration.screenWidthDp - 32)),
                minOf(dp(560), dp(activity.resources.configuration.screenHeightDp - 32)))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        Thread({
            val result = runCatching {
                val pm = activity.packageManager
                pm.getInstalledApplications(0)
                    // Only valid package names can be stored; see AppException.decode.
                    .filter { Shell.isPkg(it.packageName) }
                    .map { info ->
                        App(runCatching { info.loadLabel(pm).toString() }.getOrDefault(info.packageName).ifBlank { info.packageName }, info.packageName,
                            info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
                    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || !dialog.isShowing) return@runOnUiThread
                loaded = result.getOrDefault(emptyList())
                status.setText(if (result.isSuccess) R.string.exceptions_no_results else R.string.exceptions_load_failed)
                filter()
            }
        }, "ExceptionAppPicker").apply { isDaemon = true }.start()
    }

    private companion object {
        val DPAD_CONTROLS = setOf(ExceptionControl.UP, ExceptionControl.DOWN, ExceptionControl.LEFT, ExceptionControl.RIGHT)
    }
}
