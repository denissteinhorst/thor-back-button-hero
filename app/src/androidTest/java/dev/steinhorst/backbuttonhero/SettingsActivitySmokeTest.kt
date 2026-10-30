package dev.steinhorst.backbuttonhero

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import org.hamcrest.Matchers.equalTo
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
                assertTrue(text.contains(activity.getString(R.string.nav_basic_hint)))
                assertTrue(text.contains(activity.getString(R.string.settings_preset)))
                assertTrue(text.contains(activity.getString(R.string.settings_unbind)))
            }
            SettingsSection.entries.forEach { section ->
                var compact = false
                scenario.onActivity { activity ->
                    compact = activity.findViewById<android.view.View>(android.R.id.content)
                        .findViewWithTag<android.view.View>("settings_nav_${section.name}") == null
                }
                if (compact) onView(withTagValue(equalTo<Any>("settings_menu"))).perform(click())
                onView(withTagValue(equalTo<Any>("settings_nav_${section.name}"))).perform(scrollTo(), click())
                var hasFocusOptions = false
                scenario.onActivity { activity ->
                    val page = activity.findViewById<android.view.View>(android.R.id.content)
                        .findViewWithTag<android.view.View>("settings_page_${section.name}")
                    val pageText = StringBuilder()
                    collectText(page, pageText)
                    assertTrue(pageText.contains(activity.getString(section.hintRes)))
                    hasFocusOptions = page.findViewWithTag<android.view.View>("settings_focus_options") != null
                    val actions = page.findViewWithTag<android.view.ViewGroup>("settings_page_actions")
                    if (section == SettingsSection.HELP || section == SettingsSection.EXCEPTIONS) {
                        assertTrue(actions == null)
                    } else {
                        assertTrue(actions.childCount == 2)
                        assertTrue(kotlin.math.abs(actions.getChildAt(0).width - actions.getChildAt(1).width) <= 1)
                        for (i in 0 until actions.childCount) assertTrue(actions.getChildAt(i).height >= activity.dp(48))
                    }
                    val topBar = activity.findViewById<android.view.View>(android.R.id.content)
                        .findViewWithTag<android.view.View>("settings_top_bar")
                    assertTrue(topBar.findViewWithTag<android.view.View>("settings_page_actions") == null)
                }
                if (section == SettingsSection.EXCEPTIONS) {
                    onView(withText(R.string.exceptions_add_app)).perform(scrollTo(), click())
                    onView(withTagValue(equalTo<Any>("exceptions_search"))).inRoot(isDialog())
                        .check(matches(isDisplayed())).perform(replaceText("dev.steinhorst.backbuttonhero"))
                    onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
                }
                if (hasFocusOptions) {
                    scenario.onActivity { activity ->
                        val group = activity.findViewById<android.view.View>(android.R.id.content)
                            .findViewWithTag<android.view.View>("settings_focus_options")
                        val target = group.findViewWithTag<android.view.View>("settings_focus_target")
                        assertTrue(target.height >= activity.dp(48))
                        target.requestRectangleOnScreen(android.graphics.Rect(0, 0, target.width, target.height), true)
                        target.performClick()
                    }
                    onView(withText(R.string.focus_target_dialog_title)).inRoot(isDialog()).check(matches(isDisplayed()))
                    onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click())
                    scenario.onActivity { activity ->
                        activity.findViewById<android.view.View>(android.R.id.content)
                            .findViewWithTag<android.view.View>("settings_focus_options")
                            .findViewWithTag<android.view.View>("settings_focus_indicator").performClick()
                    }
                    onView(withText(R.string.focus_position_title)).inRoot(isDialog()).check(matches(isDisplayed()))
                    onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
                }
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<android.view.View>(android.R.id.content)
                    .findViewWithTag<android.view.View>("settings_page_HELP") != null)
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
