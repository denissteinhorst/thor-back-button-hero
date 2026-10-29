package dev.steinhorst.backbuttonhero

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecentsBadgesTest {
    @Test
    fun `OEM margins survive view scope and only the lock has internal padding`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val constructor = RecentsBadges::class.java.declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
        val badges = constructor.newInstance(context, ColorDrawable(Color.BLACK), ColorDrawable(Color.GRAY),
            ColorDrawable(Color.BLACK), 65, 7, 69, 23) as RecentsBadges
        val (lock, display) = badges.addTo(FrameLayout(context))
        val lockLayout = lock.layoutParams as FrameLayout.LayoutParams
        val displayLayout = display.layoutParams as FrameLayout.LayoutParams
        assertEquals(69, lockLayout.topMargin)
        assertEquals(69, displayLayout.topMargin)
        assertEquals(23, lockLayout.rightMargin)
        assertEquals(103, displayLayout.rightMargin)
        assertEquals(65, lockLayout.width)
        assertEquals(65, displayLayout.height)
        assertEquals(Gravity.TOP or Gravity.END, lockLayout.gravity)
        assertEquals(7, lock.paddingTop)
        assertEquals(0, display.paddingTop)
        assertEquals(View.INVISIBLE, lock.visibility)
        badges.update(lock to display, RecentsTaskState("dev.example.app", 17, true))
        assertEquals(View.VISIBLE, lock.visibility)
        assertEquals(2, display.drawable.level)
        badges.update(lock to display, null)
        assertEquals(View.INVISIBLE, display.visibility)
    }
}
