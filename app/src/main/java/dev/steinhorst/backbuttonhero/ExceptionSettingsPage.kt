package dev.steinhorst.backbuttonhero

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** UI draft only: deliberately has no Prefs/service connection or persistent configuration. */
class ExceptionSettingsPage(private val activity: Activity, page: SettingsPage, saved: Bundle?) {
    private data class App(val name: String, val packageName: String)
    private data class Draft(val app: App, var allowed: Int = ALL_ALLOWED)

    private val controls = listOf(
        R.string.exceptions_single_column to R.string.single_section_title,
        R.string.exceptions_hold_column to R.string.hold_section_title,
        R.string.shoulder_l1 to R.string.shoulder_l1,
        R.string.shoulder_r1 to R.string.shoulder_r1,
        R.string.shoulder_l2 to R.string.shoulder_l2,
        R.string.shoulder_r2 to R.string.shoulder_r2,
        R.string.exceptions_dpad_up_column to R.string.dpad_up,
        R.string.exceptions_dpad_down_column to R.string.dpad_down,
        R.string.exceptions_dpad_left_column to R.string.dpad_left,
        R.string.exceptions_dpad_right_column to R.string.dpad_right,
    )
    private val full = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val drafts = mutableListOf<Draft>()
    private val matrix = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var horizontal: HorizontalScrollView? = null
    private var picker: AlertDialog? = null
    private val rowHeight = dp((56 * activity.resources.configuration.fontScale.coerceAtLeast(1f)).toInt())

    init {
        val packages = saved?.getStringArrayList("packages").orEmpty()
        val names = saved?.getStringArrayList("names").orEmpty()
        val masks = saved?.getIntArray("allowed") ?: intArrayOf()
        packages.distinct().forEachIndexed { index, pkg ->
            drafts += Draft(App(names.getOrElse(index) { pkg }, pkg), masks.getOrElse(index) { ALL_ALLOWED } and ALL_ALLOWED)
        }
        page.sectionHeader(R.string.exceptions_apps_title)
        page.card {
            customRow(LinearLayout(activity).apply {
                setPadding(dp(16), dp(8), dp(16), dp(8))
                val accent = activity.themedColor(android.R.attr.colorAccent, Color.parseColor("#6750A4"))
                addView(Button(activity).apply {
                    setText(R.string.exceptions_add_app)
                    isAllCaps = false
                    textSize = 14f
                    minimumHeight = dp(48)
                    backgroundTintList = ColorStateList.valueOf(accent)
                    setTextColor(if (Color.luminance(accent) > 0.179) Color.BLACK else Color.WHITE)
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    setOnClickListener { showPicker() }
                }, LinearLayout.LayoutParams(wrap, wrap))
            })
            customRow(matrix)
        }
        render()
    }

    private fun dp(value: Int) = activity.dp(value)
    private fun primary() = activity.themedColor(android.R.attr.textColorPrimary, Color.BLACK)
    private fun secondary() = activity.themedColor(android.R.attr.textColorSecondary, Color.GRAY)

    fun saveState(): Bundle = Bundle().apply {
        putStringArrayList("packages", ArrayList(drafts.map { it.app.packageName }))
        putStringArrayList("names", ArrayList(drafts.map { it.app.name }))
        putIntArray("allowed", drafts.map { it.allowed }.toIntArray())
    }

    fun dispose() = picker?.dismiss()

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

