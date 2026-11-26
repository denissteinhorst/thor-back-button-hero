package dev.steinhorst.backbuttonhero.capture.recorder

import android.os.SystemClock
import dev.steinhorst.backbuttonhero.capture.EchoTrim
import dev.steinhorst.backbuttonhero.capture.GameBed
import dev.steinhorst.backbuttonhero.capture.TalkGate
import dev.steinhorst.backbuttonhero.capture.VoiceBand
import dev.steinhorst.backbuttonhero.capture.VoiceLeveler
import dev.steinhorst.backbuttonhero.capture.VoicePolish
import dev.steinhorst.backbuttonhero.prefs.Prefs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The recording's audio mix: the game's captured playback with the microphone through the voice
 * chain, the push-to-talk gate and the fades. With push to talk and a duck mode the video's game
 * sound goes down while a talk is open (one depth per talk, learnt from the game before it) and comes
 * back only once the delayed voice has faded out. The voice is never altered beyond the chain, and
 * the device's own speaker is [Speaker]'s business, never this mix's.
 */
internal class AudioMixer(private val pushToTalk: Boolean, duck: String, private val micRing: MicRing, private val speaker: Speaker) {
    /** The device speaker's state as the recorder last set it. */
    interface Speaker {
        val ducked: Boolean
        val changedMs: Long
    }

    @Volatile var talking = !pushToTalk
        private set
    private var micGain = if (pushToTalk) 0f else 1f
    private var gameGain = 1f
    private val ducking = pushToTalk && duck != Prefs.DUCK_OFF
    private val bed = GameBed()
    private var bedGain = GameBed.MAX_GAIN
    private var lowered = false
    /** What the closed microphone last heard, mostly the speaker; decides how late a talk opens. */
    @Volatile private var ambient = 0f
    private val band = VoiceBand(SAMPLE_RATE)
    private val leveler = VoiceLeveler()
    private var leveling = !pushToTalk
    private val polish = VoicePolish(SAMPLE_RATE)
    /** Only with push to talk: without it the speaker stays loud and there is no pause to learn in. */
    private val trim = if (pushToTalk) EchoTrim(SAMPLE_RATE) else null
    /** The talk gate's decisions, as late as [trim] and [polish] hand the samples they belong to back. */
    private val openLine = BooleanArray(polish.delay + (trim?.delay ?: 0)) { !pushToTalk }
    private var openAt = 0
    private val gate = TalkGate(TALK_OPEN_LATE_FRAMES, TALK_CLOSE_EARLY_FRAMES)
    private val voice = FloatArray(CHUNK_FRAMES)
    private val open = BooleanArray(CHUNK_FRAMES)

    /** The game's playback as the speaker plays it, which the echo trim learns from. */
    fun reference(chunk: ShortArray, frames: Int) {
        trim?.reference(chunk, frames)
    }

    /** A push-to-talk press or release, placed on the microphone sample written last. */
    fun talk(on: Boolean) {
        talking = on
        val at = micRing.written()
        // A speaker that was audible at the microphone needs its fade and the audio path's latency
        // before it is gone; opened earlier, the leveled microphone would start with a tinny burst.
        val echo = !speaker.ducked && ambient > ECHO_LEVEL
        if (on) gate.press(at, if (echo) TALK_OPEN_AFTER_ECHO_FRAMES else TALK_OPEN_LATE_FRAMES) else gate.release(at)
    }

