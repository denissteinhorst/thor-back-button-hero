package dev.steinhorst.backbuttonhero.macro

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.prefs.Prefs
import dev.steinhorst.backbuttonhero.settings.dp
import dev.steinhorst.backbuttonhero.settings.themedColor

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
