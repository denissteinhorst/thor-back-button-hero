package dev.steinhorst.backbuttonhero.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerMenuTest {
    private class Node(val name: String, val focusable: Boolean, vararg val children: Node)

    private fun first(root: Node) = PowerMenu.firstOption(root, { it.children.toList() }, Node::focusable)?.name

    @Test
    fun `the first option wins over the focusable strip around it`() {
        val dialog = Node("decor", false,
            Node("strip", true,
                Node("power off", true, Node("icon", false), Node("label", false)),
                Node("restart", true, Node("label", false)),
            ),
        )
        assertEquals("power off", first(dialog))
    }

    @Test
    fun `an unfocusable leading branch is skipped`() {
        val dialog = Node("decor", false, Node("title", false, Node("text", false)), Node("strip", true, Node("restart", true)))
        assertEquals("restart", first(dialog))
    }

    @Test
    fun `a focusable container without focusable children is the option itself`() {
        assertEquals("strip", first(Node("decor", false, Node("strip", true, Node("label", false)))))
        assertNull(first(Node("decor", false, Node("label", false))))
    }
}
