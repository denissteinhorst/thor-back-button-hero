package dev.steinhorst.backbuttonhero

object PowerMenu {
    /**
     * The first focusable node without focusable descendants, in layout order. The system power
     * dialog makes its option strip focusable as a whole: the first D-pad press lands on that strip
     * and further presses cannot move from it into the options, so the controller would be stuck.
     */
    fun <T> firstOption(root: T, children: (T) -> List<T>, focusable: (T) -> Boolean): T? {
        children(root).forEach { child -> firstOption(child, children, focusable)?.let { return it } }
        return root.takeIf(focusable)
    }
}
