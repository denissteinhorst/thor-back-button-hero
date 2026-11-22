package dev.steinhorst.backbuttonhero

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log
import android.view.KeyEvent

/**
 * Debug builds only. Lets the on-device test suite (`tools/devicetest`) change the settings inside
 * the app's own process, so the running service sees each change at once through its preference
 * listeners, exactly as if the settings screen had made it. The result data answers every call.
 *
 * `adb shell am broadcast -a dev.steinhorst.backbuttonhero.DEVICE_TEST --es op <op> ...`
 * - `snapshot` with `run`: copies every setting aside and answers `OK <count>`. An existing copy is
 *   never overwritten (`KEPT <run>` names the run that made it), so a run that died halfway cannot
 *   replace the owner's real settings with test values. A separate marker records that the copy is
 *   complete, which tells a valid empty copy apart from none at all.
 * - `restore` with `run`: writes the copy back to disk and answers `OK <count>`, `NONE` without a
 *   complete copy, or `OTHER <run>` when the copy belongs to a different run (never adopted). The copy stays until `drop`, so an interrupted or failed restore can simply be repeated;
 *   the suite reads the settings file back before it drops anything.
 * - `status`: only reads: `KEPT <run> <count>` for a complete copy, `KEPT unmarked` for one without
 *   the marker, `NONE` without a copy. Unlike `snapshot` it never takes a copy, so checking that the
 *   copy survived an install cannot replace a lost one with test values.
 * - `drop`: deletes the copy once the suite has verified the restore.
 * - `reset`: clears everything and lets [Prefs] apply a fresh install's defaults.
 * - `put` with `key`, `type` (string, int, long, bool, float) and `value`; `remove` with `key`.
 * - `probe` with `value` start (and `display`) or stop: [DeviceTestKeyActivity], a stand-in game.
 * - `media` with `value` start or stop: a playing media session that logs each media key it gets.
 *   It plays silence, because Android hands media keys to the session whose app plays audio; without
 *   it they would reach whichever player the owner used last.
 * - `gyro` with `value` "x,y,z" (rad/s around the sensor's axes) or off: replaces the gyroscope's
 *   readings while the gyro hotkey is on, since a test cannot turn the device.
 */
class DeviceTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val copy = app.getSharedPreferences(SNAPSHOT, Context.MODE_PRIVATE)
        resultData = runCatching {
            when (val op = intent.getStringExtra("op")) {
                "snapshot" -> {
                    val meta = app.getSharedPreferences(SNAPSHOT_META, Context.MODE_PRIVATE)
                    if (meta.getBoolean(COMPLETE, false)) {
                        "KEPT ${meta.getString(RUN, "")}"
                    } else if (copy.all.isNotEmpty()) {
                        // A copy without the marker: an older build's or a half-finished one. Keep it
                        // for manual recovery instead of overwriting it.
                        "KEPT unmarked"
                    } else {
                        val run = requireNotNull(intent.getStringExtra("run")) { "run missing" }
                        when {
                            !copyAll(prefs, copy) -> "FAIL snapshot write"
                            !meta.edit().putString(RUN, run).putBoolean(COMPLETE, true).commit() -> "FAIL marker write"
                            else -> "OK ${copy.all.size}"
                        }
                    }
                }
                "restore" -> {
                    val meta = app.getSharedPreferences(SNAPSHOT_META, Context.MODE_PRIVATE)
                    val run = intent.getStringExtra("run")
                    when {
                        !meta.getBoolean(COMPLETE, false) -> "NONE"
                        run != null && run != meta.getString(RUN, null) -> "OTHER ${meta.getString(RUN, "")}"
                        !copyAll(copy, prefs) -> "FAIL restore write"
                        else -> "OK ${prefs.all.size}"
                    }
                }
                "status" -> {
                    val meta = app.getSharedPreferences(SNAPSHOT_META, Context.MODE_PRIVATE)
                    when {
                        meta.getBoolean(COMPLETE, false) -> "KEPT ${meta.getString(RUN, "")} ${copy.all.size}"
                        copy.all.isNotEmpty() -> "KEPT unmarked"
                        else -> "NONE"
                    }
                }
                "drop" -> {
                    val meta = app.getSharedPreferences(SNAPSHOT_META, Context.MODE_PRIVATE)
                    // The marker goes first: a half-finished drop then reads as no copy, never as a
                    // complete copy with missing settings.
                    if (meta.edit().clear().commit() && copy.edit().clear().commit()) "OK" else "FAIL drop write"
                }
                "reset" -> {
                    prefs.edit().clear().apply()
                    Prefs(app)
                    "OK"
                }
                "put" -> put(prefs.edit(), intent).let { if (it.commit()) "OK" else "FAIL" }
                "remove" -> if (prefs.edit().remove(intent.getStringExtra("key")).commit()) "OK" else "FAIL"
                "media" -> media(app, intent.getStringExtra("value") == "start")
                "gyro" -> {
                    val rates = intent.getStringExtra("value")?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }
                        ?.takeIf { it.size == 3 }?.toFloatArray()
                    SwitcherService.gyroOverride?.let { it(rates); "OK" } ?: "ERR service not connected"
                }
                "probe" -> {
                    if (intent.getStringExtra("value") == "start") {
                        val display = intent.getStringExtra("display")?.toIntOrNull() ?: 0
                        app.startActivity(
                            Intent(app, DeviceTestKeyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            android.app.ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle(),
                        )
                    } else {
                        DeviceTestKeyActivity.finishCurrent()
                    }
                    "OK"
                }
                else -> "ERR unknown op $op"
            }
        }.getOrElse { "ERR ${it.javaClass.simpleName}: ${it.message}" }
    }

    private fun put(editor: SharedPreferences.Editor, intent: Intent): SharedPreferences.Editor {
        val key = requireNotNull(intent.getStringExtra("key")) { "key missing" }
        val value = intent.getStringExtra("value").orEmpty()
        return when (intent.getStringExtra("type") ?: "string") {
            "int" -> editor.putInt(key, value.toInt())
            "long" -> editor.putLong(key, value.toLong())
            "bool" -> editor.putBoolean(key, value.toBooleanStrict())
            "float" -> editor.putFloat(key, value.toFloat())
            else -> editor.putString(key, value)
        }
    }

    /** One edit replaces [to] with [from], so a listener never sees a half-copied state. Committed
     *  synchronously: the result says whether the file on disk holds the copy. */
    private fun copyAll(from: SharedPreferences, to: SharedPreferences): Boolean {
        val editor = to.edit().clear()
        from.all.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        return editor.commit()
    }

    private fun media(context: Context, start: Boolean): String {
        session?.release()
        session = null
        silence?.release()
        silence = null
        if (!start) return "OK"
        silence = silentTrack().apply { play() }
        session = MediaSession(context, TAG).apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (event?.action == KeyEvent.ACTION_DOWN) Log.i(TAG, "MEDIA_KEY ${KeyEvent.keyCodeToString(event.keyCode)}")
                    return true
                }
            })
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .build(),
            )
            isActive = true
        }
        return "OK"
    }

    /** One second of zeros, looped forever: audible to the media framework, not to anyone else. */
    private fun silentTrack(): AudioTrack {
        val rate = 8000
        val bytes = rate * 2
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(bytes)
            .build()
            .apply {
                write(ByteArray(bytes), 0, bytes)
                setLoopPoints(0, rate, -1)
            }
    }

    private companion object {
        const val TAG = "DeviceTest"
        const val PREFS = "back_button_hero"
        const val SNAPSHOT = "device_test_snapshot"
        const val SNAPSHOT_META = "device_test_snapshot_meta"
        const val COMPLETE = "complete"
        const val RUN = "run"

        /** Lives with the process; the service keeps it alive while the accessibility service runs. */
        var session: MediaSession? = null
        var silence: AudioTrack? = null
    }
}
