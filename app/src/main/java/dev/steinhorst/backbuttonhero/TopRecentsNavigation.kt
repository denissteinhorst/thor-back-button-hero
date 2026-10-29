package dev.steinhorst.backbuttonhero

/** Retains presses during Overview initialization; the opening Back release never starts input. */
class TopRecentsNavigation(
    private val navigate: (DpadDirection) -> Unit,
    private val dismiss: () -> Unit,
) {
    private val pending = ArrayDeque<DpadDirection>()
    private var ready = false
    private var actions = false

    fun reset() {
        pending.clear()
        ready = false
        actions = false
    }

    fun press(direction: DpadDirection) {
        pending.addLast(direction)
        drain()
    }

    fun pause() { ready = false }

    fun ready(actionsFocused: Boolean = false) {
        actions = actionsFocused
        ready = true
        drain()
    }

    private fun drain() {
        while (ready && pending.isNotEmpty()) {
            val direction = pending.removeFirst()
            when {
                direction == DpadDirection.UP && !actions -> dismiss()
                direction == DpadDirection.DOWN && actions -> Unit
                else -> {
                    navigate(direction)
                    if (direction == DpadDirection.DOWN) actions = true
                    if (direction == DpadDirection.UP) actions = false
                }
            }
        }
    }
}
