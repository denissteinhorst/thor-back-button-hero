package dev.steinhorst.backbuttonhero

import dev.steinhorst.backbuttonhero.support.Golden
import dev.steinhorst.backbuttonhero.support.ViewTree
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class SettingsActivitySnapshotTest {

    private fun snapshot(name: String) {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val root = activity.findViewById<android.view.View>(android.R.id.content)
        Golden.verify(name, ViewTree.describe(root))
    }

    @Config(qualifiers = "en-xxhdpi")
    @Test
    fun `main page in English, light theme`() = snapshot("settings_main_en_light")

    @Config(qualifiers = "en-night-xxhdpi")
    @Test
    fun `main page in English, dark theme`() = snapshot("settings_main_en_dark")

    @Config(qualifiers = "de-xxhdpi")
    @Test
    fun `main page in German, light theme`() = snapshot("settings_main_de_light")
}
