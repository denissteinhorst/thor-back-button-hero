package dev.steinhorst.backbuttonhero

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView

/**
 * Per-app exceptions; every change is written to [Prefs] at once, so the service follows immediately.
 * Each app is one row with its active switch and four group buttons (Back, shoulder buttons, D-pad,
 * analog sticks); a button opens [ExceptionGroupDialog] with that group's controls.
 */
class ExceptionSettingsPage(private val activity: Activity, page: SettingsPage, private val prefs: Prefs) {
    private data class App(val name: String, val packageName: String, val system: Boolean = false)
    private class ChipViews(val view: View, val count: TextView, val background: GradientDrawable)
    private class RowViews(val texts: List<TextView>, val chips: Map<ExceptionGroup, ChipViews>)

    private val full = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private var exceptions = prefs.appExceptions
    private val rows = mutableMapOf<String, RowViews>()
    private val matrix = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var picker: AlertDialog? = null
    private var dialog: AlertDialog? = null
    private var groupDialog: ExceptionGroupDialog? = null
    private val accent = activity.themedColor(android.R.attr.colorAccent, Color.parseColor("#6750A4"))

    init {
        page.sectionHeader(R.string.exceptions_apps_title)
        page.card { customRow(matrix) }
        render()
    }

    private companion object {
        /** The switch's track starts this far inside its view, so it lines up with the group buttons below. */
        const val SWITCH_INSET_DP = 10
    }

