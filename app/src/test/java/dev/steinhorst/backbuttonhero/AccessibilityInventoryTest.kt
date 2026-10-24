package dev.steinhorst.backbuttonhero

import dev.steinhorst.backbuttonhero.support.Golden
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-xxhdpi")
class AccessibilityInventoryTest {

    @Test
    fun `every clickable row reports contentDescription, min height in dp, and focusability`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val root = activity.findViewById<android.view.View>(android.R.id.content)
        val report = StringBuilder()
        collectClickableRows(root, activity.resources.displayMetrics.density, report)
        Golden.verify("a11y_inventory", report.toString())
    }

    private fun collectClickableRows(view: android.view.View, density: Float, out: StringBuilder) {
        if (view.isClickable) {
            val minHeightDp = (view.minimumHeight / density).toInt()
            out.append(
                "clickable=${view.javaClass.simpleName} " +
                    "minHeightDp=$minHeightDp " +
                    "focusable=${view.isFocusable} " +
                    "contentDescription=${view.contentDescription ?: "(none)"}\n"
            )
        }
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) collectClickableRows(view.getChildAt(i), density, out)
        }
    }
}
