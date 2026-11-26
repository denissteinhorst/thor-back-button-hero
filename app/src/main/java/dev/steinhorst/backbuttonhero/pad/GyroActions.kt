package dev.steinhorst.backbuttonhero.pad

import android.view.KeyEvent
import dev.steinhorst.backbuttonhero.R
import dev.steinhorst.backbuttonhero.input.ComboButton
import dev.steinhorst.backbuttonhero.overlay.NoticeOverlay
import dev.steinhorst.backbuttonhero.prefs.Prefs

/**
 * The gyro actions: on/off with a button's setup, calibration, and the optional hold key that lets
 * the gyro steer only while it is held. Each says what happened in a notice.
 */
class GyroActions(
    private val gyro: () -> GyroControl,
    private val prefs: Prefs,
    private val notice: NoticeOverlay,
    private val helperActive: () -> Boolean,
    private val modifierDown: () -> Boolean,
) {
    /** The devices holding the gyro's hold key, while it has one. */
    private val holdDevices = mutableSetOf<Int>()

    /**
     * With holding chosen, the gyro steers only while its key is held. The key still reaches the game
     * unless the setup keeps it back; with Back held it stays the button of its Back combination.
     */
    fun handleHold(event: KeyEvent): Boolean {
        val gyro = gyro()
        val key = gyro.settings.holdKeyInUse?.takeIf { gyro.on } ?: return false
        if (event.keyCode != key) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (holdDevices.isEmpty() && modifierDown()) return false
                if (holdDevices.add(event.deviceId) && holdDevices.size == 1) gyro.setHeld(true)
            }
            KeyEvent.ACTION_UP -> {
                if (!holdDevices.remove(event.deviceId)) return false
                if (holdDevices.isEmpty()) gyro.setHeld(false)
            }
        }
        return !gyro.settings.holdPasses
    }

    /** Turns the gyro on with [button]'s setup, or off again; a notice says which. */
    fun toggle(button: ComboButton) {
        val gyro = gyro()
        if (gyro.on) {
            stop()
            return notice.show(R.string.gyro_off)
        }
        if (!gyro.available) return notice.show(R.string.gyro_no_sensor)
        if (!helperActive()) return notice.show(R.string.gyro_helper_missing)
        holdDevices.clear()
        gyro.calibration = prefs.gyroCalibration
        gyro.start(button, prefs.gyro(button))
        notice.show(R.string.gyro_on)
    }

    /** Calibration measures rest independently of the posture used to aim. */
    fun calibrate() {
        val gyro = gyro()
        if (!gyro.available) return notice.show(R.string.gyro_no_sensor)
        notice.show(R.string.gyro_calibration_started)
        gyro.calibrate { result ->
            if (result != null) prefs.gyroCalibration = result
            notice.show(if (result != null) R.string.gyro_calibrated else R.string.gyro_calibration_failed)
        }
    }

    fun stop() {
        holdDevices.clear()
        gyro().stop()
    }

    /** Every held key is forgotten at once, so the gyro must not keep steering for one. */
    fun releaseHold() {
        holdDevices.clear()
        gyro().setHeld(false)
    }
}
