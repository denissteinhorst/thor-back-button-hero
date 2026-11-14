package dev.steinhorst.backbuttonhero.support

import dev.steinhorst.backbuttonhero.BackGestureButton
import dev.steinhorst.backbuttonhero.DpadDirection
import dev.steinhorst.backbuttonhero.Prefs
import dev.steinhorst.backbuttonhero.ShoulderButton

/**
 * Everything starts unassigned now. Tests that exercise a row, a dialog or a gesture rather than
 * the defaults themselves put the settings an update still carries (see Prefs' migrations) in
 * place first, so they keep their meaning and their golden files.
 */
fun Prefs.useEarlierDefaults() {
    DpadDirection.entries.forEach { setShoulderAction(it, it.earlierDefault) }
    BackGestureButton.entries.forEach { setShoulderAction(it, it.earlierDefault) }
    setShoulderAction(ShoulderButton.L1, Prefs.SHOULDER_FLICK_LEFT)
    setShoulderAction(ShoulderButton.R1, Prefs.SHOULDER_FLICK_RIGHT)
    setShoulderAction(ShoulderButton.L2, Prefs.SHOULDER_SCREENSHOT)
    setShoulderAction(ShoulderButton.R2, Prefs.SHOULDER_CONTROLLER_FOCUS)
    toggleButtons = Prefs.TOGGLE_L2_R2
}
