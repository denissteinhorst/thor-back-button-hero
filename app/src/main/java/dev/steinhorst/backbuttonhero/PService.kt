package dev.steinhorst.backbuttonhero

import android.annotation.SuppressLint
import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Bridge to AYN's own root daemon, "pservice" (uid 0, started at boot), which backs the firmware's
 * Settings -> Thor -> "Run Script as Root". It registers the binder service "PServerBinder" and runs
 * each command through popen as root. A normal app can reach it only because the Thor ships with
 * SELinux permissive (AYN's "Force SELinux" toggle off); with it on, or on any other device, [isAvailable]
 * stays false and callers fall back to what an unprivileged app can do.
 *
 * Transaction contract: code 0, data = string array [command, "0"], no interface token,
 * reply = byte array holding the command's stdout.
 */
object PService {
    private const val TAG = "PService"
    private const val SERVICE = "PServerBinder"
    private const val CODE_EXEC = 0
    private const val RETRY_MS = 10_000L

    @Volatile private var cached: IBinder? = null
    @Volatile private var checked = false
    @Volatile private var lastFail = -RETRY_MS

    // Overlapping transactions came back empty in practice, so one command at a time.
    private val execLock = Any()
    private val scriptSeq = AtomicLong()

    /** Non-blocking: true once a probe succeeded. */
    fun cachedAvailable(): Boolean = checked

    /** Probes off the main thread; [then] runs on that thread afterwards, whatever the outcome. */
    fun probeAsync(then: (() -> Unit)? = null) {
        if (checked) { then?.invoke(); return }
        Thread { isAvailable(); then?.invoke() }.apply { isDaemon = true }.start()
    }

    /** True when pservice answers and runs our commands as uid 0. Only a success is cached: pservice
     *  may not be up yet at boot, and one early miss must not disable root until the next restart. */
    fun isAvailable(): Boolean {
        if (checked) return true
        synchronized(this) {
            val now = SystemClock.uptimeMillis()
            if (!checked && now - lastFail > RETRY_MS) {
                if (exec("id")?.contains("uid=0") == true) checked = true else lastFail = now
                Log.d(TAG, "available=$checked")
            }
        }
        return checked
    }

    /** Runs [cmd] as root; returns stdout, or null when pservice can't be reached. Blocking. */
    fun exec(cmd: String): String? = synchronized(execLock) {
        val binder = service() ?: return@synchronized null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeStringArray(arrayOf(cmd, "0"))
            if (!binder.transact(CODE_EXEC, data, reply, 0)) return@synchronized null
            reply.createByteArray()?.let { String(it) } ?: ""
        } catch (t: Throwable) {
            Log.w(TAG, "transact: ${t.javaClass.simpleName}")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    @SuppressLint("PrivateApi") // ServiceManager is hidden API; it is the only way to a vendor binder
    private fun service(): IBinder? {
        cached?.let { if (it.isBinderAlive) return it }
        return try {
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, SERVICE)
                .let { it as? IBinder }
                .also { cached = it }
        } catch (t: Throwable) {
            Log.w(TAG, "getService: ${t.javaClass.simpleName}")
            null
        }
    }

    /** `am force-stop` for [pkg]. */
    fun forceStop(pkg: String): Boolean {
        if (!Shell.isPkg(pkg)) return false
        return exec("am force-stop ${Shell.q(pkg)}") != null
    }

    /**
     * Runs this APK's [className] (an object with a `main`) in a bare app_process VM as root and
     * returns its "OK ..." / "ERR ..." line. pservice hands back only the first output line and drops
     * long commands (~300+ chars), so the real command goes into a small script and pservice runs
     * `sh <script>`; one script per call so concurrent callers can't overwrite each other.
     */
    fun runEntryPoint(context: Context, className: String, vararg args: String): String? {
        val argv = args.joinToString(" ") { Shell.q(it) }
        val script = File(context.cacheDir, "pservice_${scriptSeq.incrementAndGet()}.sh")
        return try {
            script.writeText(
                "CLASSPATH=${Shell.q(context.applicationInfo.sourceDir)} app_process /system/bin " +
                    "${context.packageName}.$className $argv 2>&1 | grep -m1 -E '^(OK|ERR)'\n"
            )
            exec("sh ${Shell.q(script.absolutePath)}")?.trim()
        } catch (e: Exception) {
            Log.w(TAG, "runEntryPoint: ${e.javaClass.simpleName}")
            null
        } finally {
            script.delete()
        }
    }
}
