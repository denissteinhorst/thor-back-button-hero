package dev.steinhorst.backbuttonhero

import android.view.KeyEvent

/**
 * Lets the pad's Home key stand in for Back ([Prefs.homeAsBack]). Whichever of the two goes down
 * first owns the one virtual Back until every device has released it; a press of the other key
 * meanwhile is swallowed whole, so holding both never looks like two Back presses or Back + Home.
 * The decision is made at DOWN, so a press keeps its route when the option or interception changes
 * before its UP.
 */
class HomeAsBack {
    enum class Route {
        /** Not ours: the event continues unchanged. */
        PASS,
        /** Home owning the virtual Back: handle it as a Back event. */
        AS_BACK,
        /** The second of the two keys: consume it and do nothing. */
        SWALLOW,
    }

    private val owned = PhysicalKeyTracker()
    private val swallowed = PhysicalKeyTracker()
    private var owner: Int? = null

    /** [keyCode] is Back or the pad's Home; [enabled] is the option while interception is on. */
    fun route(keyCode: Int, deviceId: Int, action: Int, repeatCount: Int, enabled: Boolean): Route {
        when (action) {
            KeyEvent.ACTION_DOWN -> {
                if (swallowed.isDown(keyCode)) {
                    if (repeatCount == 0) swallowed.down(keyCode, deviceId)
                    return Route.SWALLOW
                }
                if (repeatCount == 0) {
                    val current = owner
                    when {
                        current == null && !enabled -> return Route.PASS
                        current == null || current == keyCode -> { owner = keyCode; owned.down(keyCode, deviceId) }
                        else -> { swallowed.down(keyCode, deviceId); return Route.SWALLOW }
                    }
                }
                return ownRoute(keyCode)
            }
            KeyEvent.ACTION_UP -> {
                if (swallowed.up(keyCode, deviceId)) return Route.SWALLOW
                if (owner != keyCode || !owned.up(keyCode, deviceId)) return Route.PASS
                if (!owned.isDown(keyCode)) owner = null
                return if (keyCode == KeyEvent.KEYCODE_HOME) Route.AS_BACK else Route.PASS
            }
        }
        return ownRoute(keyCode)
    }

    private fun ownRoute(keyCode: Int): Route =
        if (keyCode == KeyEvent.KEYCODE_HOME && owner == keyCode) Route.AS_BACK else Route.PASS

    fun reset() {
        owned.clear()
        swallowed.clear()
        owner = null
    }
}
