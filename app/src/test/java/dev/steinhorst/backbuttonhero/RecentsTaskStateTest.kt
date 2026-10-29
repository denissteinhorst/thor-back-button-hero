package dev.steinhorst.backbuttonhero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecentsTaskStateTest {
    @Test
    fun `system task display and lock values are retained without assuming bottom id`() {
        assertEquals(listOf(RecentsTaskState("dev.example.app", 17, true)),
            RecentsTaskState.parse("OK [{\"package\":\"dev.example.app\",\"display\":17,\"locked\":true}]"))
    }

    @Test
    fun `native card label stays bound to its task identity across a display move`() {
        val output = """OK [{"package":"dev.example.app","display":17,"locked":false,"task":42,"label":"Dateien & Ordner"}]"""
        val task = RecentsTaskState.parse(output)!!.single()
        assertEquals(42, task.taskId)
        assertEquals("Dateien & Ordner", task.label)
        assertEquals(task, RecentsSelection.fromCard(listOf(task.copy(displayId = 0)), listOf(task), "Dateien & Ordner"))
    }

    @Test
    fun `missing and invalid state never becomes an unlocked snapshot`() {
        assertNull(RecentsTaskState.parse(null))
        assertNull(RecentsTaskState.parse("ERR unavailable"))
        assertNull(RecentsTaskState.parse("OK [{\"package\":\"invalid;command\"}]"))
        assertNull(RecentsTaskState.parse("OK [{\"package\":\"dev.example.app\",\"display\":0}]"))
    }

    @Test
    fun `native store uses key presence including zero valued entries`() {
        val xml = "<map><int name=\"u: 0, dev.example.app\" value=\"0\"/><int name=\"u: 10, dev.example.app\" value=\"0\"/></map>"
        assertEquals(setOf("u: 0, dev.example.app", "u: 10, dev.example.app"), RecentsTaskState.lockKeys(xml))
        assertEquals(emptySet<String>(), RecentsTaskState.lockKeys("<map/>"))
    }

    @Test
    fun `corrupt lock store is rejected rather than interpreted as unlocked`() {
        assertThrows(Exception::class.java) { RecentsTaskState.lockKeys("<map><int name=\"u: 0, app\"") }
        assertThrows(IllegalArgumentException::class.java) { RecentsTaskState.lockKeys("<unrelated/>") }
    }

}
