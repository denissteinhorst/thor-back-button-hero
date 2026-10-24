package dev.steinhorst.backbuttonhero

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsActivitySmokeTest {

    @Test
    fun settingsActivityLaunchesAndShowsCoreSections() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                val allText = StringBuilder()
                collectText(content, allText)
                val text = allText.toString()
                assertTrue(text.contains(activity.getString(R.string.single_title)))
                assertTrue(text.contains(activity.getString(R.string.shoulder_title)))
                assertTrue(text.contains(activity.getString(R.string.help_debug_title)))
            }
        }
    }

    private fun collectText(view: android.view.View, out: StringBuilder) {
        if (view is android.widget.TextView) out.append(view.text).append('\n')
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) collectText(view.getChildAt(i), out)
        }
    }
}
