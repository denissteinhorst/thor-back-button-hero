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
 * - `snapshot`: copies every setting aside, but never over an existing copy, so a run that died
 *   halfway cannot overwrite the owner's real settings with test values.
 * - `restore`: puts the copy back exactly and drops it.
 * - `reset`: clears everything and lets [Prefs] apply a fresh install's defaults.
 * - `put` with `key`, `type` (string, int, long, bool, float) and `value`; `remove` with `key`.
 * - `probe` with `value` start (and `display`) or stop: [DeviceTestKeyActivity], a stand-in game.
 * - `media` with `value` start or stop: a playing media session that logs each media key it gets.
 *   It plays silence, because Android hands media keys to the session whose app plays audio; without
 *   it they would reach whichever player the owner used last.
 */
class DeviceTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val copy = app.getSharedPreferences(SNAPSHOT, Context.MODE_PRIVATE)
        resultData = runCatching {
            when (val op = intent.getStringExtra("op")) {
                "snapshot" -> if (copy.all.isNotEmpty()) "KEPT" else { copyAll(prefs, copy); "OK ${copy.all.size}" }
                "restore" -> if (copy.all.isEmpty()) "NONE" else {
                    copyAll(copy, prefs)
                    copy.edit().clear().apply()
                    "OK ${prefs.all.size}"
                }
                "reset" -> {
                    prefs.edit().clear().apply()
                    Prefs(app)
                    "OK"
                }
                "put" -> put(prefs.edit(), intent).let { if (it.commit()) "OK" else "FAIL" }
                "remove" -> if (prefs.edit().remove(intent.getStringExtra("key")).commit()) "OK" else "FAIL"
                "media" -> media(app, intent.getStringExtra("value") == "start")
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

    /** One edit replaces [to] with [from], so a listener never sees a half-copied state. The in-memory
     *  values change at once; the file follows in the background, well before the next step reads it. */
    private fun copyAll(from: SharedPreferences, to: SharedPreferences) {
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
        editor.apply()
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

        /** Lives with the process; the service keeps it alive while the accessibility service runs. */
        var session: MediaSession? = null
        var silence: AudioTrack? = null
    }
}
