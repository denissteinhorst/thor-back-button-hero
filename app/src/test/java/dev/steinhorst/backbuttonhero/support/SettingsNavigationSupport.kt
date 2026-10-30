package dev.steinhorst.backbuttonhero.support

import android.view.View
import dev.steinhorst.backbuttonhero.SettingsActivity
import dev.steinhorst.backbuttonhero.SettingsSection
import org.robolectric.shadows.ShadowDialog

fun selectSettingsPage(activity: SettingsActivity, section: SettingsSection) {
    val root = activity.findViewById<View>(android.R.id.content)
    val tag = "settings_nav_${section.name}"
    val button = root.findViewWithTag<View>(tag) ?: run {
        root.findViewWithTag<View>("settings_menu").performClick()
        ShadowDialog.getLatestDialog()!!.window!!.decorView.findViewWithTag<View>(tag)
    }
    button.performClick()
}
