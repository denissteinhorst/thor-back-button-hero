package dev.steinhorst.backbuttonhero

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves a screenshot of one display to Pictures/Screenshots. The system's own screenshot action can
 * only capture the default (top) display; the bottom one is captured by the service itself and ends
 * up here. Files the app creates through MediaStore need no storage permission.
 */
object ScreenshotSaver {

    /** Writes [bitmap] as `Screenshot_<time>_<suffix>.png`; returns whether it was stored. */
    fun save(context: Context, bitmap: Bitmap, suffix: String): Boolean {
        val resolver = context.contentResolver
        val name = "Screenshot_${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}_$suffix.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        val written = runCatching {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } == true
        }.getOrDefault(false)
        if (!written) {
            resolver.delete(uri, null, null)
            return false
        }
        // The file stays hidden from gallery apps until the pending flag is cleared.
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return true
    }
}
