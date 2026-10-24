package dev.steinhorst.backbuttonhero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateCheckerParseTest {

    @Test
    fun `parses a well-formed release payload`() {
        val body = """{"tag_name":"v7","html_url":"https://example.com/releases/v7"}"""
        val release = UpdateChecker.parseRelease(body)
        assertEquals(7, release?.versionCode)
        assertEquals("v7", release?.tagName)
        assertEquals("https://example.com/releases/v7", release?.htmlUrl)
    }

    @Test
    fun `returns null when the tag does not parse as v-plus-int`() {
        val body = """{"tag_name":"release-7","html_url":"https://example.com"}"""
        assertNull(UpdateChecker.parseRelease(body))
    }

    @Test(expected = org.json.JSONException::class)
    fun `throws on malformed JSON, left to the caller's runCatching`() {
        UpdateChecker.parseRelease("not json")
    }

    @Test(expected = org.json.JSONException::class)
    fun `throws when tag_name is missing`() {
        UpdateChecker.parseRelease("""{"html_url":"https://example.com"}""")
    }
}
