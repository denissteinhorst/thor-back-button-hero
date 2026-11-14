package dev.steinhorst.backbuttonhero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionGroupsTest {
    @Test
    fun `groups fold only known single actions and never overlap`() {
        val all = ActionGroups.Group.values().flatMap { g -> g.options.map { it.action } }
        assertEquals(all.size, all.toSet().size)
        assertTrue(Prefs.KNOWN_ACTIONS.containsAll(all))
        assertEquals(all.toSet(), ActionGroups.GROUPED)
    }

    @Test
    fun `an app switcher binding keeps its own stored action`() {
        assertEquals(ActionGroups.Group.APP_SWITCHER, ActionGroups.groupOf(Prefs.SHOULDER_RECENTS))
        assertEquals(ActionGroups.Group.APP_SWITCHER, ActionGroups.groupOf(Prefs.SHOULDER_SWITCHER))
        assertEquals(ActionGroups.Group.VOLUME, ActionGroups.groupOfEntry("@group_volume"))
        assertNull(ActionGroups.groupOf(Prefs.SHOULDER_HOME))
        assertNull(ActionGroups.groupOfEntry("@volume_up"))
    }

    @Test
    fun `entries are never a storable action`() {
        assertTrue(ActionGroups.Group.values().none { it.entry in Prefs.KNOWN_ACTIONS || Prefs.isShoulderApp(it.entry) })
    }
}
