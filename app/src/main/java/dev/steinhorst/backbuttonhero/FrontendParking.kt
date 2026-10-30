package dev.steinhorst.backbuttonhero

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import java.util.concurrent.Executors

/**
 * Covering a frontend's bottom activity stops it, and frontends react to that (Cocoon switches its
 * top screen to a single-screen layout). Instead its task waits, still resumed, on a private display
 * owned by this process. Android destroys that display's content if this process dies, so a crash
 * never leaves a frontend stranded where nobody can see it.
 */
class FrontendParking(
    private val context: Context,
    private val run: (Array<String>) -> String? = { args -> PService.runEntryPoint(context, "TaskParker", *args) },
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {
    private data class Parked(val packageName: String, val taskId: Int, val since: Long)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var parked: Parked? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var frames: HandlerThread? = null
    // Frontends that reclaim the bottom screen right away stay unparked for this service session.
    private val abandoned = mutableSetOf<String>()
    private var busy = false
    private var closed = false

    val parkedPackage: String? get() = parked?.packageName

    /** Parks the frontend in front on both screens before something else covers it; [then] always runs. */
    fun prepareLaunch(bottom: Display, topPackage: String?, bottomPackage: String?, then: () -> Unit) {
        if (!wouldPark(topPackage, bottomPackage)) return then()
        park(bottom, requireNotNull(bottomPackage), then)
    }

    fun wouldPark(topPackage: String?, bottomPackage: String?): Boolean = !closed && !busy && parked == null &&
        ParkingPolicy.candidate(topPackage, bottomPackage, context.packageName, abandoned) != null && PService.cachedAvailable()

    /** The parked frontend started a new bottom activity over the app in front (wake, return from a game). */
    fun reclaimed(bottom: Display, bottomPackage: String?) {
        val current = parked ?: return
        if (closed || busy || bottomPackage != current.packageName) return
        if (!ParkingPolicy.mayRepark(clock(), current.since)) {
            Log.i(TAG, "frontend reclaims the bottom screen immediately; parking stopped for ${current.packageName}")
            abandoned += current.packageName
            // The frontend already replaced the parked instance; releasing the display discards it.
            forget()
            return
        }
        parked = null
        park(bottom, current.packageName) {}
    }

    /** Brings the parked frontend back in front on [target]. */
    fun restore(target: Int, then: (Boolean) -> Unit) {
        val current = parked
        if (closed || busy || current == null) return then(false)
        busy = true
        worker.execute {
            val result = run(arrayOf("restore", current.packageName, current.taskId.toString(), target.toString()))
            Log.d(TAG, "parking restore: $result")
            main.post {
                busy = false
                if (closed) return@post
                forget()
                then(result?.startsWith("OK ") == true)
            }
        }
    }

    /** Drops a parked task that no longer exists, e.g. after its app was closed or crashed. */
    fun verify() {
        val current = parked
        if (closed || busy || current == null) return
        busy = true
        worker.execute {
            val result = run(arrayOf("query", current.packageName, current.taskId.toString()))
            main.post {
                busy = false
                if (closed || parked != current) return@post
                if (result != "OK display=${display?.display?.displayId}") forget()
            }
        }
    }

    /** The parked app is being closed anyway, so its parked task may simply be discarded. */
    fun forget(packageName: String) {
        if (parked?.packageName == packageName && !busy) forget()
    }

    fun close() {
        if (closed) return
        closed = true
        val current = parked
        val owned = display
        val target = owned?.let { bottomScreen() }
        if (current == null || target == null) {
            release()
            worker.shutdown()
            return
        }
        // Best effort: if this process dies first, releasing the display discards the task instead.
        worker.execute {
            run(arrayOf("restore", current.packageName, current.taskId.toString(), target.toString()))
            main.post { release() }
        }
        worker.shutdown()
    }

    private fun park(bottom: Display, pkg: String, then: () -> Unit) {
        val target = ensureDisplay(bottom) ?: return then()
        busy = true
        worker.execute {
            val result = run(arrayOf("park", pkg, bottom.displayId.toString(), target.toString()))
            Log.d(TAG, "parking park: $result")
            main.post {
                busy = false
                if (closed) return@post
                val task = ParkingPolicy.parkedTask(result)
                if (task != null) parked = Parked(pkg, task, clock()) else if (parked == null) release()
                then()
            }
        }
    }

    private fun forget() {
        parked = null
        release()
    }

    private fun ensureDisplay(bottom: Display): Int? {
        display?.let { return it.display.displayId }
        return runCatching {
            val size = Point().also { bottom.getRealSize(it) }
            val metrics = DisplayMetrics().also { bottom.getRealMetrics(it) }
            val thread = HandlerThread("bbh-parking").apply { start() }
            frames = thread
            // Nobody sees this display; drain each frame so the parked frontend never blocks on buffers.
            val images = ImageReader.newInstance(size.x, size.y, PixelFormat.RGBA_8888, 2).apply {
                setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(thread.looper))
            }
            reader = images
            // Without PUBLIC the display is private: invisible to other apps and never a launch target.
            val created = context.getSystemService(DisplayManager::class.java)
                .createVirtualDisplay(PARKING_DISPLAY_NAME, size.x, size.y, metrics.densityDpi, images.surface, 0)
            display = created
            created.display.displayId
        }.onFailure {
            Log.w(TAG, "parking display unavailable: ${it.javaClass.simpleName}")
            release()
        }.getOrNull()
    }

    private fun bottomScreen(): Int? = context.getSystemService(DisplayManager::class.java).displays
        .filter { it.displayId != Display.DEFAULT_DISPLAY && !isParkingDisplay(it) }
        .maxByOrNull { it.displayId }?.displayId

    private fun release() {
        display?.release()
        display = null
        reader?.close()
        reader = null
        frames?.quitSafely()
        frames = null
    }

    companion object {
        private const val TAG = "FrontendParking"
        const val PARKING_DISPLAY_NAME = "Back Button Hero parking"

        /** Private displays are only ever this app's own; none is a screen the user can see. */
        fun isParkingDisplay(display: Display): Boolean = display.flags and Display.FLAG_PRIVATE != 0
    }
}
