package dev.steinhorst.backbuttonhero

/** Decides when a dual-screen frontend's bottom activity is parked instead of being covered. */
object ParkingPolicy {
    /** A frontend that takes the bottom screen back sooner than this after a park would loop. */
    const val REPARK_MIN_INTERVAL_MS = 3_000L

    /** The same app in front on both screens draws its bottom activity as part of one frontend. */
    fun candidate(topPackage: String?, bottomPackage: String?, ownPackage: String, abandoned: Set<String>): String? =
        bottomPackage?.takeIf { it == topPackage && it != ownPackage && it !in abandoned && Shell.isPkg(it) }

    /** A reclaim long after the last park (wake, return from a game) is parked again; a quick one is not. */
    fun mayRepark(now: Long, lastParkedAt: Long): Boolean = now - lastParkedAt >= REPARK_MIN_INTERVAL_MS

    fun parkedTask(result: String?): Int? =
        result?.takeIf { it.startsWith("OK task=") }?.removePrefix("OK task=")?.toIntOrNull()?.takeIf { it >= 0 }
}
