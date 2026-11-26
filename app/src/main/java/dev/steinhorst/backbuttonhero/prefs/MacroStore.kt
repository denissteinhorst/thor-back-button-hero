package dev.steinhorst.backbuttonhero.prefs

import android.content.SharedPreferences
import dev.steinhorst.backbuttonhero.input.ComboButton
import dev.steinhorst.backbuttonhero.macro.Macro
import dev.steinhorst.backbuttonhero.macro.SavedMacro

/**
 * The macro library and the hotkeys' references into it, over [Prefs]' own file. [Prefs] stays the
 * facade and the owner of whole-settings transactions ([Prefs.applySettings] writes the library too).
 */
internal class MacroStore(private val sp: SharedPreferences) {
    var library: List<SavedMacro>
        get() = SavedMacro.decode(sp.getString("macro_library", null))
        set(v) = sp.edit().putString("macro_library", SavedMacro.encode(v)).apply()

    fun saved(button: ComboButton): SavedMacro? =
        sp.getString("${button.prefKey}_macro", null)?.let { id -> library.firstOrNull { it.id == id } }

    fun setRef(button: ComboButton, id: String?) {
        sp.edit().apply { if (id == null) remove("${button.prefKey}_macro") else putString("${button.prefKey}_macro", id) }.apply()
    }

    fun save(id: String?, name: String, macro: Macro): SavedMacro {
        val library = library
        val existing = library.any { it.id == id }
        val newId = if (existing) checkNotNull(id) else SavedMacro.nextId(library)
        val saved = SavedMacro(newId, SavedMacro.cleanName(name, newId), macro)
        this.library = if (existing) library.map { if (it.id == newId) saved else it } else library + saved
        return saved
    }

    fun rename(id: String, name: String) {
        library = library.map { if (it.id == id) it.copy(name = SavedMacro.cleanName(name, it.name)) else it }
    }

    fun users(id: String): List<ComboButton> = Prefs.ALL_MACRO_BUTTONS.filter { sp.getString("${it.prefKey}_macro", null) == id }

    fun delete(id: String) {
        val edit = sp.edit().putString("macro_library", SavedMacro.encode(library.filterNot { it.id == id }))
        users(id).forEach { edit.remove("${it.prefKey}_macro") }
        edit.apply()
    }
}
