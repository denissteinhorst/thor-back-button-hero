package dev.steinhorst.backbuttonhero

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import dev.steinhorst.backbuttonhero.core.AppSource
import dev.steinhorst.backbuttonhero.core.RunningApp
import dev.steinhorst.backbuttonhero.recents.RecentsTaskState
import dev.steinhorst.backbuttonhero.recents.SwitcherOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** After a card leaves, every later lookup (thumbnail, selection, launch) still reaches the card built for that app. */
@RunWith(RobolectricTestRunner::class)
class SwitcherCardListTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val service = Robolectric.buildService(SwitcherService::class.java).create().get()
    private val row = LinearLayout(activity)
    private val apps = listOf("dev.example.first", "dev.example.gone", "dev.example.last").map { RunningApp(it, it, null, null) }
    private val thumbs = apps.map { ImageView(activity) }
    private val cards = apps.indices.map { i ->
        LinearLayout(activity).apply { addView(FrameLayout(activity)); addView(thumbs[i]); row.addView(this) }
    }
    private var launched: String? = null
    private val overlay = SwitcherOverlay(service, object : AppSource {
        override fun appsOnDisplay(displayId: Int) = apps
        override fun forget(packageName: String) = Unit
    }, { false }, {}, { _, _ -> }, {}, { pkg, _ -> launched = pkg }, {}, {})

    init {
        activity.setContentView(FrameLayout(activity).apply { addView(row) })
        fun field(name: String, value: Any) = SwitcherOverlay::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(overlay, value)
        field("root", row.parent)
        field("shown", shownCards(apps, cards))
        field("shownDisplayId", 17)
        field("selected", 2)
    }

    private fun bitmapOf(view: ImageView) = (view.drawable as? BitmapDrawable)?.bitmap

    @Test
    fun `a late thumbnail lands on its own card after an earlier card left`() {
        overlay.reconcileTaskStates(listOf(RecentsTaskState(apps[0].packageName, 17, false), RecentsTaskState(apps[2].packageName, 17, false)))
        val late = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        overlay.updateThumbnail(apps[1].packageName, late)
        thumbs.forEach { assertNull(bitmapOf(it)) }
        overlay.updateThumbnail(apps[2].packageName, late)
        assertSame(late, bitmapOf(thumbs[2]))
        assertNull(bitmapOf(thumbs[0]))
        assertEquals(listOf(cards[0], cards[2]), (0 until row.childCount).map(row::getChildAt))
        overlay.activateSelection()
        assertEquals(apps[2].packageName, launched)
        activity.finish()
    }
}
