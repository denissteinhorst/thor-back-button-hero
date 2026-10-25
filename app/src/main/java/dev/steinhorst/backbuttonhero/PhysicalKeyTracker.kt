package dev.steinhorst.backbuttonhero

/**
 * Collapses duplicate Android input devices that report the same physical key press.
 * The Thor can expose its built-in controller twice, with both devices emitting an otherwise
 * identical DOWN/UP pair. A key is therefore considered newly pressed only when the first device
 * reports DOWN, and remains pressed until every reporting device has sent UP.
 */
class PhysicalKeyTracker {
    private val devicesByKey = mutableMapOf<Int, MutableSet<Int>>()

    /** True only for the first device reporting this key as down. */
    fun down(keyCode: Int, deviceId: Int): Boolean {
        val devices = devicesByKey.getOrPut(keyCode) { mutableSetOf() }
        val first = devices.isEmpty()
        devices += deviceId
        return first
    }

    /** True when this UP belongs to a press that this tracker has seen. */
    fun up(keyCode: Int, deviceId: Int): Boolean {
        val devices = devicesByKey[keyCode] ?: return false
        if (!devices.remove(deviceId)) return false
        if (devices.isEmpty()) devicesByKey.remove(keyCode)
        return true
    }

    fun isDown(keyCode: Int): Boolean = devicesByKey[keyCode]?.isNotEmpty() == true

    fun clear() = devicesByKey.clear()
}
