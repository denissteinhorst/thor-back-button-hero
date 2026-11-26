package dev.steinhorst.backbuttonhero.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.LineHeightSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import dev.steinhorst.backbuttonhero.R
import java.text.Collator

/**
 * The hotkey action list: "Not set" on top, a dashed rule, then every action from A to Z. The search
 * above the list also finds the options inside groups and AYN tiles, which the plain list folds away.
 */
internal class ActionChooser(
    private val activity: Activity,
    private val title: CharSequence,
    private val actions: List<Entry>,
    private val options: List<Entry>,
    private val current: String,
    private val onPick: (Entry) -> Unit,
) {
    /**
     * [option] is set only on an entry inside a group or tile, which the search alone shows; [value] is
     * then the group's own entry. [keywords] are never shown, only searched.
     */
    class Entry(val value: String, val label: String, val description: String?, val option: String? = null, val keywords: String = "") {
        /**
         * An option shows its group's description, but searching it would list every option of the group
         * for any word of that text; the group's own entry already stands for it.
         */
        val searchText: String = listOfNotNull(label, keywords, description.takeIf { option == null }).joinToString("\n")
    }

    sealed interface Row {
        class Item(val entry: Entry) : Row
        object Divider : Row
        object NoResults : Row
    }

    private val ctx: Context get() = builder.context
    private val builder = AlertDialog.Builder(activity)
    private val adapter = Adapter()
    private val order = Collator.getInstance(activity.resources.configuration.locales[0])
    private lateinit var dialog: AlertDialog

    fun show(): AlertDialog {
        val padding = dimension(android.R.attr.dialogPreferredPadding, 24)
        val search = EditText(ctx).apply {
            tag = SEARCH_TAG
            setHint(R.string.action_search_hint)
            contentDescription = ctx.getString(R.string.action_search_hint)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER
            // In landscape the keyboard would otherwise take the whole screen and hide the results.
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            setOnEditorActionListener { field, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) {
                    ctx.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
                    true
                } else false
            }
        }
        val clear = ImageButton(ctx).apply {
            tag = CLEAR_TAG
            setImageResource(R.drawable.ic_macro_close)
            imageTintList = ColorStateList.valueOf(secondaryColor())
            contentDescription = ctx.getString(R.string.action_search_clear)
            resolve(android.R.attr.selectableItemBackgroundBorderless)?.resourceId?.takeIf { it != 0 }?.let(::setBackgroundResource)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            visibility = View.GONE
            setOnClickListener {
                search.setText("")
                // Clearing starts a new search: keep typing in the field rather than lose it and the keyboard.
                search.requestFocus()
                ctx.getSystemService(InputMethodManager::class.java).showSoftInput(search, 0)
            }
        }
        val titleStyle = resolve(android.R.attr.windowTitleStyle)?.resourceId ?: 0
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, dp(18), padding, 0)
            addView(TextView(ctx, null, 0, titleStyle).apply { text = title })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(clear, LinearLayout.LayoutParams(dp(40), dp(40)))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
        adapter.rows = rows(actions, options, "", order)
        dialog = builder
            .setCustomTitle(header)
            .setSingleChoiceItems(adapter, checkedRow()) { shown, which ->
                val item = adapter.rows.getOrNull(which) as? Row.Item ?: return@setSingleChoiceItems
                shown.dismiss()
                onPick(item.entry)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.listView.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {
                // Scrolling means reading the results: give them the room; tapping the field brings the keyboard back.
                if (scrollState != AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL || !search.hasFocus()) return
                ctx.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
                search.clearFocus()
            }
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) = Unit
        })
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty()
                clear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                adapter.rows = rows(actions, options, query, order)
                adapter.notifyDataSetChanged()
                dialog.listView.clearChoices()
                checkedRow().takeIf { it >= 0 }?.let { dialog.listView.setItemChecked(it, true) }
                dialog.listView.setSelection(0)
            }
        })
        dialog.show()
        dialog.window?.apply {
            // Without a custom view AlertDialog keeps the keyboard away from the whole window.
            clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            // Full height keeps the field in place while the results shrink, and the window still
            // resizes to the room above the keyboard.
            setLayout(attributes.width, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        // A controller starts in the list as before; the field takes focus only when it is chosen.
        dialog.listView.requestFocus()
        return dialog
    }

    private fun checkedRow(): Int =
        adapter.rows.indexOfFirst { it is Row.Item && it.entry.option == null && it.entry.value == current }

    private fun resolve(attr: Int): TypedValue? = TypedValue().takeIf { ctx.theme.resolveAttribute(attr, it, true) }

    private fun dimension(attr: Int, fallbackDp: Int): Int =
        resolve(attr)?.takeIf { it.type == TypedValue.TYPE_DIMENSION }?.let { TypedValue.complexToDimensionPixelSize(it.data, ctx.resources.displayMetrics) } ?: dp(fallbackDp)

    private fun secondaryColor(): Int = resolve(android.R.attr.textColorSecondary)?.let {
        if (it.resourceId != 0) ctx.getColorStateList(it.resourceId).defaultColor else it.data
    } ?: Color.GRAY

    private fun dp(value: Int): Int = activity.dp(value)

    /** An entry's name with its description below, smaller, in the secondary colour and a little apart. */
    private fun text(entry: Entry): CharSequence {
        val description = entry.description ?: return entry.label
        val start = entry.label.length + 1
        return SpannableString("${entry.label}\n$description").apply {
            setSpan(RelativeSizeSpan(DESCRIPTION_SCALE), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(secondaryColor()), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(GapAbove(dp(4)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private inner class Adapter : BaseAdapter() {
        var rows: List<Row> = emptyList()

        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = when (val row = rows[position]) {
            is Row.Item -> text(row.entry)
            Row.Divider -> ""
            Row.NoResults -> ctx.getString(R.string.action_search_empty)
        }
        override fun getItemId(position: Int): Long = position.toLong()
        override fun hasStableIds(): Boolean = false
        override fun getViewTypeCount(): Int = 3
        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Item -> 0
            Row.Divider -> 1
            Row.NoResults -> 2
        }
        override fun areAllItemsEnabled(): Boolean = false
        override fun isEnabled(position: Int): Boolean = rows[position] is Row.Item

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = when (val row = rows[position]) {
            is Row.Item -> ((convertView as? CheckedTextView) ?: itemView()).apply { text = text(row.entry) }
            Row.Divider -> convertView ?: DashedRule(ctx, secondaryColor(), dimension(android.R.attr.dialogPreferredPadding, 24), dp(1).toFloat()).apply {
                layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(17))
            }
            Row.NoResults -> ((convertView as? TextView) ?: TextView(ctx).apply {
                setTextColor(secondaryColor())
                val padding = dimension(android.R.attr.dialogPreferredPadding, 24)
                setPaddingRelative(padding, dp(12), padding, dp(12))
            }).apply { setText(R.string.action_search_empty) }
        }

        /** The look of the platform's single-choice row, with a little more room above and below. */
        private fun itemView(): CheckedTextView = CheckedTextView(ctx).apply {
            minHeight = dimension(android.R.attr.listPreferredItemHeightSmall, 48)
            resolve(android.R.attr.textAppearanceMedium)?.resourceId?.takeIf { it != 0 }?.let(::setTextAppearance)
            resolve(android.R.attr.textColorAlertDialogListItem)?.resourceId?.takeIf { it != 0 }?.let { setTextColor(ctx.getColorStateList(it)) }
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(20), dp(4), dimension(android.R.attr.dialogPreferredPadding, 24), dp(4))
            val radio = resolve(android.R.attr.listChoiceIndicatorSingle)?.resourceId?.takeIf { it != 0 }?.let(ctx::getDrawable)
            setCompoundDrawablesRelativeWithIntrinsicBounds(radio, null, null, null)
            compoundDrawablePadding = dp(20)
        }
    }

    /** Lifts the first line of a paragraph by [gap] pixels, so a description sits apart from its name. */
    private class GapAbove(private val gap: Int) : LineHeightSpan {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
            if (text !is Spanned || start != text.getSpanStart(this)) return
            fm.top -= gap
            fm.ascent -= gap
        }
    }

    private class DashedRule(context: Context, color: Int, private val inset: Int, stroke: Float) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            this.color = color
            pathEffect = DashPathEffect(floatArrayOf(stroke * 4, stroke * 3), 0f)
        }

        override fun onDraw(canvas: Canvas) {
            val y = height / 2f
            canvas.drawLine(inset.toFloat(), y, (width - inset).toFloat(), y, paint)
        }
    }

    companion object {
        const val SEARCH_TAG = "action_search"
        const val CLEAR_TAG = "action_search_clear"

        /** The description line of an entry, relative to its name. */
        private const val DESCRIPTION_SCALE = 0.72f

        /**
         * Without a [query]: "Not set" (the empty value), a rule, then every action sorted by [order].
         * With one: the actions and the options inside groups whose [Entry.searchText] contains every word
         * of it, so "volume" also lists the volume options and "volume mute" narrows them; sorted the same.
         */
        fun rows(actions: List<Entry>, options: List<Entry>, query: String, order: Comparator<Any?>): List<Row> {
            val sorted = compareBy<Entry, String>(order) { it.label }
            val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) {
                val (none, rest) = actions.partition { it.value.isEmpty() }
                val rule = if (none.isNotEmpty() && rest.isNotEmpty()) listOf(Row.Divider) else emptyList()
                return none.map { Row.Item(it) } + rule + rest.sortedWith(sorted).map { Row.Item(it) }
            }
            val hits = (actions + options).filter { entry -> words.all { entry.searchText.contains(it, ignoreCase = true) } }.sortedWith(sorted)
            return if (hits.isEmpty()) listOf(Row.NoResults) else hits.map { Row.Item(it) }
        }
    }
}