    private fun render() {
        val oldScroll = horizontal?.scrollX ?: 0
        matrix.removeAllViews()
        val names = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val cells = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        names.addView(header(activity.getString(R.string.exceptions_app_column)), LinearLayout.LayoutParams(full, rowHeight))
        val headings = LinearLayout(activity)
        controls.forEach { (label, description) ->
            headings.addView(header(activity.getString(label), activity.getString(description)), LinearLayout.LayoutParams(dp(48), rowHeight))
        }
        headings.addView(View(activity), LinearLayout.LayoutParams(dp(48), rowHeight))
        cells.addView(headings)
        separator(names)
        separator(cells)
        drafts.forEach { draft ->
            names.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(4), dp(8), dp(4))
                tooltipText = "${draft.app.name}\n${draft.app.packageName}"
                addView(TextView(activity).apply {
                    text = draft.app.name
                    textSize = 14f
                    setTextColor(primary())
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                addView(TextView(activity).apply {
                    text = draft.app.packageName
                    textSize = 11f
                    setTextColor(secondary())
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(full, rowHeight))
            val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            controls.forEachIndexed { index, (_, label) ->
                row.addView(CheckBox(activity).apply {
                    tag = "exception_${draft.app.packageName}_$index"
                    contentDescription = activity.getString(R.string.exceptions_cell, draft.app.name, activity.getString(label))
                    minimumWidth = dp(48)
                    minimumHeight = dp(48)
                    gravity = Gravity.CENTER_VERTICAL
                    buttonDrawable?.let { drawable ->
                        val inset = ((dp(48) - drawable.intrinsicWidth) / 2).coerceAtLeast(0)
                        buttonDrawable = InsetDrawable(drawable, inset, 0, inset, 0)
                    }
                    isChecked = draft.allowed and (1 shl index) != 0
                    setOnCheckedChangeListener { _, checked ->
                        draft.allowed = if (checked) draft.allowed or (1 shl index) else draft.allowed and (1 shl index).inv()
                    }
                }, LinearLayout.LayoutParams(dp(48), rowHeight))
            }
            row.addView(ImageButton(activity).apply {
                tag = "exception_remove_${draft.app.packageName}"
                contentDescription = activity.getString(R.string.exceptions_remove_app, draft.app.name)
                tooltipText = contentDescription
                setImageDrawable(activity.getDrawable(R.drawable.ic_settings_remove)!!.mutate().apply { setTint(activity.getColor(R.color.settings_destructive)) })
                val value = android.util.TypedValue()
                activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                background = activity.getDrawable(value.resourceId)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener { drafts.remove(draft); render() }
            }, LinearLayout.LayoutParams(dp(48), rowHeight))
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
            addView(names, LinearLayout.LayoutParams(dp(160), wrap))
            addView(scroll, LinearLayout.LayoutParams(0, wrap, 1f))
        }, LinearLayout.LayoutParams(full, wrap))
        scroll.post { scroll.scrollTo(oldScroll, 0) }
        if (drafts.isEmpty()) matrix.addView(TextView(activity).apply {
            setText(R.string.exceptions_empty)
            textSize = 14f
            setTextColor(secondary())
            setPadding(dp(16), dp(16), dp(16), dp(16))
        })
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
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(search, LinearLayout.LayoutParams(full, wrap))
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
            adapter.apps = loaded.filter { app ->
                drafts.none { it.app.packageName == app.packageName } &&
                    (app.name.contains(query, ignoreCase = true) || app.packageName.contains(query, ignoreCase = true))
            }
            adapter.notifyDataSetChanged()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = filter()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        list.setOnItemClickListener { _, _, position, _ ->
            val app = adapter.getItem(position)
            if (drafts.none { it.app.packageName == app.packageName }) drafts += Draft(app)
            render()
            matrix.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(view: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                    view.removeOnLayoutChangeListener(this)
                    view.requestRectangleOnScreen(android.graphics.Rect(0, (view.height - rowHeight).coerceAtLeast(0), view.width, view.height), true)
                }
            })
            dialog.dismiss()
        }
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
                pm.getInstalledApplications(0).map { info ->
                    App(runCatching { info.loadLabel(pm).toString() }.getOrDefault(info.packageName).ifBlank { info.packageName }, info.packageName)
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

    companion object {
        private const val ALL_ALLOWED = (1 shl 10) - 1
    }
}