    /**
     * Adds the microphone to [chunk]'s [frames] stereo frames (a short supply is padded with silence)
     * and moves both levels toward their targets by at most one fade step per frame, so no switch clicks.
     */
    fun mix(chunk: ShortArray, frames: Int, mono: ShortArray, hasMic: Boolean) {
        // The device-side shaper only reaches the speaker, never the captured copy, so the video fades
        // here, and stays down until the delayed voice has faded out.
        val lower = ducking && (talking || micGain > 0f)
        if (ducking && !lower) bed.hear(chunk, frames * 2)
        // One depth per talk, from how loud the game was before it: a level that followed the game
        // while talking would pump.
        if (lower && !lowered) bedGain = bed.gain()
        lowered = lower
        val gameTarget = if (lower) bedGain else 1f
        if (!hasMic && gameGain == 1f && gameTarget == 1f) return
        val available = if (!hasMic) 0 else micRing.pull(frames, mono)
        if (available == 0 && gameGain == 1f && gameTarget == 1f) return
        var anyOpen = !pushToTalk
        for (i in 0 until available) {
            open[i] = !pushToTalk || gate.isOpen(micRing.pulledFrom + i)
            if (open[i]) anyOpen = true
        }
        band.process(mono, available, voice)
        // A closed push-to-talk microphone must not teach the leveler the room's noise.
        val closed = micGain == 0f && !anyOpen
        if (closed) ambient = maxOf(rms(voice, available), ambient * AMBIENT_DECAY_PER_CHUNK)
        // While a talk is open the speaker is down; what it plays at its normal level between talks
        // teaches the trim, once the fades and the audio path's latency are over.
        trim?.process(voice, available, micRing.pulledFrom, if (anyOpen) SPEAKER_GAIN else 1f,
            closed && !speaker.ducked && SystemClock.uptimeMillis() - speaker.changedMs > SPEAKER_SETTLE_MS)
        if (anyOpen && !leveling) leveler.restart()
        leveling = anyOpen
        if (leveling) leveler.process(voice, available)
        polish.process(voice, available)
        val micStep = 1f / TALK_FADE_FRAMES
        for (i in 0 until frames) {
            if (i < available) {
                val wasOpen = openLine[openAt]
                openLine[openAt] = open[i]
                openAt = (openAt + 1) % openLine.size
                micGain = toward(micGain, if (wasOpen) 1f else 0f, micStep)
            }
            // The same number of dB per frame: an even fade to the ear, however deep it goes.
            gameGain = if (gameGain > gameTarget) maxOf(gameTarget, gameGain * GAME_FADE_PER_FRAME)
            else minOf(gameTarget, gameGain / GAME_FADE_PER_FRAME)
            val spoken = if (i < available) voice[i] * micGain else 0f
            for (channel in 0..1) {
                val at = i * 2 + channel
                chunk[at] = VoiceLeveler.limit(chunk[at] * gameGain + spoken)
            }
        }
    }

    private fun rms(samples: FloatArray, count: Int): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / count).toFloat()
    }

    private fun toward(value: Float, target: Float, step: Float) = when {
        value < target -> minOf(target, value + step)
        value > target -> maxOf(target, value - step)
        else -> value
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHUNK_FRAMES = SAMPLE_RATE / 50
        /** Measured on the Thor: the microphone hears the speaker 23 dB quieter, 17 dB under a voice instead
         *  of 6 dB over it, and the game stays audible to the player. */
        const val SPEAKER_GAIN = 0.1f
        /** 120 dB a second: the deepest duck takes 200 ms, the usual one less. */
        private val GAME_FADE_PER_FRAME = 10.0.pow(-120.0 / 20 / SAMPLE_RATE).toFloat()
        /** The press click (plus the key's way here) is over by then. */
        private const val TALK_OPEN_LATE_FRAMES = SAMPLE_RATE * 100 / 1000
        /** Measured on the Thor: the microphone hears the speaker's fade until about 300 ms after the press. */
        private const val TALK_OPEN_AFTER_ECHO_FRAMES = SAMPLE_RATE * 300 / 1000
        /** About -48 dBFS: a speaker quieter than this at the microphone stays under the game in the video. */
        private const val ECHO_LEVEL = 130f
        /** The closed microphone's level is held and falls by about 45 dB a second. */
        private const val AMBIENT_DECAY_PER_CHUNK = 0.9f
        /** The release click can start before the key event arrives. */
        private const val TALK_CLOSE_EARLY_FRAMES = SAMPLE_RATE * 80 / 1000
        private const val TALK_FADE_FRAMES = SAMPLE_RATE * 20 / 1000
        /** The fade, the latency to the speaker and back, and the microphone's own delay, with room to spare. */
        private const val SPEAKER_SETTLE_MS = 700L
    }
}
