package dev.steinhorst.backbuttonhero

/** The four input groups an app's exception is edited by; each opens one modal of switches. */
enum class ExceptionGroup(val titleRes: Int, val iconRes: Int, val sections: List<Section>) {
    BACK(
        R.string.exceptions_group_back, R.drawable.ic_settings_back,
        listOf(Section(null, ExceptionControl.BACK)),
    ),
    SHOULDERS(
        R.string.exceptions_group_shoulders, R.drawable.ic_settings_shoulders,
        listOf(Section(null, listOf(ExceptionControl.L1, ExceptionControl.R1, ExceptionControl.L2, ExceptionControl.R2))),
    ),
    DPAD(
        R.string.exceptions_group_dpad, R.drawable.ic_settings_dpad,
        listOf(Section(null, listOf(ExceptionControl.UP, ExceptionControl.DOWN, ExceptionControl.LEFT, ExceptionControl.RIGHT))),
    ),
    STICKS(
        R.string.exceptions_group_sticks, R.drawable.ic_settings_stick,
        listOf(
            Section(R.string.stick_tab_left, stickControls("LEFT")),
            Section(R.string.stick_tab_right, stickControls("RIGHT")),
        ),
    );

    /** Controls listed together under an optional heading; the heading carries its own all-switch. */
    class Section(val titleRes: Int?, val controls: List<ExceptionControl>)

    val controls: List<ExceptionControl> get() = sections.flatMap { it.controls }

    /** Bits of this group's controls. */
    val mask: Int get() = controls.sumOf { it.bit }

    /** How many of this group's controls Back Button Hero still handles for [exception]. */
    fun allowedCount(exception: AppException): Int = controls.count { exception.allowed and it.bit != 0 }

    /** The combos of every group but Back are unreachable once Back reaches the app untouched. */
    fun editable(exception: AppException): Boolean = exception.active && (this == BACK || !exception.backNative)
}

private fun stickControls(side: String) = listOf("UP", "DOWN", "LEFT", "RIGHT", "PRESS", "DOUBLE", "TAP_HOLD")
    .map { ExceptionControl.valueOf("${side}_STICK_$it") }
