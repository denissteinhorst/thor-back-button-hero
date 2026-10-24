package dev.steinhorst.backbuttonhero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateCheckerParseTest {

    @Test
    fun `parses a well-formed semver release payload`() {
        val body = """{"tag_name":"v1.2.3","html_url":"https://example.com/releases/v1.2.3"}"""
        val release = UpdateChecker.parseRelease(body)
        assertEquals(UpdateChecker.Version(1, 2, 3), release?.version)
        assertEquals("v1.2.3", release?.tagName)
        assertEquals("https://example.com/releases/v1.2.3", release?.htmlUrl)
    }

    @Test
    fun `returns null when the tag is not a version at all`() {
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

    // The 1.0.0 and 1.0.1 releases were tagged v1 and v2, before the switch to semver tags.
    // A build installed from those must stay comparable, otherwise its update check would
    // fail forever.
    @Test
    fun `accepts the legacy versionCode tags by padding them out`() {
        assertEquals(UpdateChecker.Version(1, 0, 0), UpdateChecker.parseVersion("v1"))
        assertEquals(UpdateChecker.Version(2, 0, 0), UpdateChecker.parseVersion("v2"))
    }

    @Test
    fun `accepts a two-part version and a bare version without the v prefix`() {
        assertEquals(UpdateChecker.Version(1, 4, 0), UpdateChecker.parseVersion("v1.4"))
        assertEquals(UpdateChecker.Version(1, 4, 2), UpdateChecker.parseVersion("1.4.2"))
    }

    @Test
    fun `drops prerelease and build metadata suffixes`() {
        assertEquals(UpdateChecker.Version(1, 2, 0), UpdateChecker.parseVersion("v1.2.0-beta.1"))
        assertEquals(UpdateChecker.Version(1, 2, 0), UpdateChecker.parseVersion("v1.2.0+build7"))
    }

    @Test
    fun `rejects versions it cannot order`() {
        assertNull(UpdateChecker.parseVersion("v1.2.3.4"))
        assertNull(UpdateChecker.parseVersion("v1.x.0"))
        assertNull(UpdateChecker.parseVersion("v-1.0.0"))
        assertNull(UpdateChecker.parseVersion(""))
        assertNull(UpdateChecker.parseVersion("v"))
    }

    @Test
    fun `orders versions by major then minor then patch`() {
        val v = { s: String -> UpdateChecker.parseVersion(s)!! }
        assertTrue(v("v1.1.0") > v("v1.0.1"))
        assertTrue(v("v2.0.0") > v("v1.9.9"))
        assertTrue(v("v1.0.2") > v("v1.0.1"))
        assertEquals(v("v1.0.1"), v("v1.0.1"))
        assertTrue(v("v1.0.0") < v("v1.0.1"))
    }

    @Test
    fun `a semver release is newer than a legacy-tagged install of the same line`() {
        // The real migration case: 1.0.1 is installed (tagged v2 back then) and the next
        // release is tagged v1.1.0. Comparison runs against versionName, not the tag.
        val installed = UpdateChecker.parseVersion("1.0.1")!!
        val released = UpdateChecker.parseVersion("v1.1.0")!!
        assertTrue(released > installed)
    }
}
