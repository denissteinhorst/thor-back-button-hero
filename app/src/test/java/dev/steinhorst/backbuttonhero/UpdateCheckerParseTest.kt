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

    @Test
    fun `a spent rate limit is named as such and says when to retry`() {
        val failed = UpdateChecker.httpFailure(403, remaining = "0", reset = "1000300", retryAfter = null, nowSeconds = 1_000_000)
        assertEquals(UpdateChecker.Failure.RATE_LIMITED, failed.failure)
        assertEquals(5, failed.retryMinutes)
        assertEquals(1, UpdateChecker.httpFailure(403, "0", "1000001", null, 1_000_000).retryMinutes)
        assertEquals(60, UpdateChecker.httpFailure(403, "0", "9000000", null, 1_000_000).retryMinutes)
    }

    @Test
    fun `retry-after and a 429 count as rate limits too, with or without a time`() {
        assertEquals(2, UpdateChecker.httpFailure(403, null, null, "90", 0).retryMinutes)
        assertEquals(UpdateChecker.Failure.RATE_LIMITED, UpdateChecker.httpFailure(429, null, null, null, 0).failure)
        assertNull(UpdateChecker.httpFailure(429, null, null, null, 0).retryMinutes)
    }

    @Test
    fun `other statuses are an unexpected answer, not a connection problem`() {
        assertEquals(UpdateChecker.Failure.UNEXPECTED, UpdateChecker.httpFailure(404, null, null, null, 0).failure)
        assertEquals(UpdateChecker.Failure.UNEXPECTED, UpdateChecker.httpFailure(500, "59", null, null, 0).failure)
        // A plain 403 with calls left (a block, not the quota) is not a rate limit.
        assertEquals(UpdateChecker.Failure.UNEXPECTED, UpdateChecker.httpFailure(403, "12", null, null, 0).failure)
    }

    private val base = "https://github.com/denissteinhorst/thor-back-button-hero/releases/download/v1.5.0"
    private val hex = "a".repeat(64)

    private fun release(asset: String) =
        """{"tag_name":"v1.5.0","html_url":"https://example.com","assets":[$asset]}"""

    @Test
    fun `the release APK is found with its size and published checksum`() {
        val apk = UpdateChecker.parseRelease(release(
            """{"name":"thor-back-button-hero-v1.5.0.apk","browser_download_url":"$base/thor-back-button-hero-v1.5.0.apk","size":3466275,"digest":"sha256:${hex.uppercase()}"}""",
        ))?.apk
        assertEquals(UpdateChecker.Asset("thor-back-button-hero-v1.5.0.apk", "$base/thor-back-button-hero-v1.5.0.apk", 3_466_275, hex), apk)
    }

    @Test
    fun `a missing or malformed digest leaves the checksum out, the APK stays usable`() {
        fun digestOf(digest: String) = UpdateChecker.parseRelease(release(
            """{"name":"a.apk","browser_download_url":"$base/a.apk","size":10,"digest":"$digest"}""",
        ))?.apk
        assertNull(digestOf("sha256:xyz")?.sha256)
        assertNull(digestOf("md5:$hex")?.sha256)
        assertEquals(10L, digestOf("")?.size)
    }

    @Test
    fun `an APK that is not from this repository's release downloads is never offered`() {
        fun apk(url: String, name: String = "a.apk", size: Long = 10) = UpdateChecker.parseRelease(release(
            """{"name":"$name","browser_download_url":"$url","size":$size}""",
        ))?.apk
        assertNull(apk("https://evil.example.com/releases/download/v1/a.apk"))
        assertNull(apk("https://github.com/someone/else/releases/download/v1/a.apk"))
        assertNull(apk("$base/notes.txt", name = "notes.txt"))
        assertNull(apk("$base/a.apk", size = 0))
        assertNull(apk("$base/a.apk", size = 500L * 1024 * 1024))
        assertNull(UpdateChecker.parseRelease("""{"tag_name":"v1.5.0","html_url":"https://example.com"}""")?.apk)
    }

    @Test
    fun `the first usable APK wins over unusable assets before it`() {
        val body = release("""{"name":"sources.zip","browser_download_url":"$base/sources.zip","size":5},{"name":"b.apk","browser_download_url":"$base/b.apk","size":7}""")
        assertEquals("b.apk", UpdateChecker.parseRelease(body)?.apk?.name)
    }
}
