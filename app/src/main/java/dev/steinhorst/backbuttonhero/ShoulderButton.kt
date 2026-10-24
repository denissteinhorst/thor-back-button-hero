package dev.steinhorst.backbuttonhero

import android.view.KeyEvent

/** The four shoulder buttons that can be combined with holding Back to run a chosen action. */
enum class ShoulderButton(val prefKey: String, val keyCode: Int) {
    L1("l1", KeyEvent.KEYCODE_BUTTON_L1),
    L2("l2", KeyEvent.KEYCODE_BUTTON_L2),
    R1("r1", KeyEvent.KEYCODE_BUTTON_R1),
    R2("r2", KeyEvent.KEYCODE_BUTTON_R2),
}