    private fun dp(value: Int) = activity.dp(value)
    private fun primary() = activity.themedColor(android.R.attr.textColorPrimary, Color.BLACK)
    private fun secondary() = activity.themedColor(android.R.attr.textColorSecondary, Color.GRAY)
    private fun tint(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    fun dispose() {
        picker?.dismiss()
        dialog?.dismiss()
        groupDialog?.dismiss()
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
        groupDialog?.refresh()
        // Telling the user once, at the moment it happens, explains why the other groups went grey.
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

    private fun openGroup(pkg: String, group: ExceptionGroup) {
        groupDialog?.dismiss()
        val modal = ExceptionGroupDialog(
            activity, group, pkg,
            current = { exceptions.firstOrNull { it.packageName == pkg } },
            onChange = { allowed -> update(pkg) { it.copy(allowed = allowed) } },
        )
        groupDialog = modal
        modal.show().setOnDismissListener { if (groupDialog === modal) groupDialog = null }
    }

    /** One tonal button per group: its glyph, name and how many of its controls the app still lets us handle. */
    private fun chip(exception: AppException, group: ExceptionGroup): ChipViews {
        val box = GradientDrawable().apply { cornerRadius = dp(12).toFloat() }
        val count = TextView(activity).apply { textSize = 13f }
        val view = LinearLayout(activity).apply {
            tag = "exception_group_${exception.packageName}_${group.name}"
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(16), dp(4), dp(16), dp(4))
            background = box
            isClickable = true
            isFocusable = true
            val ripple = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
            foreground = activity.getDrawable(ripple.resourceId)
            addView(ImageView(activity).apply {
                setImageDrawable(activity.getDrawable(group.iconRes)!!.mutate().apply { setTint(primary()) })
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
            addView(TextView(activity).apply {
                setText(group.titleRes)
                textSize = 14f
                setTextColor(primary())
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(count, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(8) })
            setOnClickListener { openGroup(exception.packageName, group) }
        }
        return ChipViews(view, count, box)
    }

    private fun render() {
        matrix.removeAllViews()
        rows.clear()
        exceptions.forEachIndexed { index, exception ->
            if (index > 0) matrix.addView(View(activity).apply { setBackgroundColor(secondary()); alpha = 0.12f },
                LinearLayout.LayoutParams(full, dp(1)).apply { setMargins(dp(16), 0, dp(16), 0) })
            val app = exception.name
            val pkg = exception.packageName
            val texts = mutableListOf<TextView>()
            val chips = ExceptionGroup.values().associateWith { chip(exception, it) }
            matrix.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                // One grid: 16 dp at the sides, 8 dp between the group buttons. Counting the air the 48 dp header
                // row carries around its text, the package line, the divider and the next name are each 16 dp
                // apart from what they separate: 8 dp margin under the header, 6 dp above it, 16 dp below the buttons.
                setPadding(dp(16), dp(6), dp(16), dp(16))
                addView(LinearLayout(activity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(Switch(activity).apply {
                        tag = "exception_active_$pkg"
                        contentDescription = activity.getString(R.string.exceptions_active, app)
                        tooltipText = contentDescription
                        isChecked = exception.active
                        minimumHeight = dp(48)
                        setOnCheckedChangeListener { _, checked -> update(pkg) { it.copy(active = checked) } }
                    }, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = -dp(SWITCH_INSET_DP); marginEnd = dp(16) })
                    addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        tooltipText = "$app\n$pkg"
                        addView(TextView(activity).apply {
                            text = app
                            textSize = 15f
                            setTypeface(typeface, Typeface.BOLD)
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
                    addView(ImageButton(activity).apply {
                        tag = "exception_remove_$pkg"
                        contentDescription = activity.getString(R.string.exceptions_remove_app, app)
                        tooltipText = contentDescription
                        setImageDrawable(activity.getDrawable(R.drawable.ic_settings_remove)!!.mutate().apply { setTint(activity.getColor(R.color.settings_destructive)) })
                        val value = TypedValue()
                        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                        background = activity.getDrawable(value.resourceId)
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        setOnClickListener { confirmRemove(exception) }
                    // Its glyph sits 16 dp inside the 48 dp target (12 padding plus the drawable's own margin); pull it out so it meets the chips' edge.
                    }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = -dp(16) })
                }, LinearLayout.LayoutParams(full, wrap))
                // Two by two, so the names stay readable on a narrow screen and the buttons stay big.
                ExceptionGroup.values().toList().chunked(2).forEach { pair ->
                    addView(LinearLayout(activity).apply {
                        pair.forEachIndexed { i, group ->
                            addView(chips.getValue(group).view, LinearLayout.LayoutParams(0, wrap, 1f).apply {
                                if (i == 0) marginEnd = dp(8)
                            })
                        }
                    }, LinearLayout.LayoutParams(full, wrap).apply { topMargin = dp(8) })
                }
            }, LinearLayout.LayoutParams(full, wrap))
            rows[pkg] = RowViews(texts, chips)
            bind(exception)
        }
        if (exceptions.isEmpty()) matrix.addView(TextView(activity).apply {
            setText(R.string.exceptions_empty)
            textSize = 14f
            setTextColor(secondary())
            setPadding(dp(16), dp(16), dp(16), dp(16))
        })
    }

    /** An inactive row fades out; a group with controls switched off is tinted, one Back made unreachable is dimmed. */
    private fun bind(exception: AppException) {
        val views = rows[exception.packageName] ?: return
        views.texts.forEach { it.alpha = if (exception.active) 1f else 0.38f }
        views.chips.forEach { (group, chip) ->
            val on = group.allowedCount(exception)
            val total = group.controls.size
            // Some controls left to the app: the button is tinted. None left: it looks like any other and only
            // the zero turns red, so the state reads without the button changing character.
            val partial = on in 1 until total
            val none = on == 0
            val counts = activity.getString(R.string.exceptions_count, on, total)
            chip.count.text = SpannableString(counts).apply {
                if (none) {
                    setSpan(ForegroundColorSpan(activity.getColor(R.color.settings_destructive)), 0, on.toString().length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(StyleSpan(Typeface.BOLD), 0, on.toString().length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            chip.count.setTextColor(if (partial) accent else secondary())
            chip.count.setTypeface(null, if (partial) Typeface.BOLD else Typeface.NORMAL)
            chip.background.setColor(if (partial) tint(accent, 30) else Color.TRANSPARENT)
            chip.background.setStroke(dp(1), if (partial) accent else tint(secondary(), 90))
            chip.view.alpha = if (!exception.active) 0.38f else if (group.editable(exception)) 1f else 0.55f
            chip.view.contentDescription = activity.getString(
                R.string.exceptions_chip_accessibility, exception.name, activity.getString(group.titleRes), on, total,
            )
        }
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
                view.requestRectangleOnScreen(android.graphics.Rect(0, (view.height - dp(200)).coerceAtLeast(0), view.width, view.height), true)
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
}
