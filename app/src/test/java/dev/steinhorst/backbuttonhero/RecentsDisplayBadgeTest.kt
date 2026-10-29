package dev.steinhorst.backbuttonhero

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LevelListDrawable
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecentsDisplayBadgeTest {
    @Test
    fun `same card changes its screen indicator after task moves in either direction`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val display = LevelListDrawable().apply {
            addLevel(1, 1, ColorDrawable(Color.WHITE))
            addLevel(2, 2, ColorDrawable(Color.BLACK))
        }
        val badges = RecentsBadges::class.java.declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
            .newInstance(activity, ColorDrawable(Color.WHITE), ColorDrawable(Color.BLACK), display, 24, 0, 0, 0) as RecentsBadges
        val views = badges.addTo(FrameLayout(activity))
        val task = RecentsTaskState("dev.example.app", 17, false, 10, 100)
        badges.update(views, task)
        assertEquals(2, views.second.drawable.level)
        badges.update(views, task.copy(displayId = 0))
        assertEquals(1, views.second.drawable.level)
        badges.update(views, task.copy(displayId = 31))
        assertEquals(2, views.second.drawable.level)
        activity.finish()
    }
}
