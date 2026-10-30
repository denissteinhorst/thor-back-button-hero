package dev.steinhorst.backbuttonhero

/**
 * RetroArch resets its port assignment when Android resumes it (a swap back to the top screen,
 * a return from another app) and gives port 1 to the first device it hears from. AYN's focus
 * switch commits with a key injected from Android's "Virtual" device. If that key is the first
 * thing RetroArch hears, "Virtual" takes port 1 and the pad is left unusable on port 2.
 *
 * Two measures keep the pad first. A swap that moves RetroArch while it has the controller moves
 * the controller route with it beforehand, so the user needs no focus switch afterwards and the
 * route's injected key reaches the app that is still in front. A nudge from the pad right after
 * RetroArch arrives then claims port 1, because the route already points at RetroArch.
 *
 * A deliberate, owner-approved app-specific exception: no other app is known to assign
 * controller ports by first input. Experimental; [EXPERIMENTAL] switches both measures off.
 */
object PadPortClaim {
    /** EXPERIMENTAL (2026-10-30): route-follows-swap and the port nudge, RetroArch only. */
    const val EXPERIMENTAL = true

    /** RetroArch may still be resetting at the first nudge; the second covers a slow reset. */
    val NUDGE_DELAYS_MS = longArrayOf(600L, 1_500L)

    /** Covers the 64-bit, 32-bit and Plus builds. */
    fun isRetroArch(packageName: String?): Boolean = packageName?.startsWith("com.retroarch") == true

    /** RetroArch came to the front on a display where it was not in front before. */
    fun arrived(before: Set<Int>, now: Set<Int>): Boolean = (now - before).isNotEmpty()

    /**
     * Where the controller route goes before a swap: to the other screen when RetroArch is the
     * app being controlled, otherwise nowhere (null). [controlledIsTop] tells which screen the
     * route points at now.
     */
    fun routeBeforeSwap(controlledIsTop: Boolean, topPackage: String?, bottomPackage: String?): String? {
        if (!EXPERIMENTAL || !isRetroArch(if (controlledIsTop) topPackage else bottomPackage)) return null
        return if (controlledIsTop) Prefs.DISPLAY_BOTTOM else Prefs.DISPLAY_TOP
    }
}
