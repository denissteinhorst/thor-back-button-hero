package dev.steinhorst.backbuttonhero

import android.view.KeyEvent

/** Keep each DOWN/UP pair on its original side of a runtime interception change. */
class KeyInterceptionGate {
    private data class Press(val generation: Int, val consumed: Boolean)
    private val presses = mutableMapOf<Pair<Int, Int>, Press>()
    private var generation = 0

    fun changed() { generation++ }
    fun clear() { presses.clear(); generation++ }

    fun handle(event: KeyEvent, enabled: Boolean, intercept: (KeyEvent) -> Boolean): Boolean {
        val key = event.deviceId to event.keyCode
        val press = presses[key]
        val consumed = if (press != null && press.generation != generation) {
            press.consumed
        } else {
            enabled && intercept(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && press == null) {
            presses[key] = Press(generation, consumed)
        } else if (event.action == KeyEvent.ACTION_UP) {
            presses.remove(key)
        }
        return consumed
    }
}
