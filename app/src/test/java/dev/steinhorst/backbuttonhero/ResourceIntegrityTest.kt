package dev.steinhorst.backbuttonhero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ResourceIntegrityTest {

    private fun stringEntries(path: String): Map<String, Boolean> {
        // name -> translatable (default true, since the attribute is only set to override)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val nodes = doc.getElementsByTagName("string")
        return buildMap {
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as org.w3c.dom.Element
                val name = el.getAttribute("name")
                val translatable = el.getAttribute("translatable") != "false"
                put(name, translatable)
            }
        }
    }

    @Test
    fun `every translatable string in values has a DE counterpart, and nothing extra exists`() {
        val en = stringEntries("src/main/res/values/strings.xml")
        val de = stringEntries("src/main/res/values-de/strings.xml")
        val expectedDeKeys = en.filterValues { it }.keys
        assertEquals(expectedDeKeys, de.keys)
    }

    @Test
    fun `app_name is marked non-translatable and has no DE entry`() {
        val en = stringEntries("src/main/res/values/strings.xml")
        val de = stringEntries("src/main/res/values-de/strings.xml")
        assertEquals(false, en["app_name"])
        assertTrue("app_name" !in de)
    }

    @Test
    fun `every placeholder in a DE string also appears in the EN original`() {
        // ${'$'} is how a literal dollar sign is written in Kotlin - a bare $ would be
        // parsed as the start of a string template and fail to compile.
        val placeholderRegex = Regex("""%\d*${'$'}?[sd]""")
        fun placeholders(path: String): Map<String, Set<String>> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
            val nodes = doc.getElementsByTagName("string")
            return buildMap {
                for (i in 0 until nodes.length) {
                    val el = nodes.item(i) as org.w3c.dom.Element
                    put(el.getAttribute("name"), placeholderRegex.findAll(el.textContent).map { it.value }.toSet())
                }
            }
        }
        val en = placeholders("src/main/res/values/strings.xml")
        val de = placeholders("src/main/res/values-de/strings.xml")
        de.forEach { (name, dePlaceholders) ->
            assertEquals("placeholder mismatch in $name", en[name].orEmpty(), dePlaceholders)
        }
    }
}
