package dev.steinhorst.backbuttonhero

import android.graphics.Bitmap
import android.util.LruCache

/** Immutable snapshots may still be displayed after eviction; their lifetime belongs to the GC. */
class RecentsThumbnailCache(maxBytes: Int = 12 * 1024 * 1024) {
    data class Key(val taskId: Int, val lastActiveTime: Long, val width: Int, val displayId: Int = -1)

    private val images = object : LruCache<Key, Bitmap>(maxBytes) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.allocationByteCount
    }

    fun get(key: Key): Bitmap? = images.get(key)
    fun put(key: Key, bitmap: Bitmap) { images.put(key, bitmap) }
}
