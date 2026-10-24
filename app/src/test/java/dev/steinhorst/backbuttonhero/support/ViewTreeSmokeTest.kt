package dev.steinhorst.backbuttonhero.support

import android.widget.TextView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ViewTreeSmokeTest {
    @Test
    fun `describe renders class name and text for a simple TextView`() {
        val view = TextView(RuntimeEnvironment.getApplication()).apply { text = "hello" }
        val described = ViewTree.describe(view)
        assertTrue(described.contains("TextView"))
        assertTrue(described.contains("text=\"hello\""))
    }
}
