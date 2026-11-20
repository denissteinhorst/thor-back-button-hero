package dev.steinhorst.backbuttonhero

/** One column of the per-app exception matrix; the ordinal is the bit stored in [AppException.allowed]. */
enum class ExceptionControl {
    SINGLE, DOUBLE, TAP_HOLD, HOLD, L1, R1, L2, R2, UP, DOWN, LEFT, RIGHT,
    // Per stick: its four directions, then its three click gestures.
    LEFT_STICK_UP, LEFT_STICK_DOWN, LEFT_STICK_LEFT, LEFT_STICK_RIGHT,
    LEFT_STICK_PRESS, LEFT_STICK_DOUBLE, LEFT_STICK_TAP_HOLD,
    RIGHT_STICK_UP, RIGHT_STICK_DOWN, RIGHT_STICK_LEFT, RIGHT_STICK_RIGHT,
    RIGHT_STICK_PRESS, RIGHT_STICK_DOUBLE, RIGHT_STICK_TAP_HOLD,
    // Back + Home, Start, Select, then the face buttons by their printed label.
    HOME, START, SELECT, A, B, X, Y;

    // The buttons pushed the matrix past 32 bits, so masks are Longs; stored values only grew.
    val bit: Long get() = 1L shl ordinal

    companion object {
        const val ALL = (1L shl 33) - 1
        /** The first twelve controls, which exist in every stored exception; the sticks came later. */
        const val BEFORE_STICKS = (1L shl 12) - 1
        /** The fourteen stick controls, as a mask shifted down to bit 0. */
        const val STICK_BITS = (1L shl 14) - 1
        /** The seven button controls (Home to Y), as a mask shifted down to bit 0. */
        const val BUTTON_BITS = (1L shl 7) - 1
        const val BUTTON_SHIFT = 26
        val BACK = listOf(SINGLE, DOUBLE, TAP_HOLD, HOLD)

        /** The column of [button]'s control; both layers of dual mode share it. */
        fun of(button: ComboButton): ExceptionControl = when (val control = button.base) {
            ShoulderButton.L1 -> L1
            ShoulderButton.R1 -> R1
            ShoulderButton.L2 -> L2
            ShoulderButton.R2 -> R2
            DpadDirection.UP -> UP
            DpadDirection.DOWN -> DOWN
            DpadDirection.LEFT -> LEFT
            DpadDirection.RIGHT -> RIGHT
            is AndroidButton -> valueOf(control.name)
            is FaceButton -> valueOf(control.name)
            is StickButton -> valueOf("${control.stick.name}_STICK_${control.direction.name}")
            is StickClickButton -> valueOf("${control.stick.name}_STICK_${control.click.name}")
            else -> error("Unknown combo button ${button.prefKey}")
        }
    }
}

/** Gestures and combos allowed with controller focus; a blocked Hold also keeps either swap partner in place. */
data class AppException(
    val packageName: String,
    val name: String,
    val allowed: Long = ExceptionControl.ALL,
    val active: Boolean = true,
) {
    /** With every Back gesture off, Back reaches the app untouched, so no Back combo can exist either. */
    val backNative: Boolean get() = ExceptionControl.BACK.none { allowed and it.bit != 0L }

    fun allows(control: ExceptionControl): Boolean = when {
        !active -> true
        control in ExceptionControl.BACK -> allowed and control.bit != 0L
        else -> !backNative && allowed and control.bit != 0L
    }

    companion object {
        /** Tab-separated lines; labels lose tabs and line breaks so they cannot break the format. */
        fun encode(exceptions: List<AppException>): String = exceptions.joinToString("\n") {
            listOf(it.packageName, it.allowed, if (it.active) 1 else 0,
                it.name.replace(Regex("[\t\r\n]"), " ")).joinToString("\t")
        }

        fun decode(value: String?): List<AppException> = value.orEmpty().lineSequence().mapNotNull { line ->
            val parts = line.split('\t', limit = 4)
            val pkg = parts[0]
            val allowed = parts.getOrNull(1)?.toLongOrNull()
            if (parts.size != 4 || !Shell.isPkg(pkg) || allowed == null) null
            else AppException(pkg, parts[3].ifBlank { pkg }, allowed and ExceptionControl.ALL, parts[2] != "0")
        }.distinctBy { it.packageName }.toList()
    }
}
