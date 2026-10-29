package dev.steinhorst.backbuttonhero

import android.graphics.Bitmap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecentsThumbnailCacheTest {
    @Test
    fun `eviction does not recycle a bitmap that a card can still display`() {
        val cache = RecentsThumbnailCache(64)
        val first = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val a = RecentsThumbnailCache.Key(10, 100, 721, 0)
        val b = RecentsThumbnailCache.Key(11, 200, 721, 17)
        cache.put(a, first)
        cache.put(b, second)
        assertNull(cache.get(a))
        assertFalse(first.isRecycled)
        assertSame(second, cache.get(b))
    }

    @Test
    fun `new activity time display and output size cannot reuse an old preview`() {
        val cache = RecentsThumbnailCache()
        val key = RecentsThumbnailCache.Key(10, 100, 721, 17)
        val image = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        cache.put(key, image)
        assertSame(image, cache.get(key))
        assertNull(cache.get(key.copy(lastActiveTime = 200)))
        assertNull(cache.get(key.copy(displayId = 0)))
        assertNull(cache.get(key.copy(width = 600)))
    }
}
