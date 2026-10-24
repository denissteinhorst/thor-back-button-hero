# Schritt 26 — `UpdateChecker`-Parse-Seam + Tests

## 1. Ziel

Die reine JSON-Parse-Logik aus `UpdateChecker.fetchLatestRelease()` als `internal`
Seam extrahieren, damit sie ohne Netzwerkzugriff testbar ist — ohne das
Netzwerk-/Notification-Verhalten zu ändern.

## 2. Voraussetzungen

Schritt 25 ist committet und `./tools/verify.sh` läuft grün.

## 3. Exakte Aufgaben

1. In `UpdateChecker.kt` die `data class Release` bleibt unverändert. Aus
   `fetchLatestRelease()` wird der Block
   ```kotlin
   val body = connection.inputStream.bufferedReader().use { it.readText() }
   val json = JSONObject(body)
   val tagName = json.getString("tag_name")
   val versionCode = tagName.removePrefix("v").toIntOrNull() ?: return null
   Release(tagName, json.getString("html_url"), versionCode)
   ```
   **wortwörtlich** in eine neue Funktion extrahiert:
   ```kotlin
   internal fun parseRelease(body: String): Release? {
       val json = JSONObject(body)
       val tagName = json.getString("tag_name")
       val versionCode = tagName.removePrefix("v").toIntOrNull() ?: return null
       return Release(tagName, json.getString("html_url"), versionCode)
   }
   ```
   `fetchLatestRelease()` ruft diese Funktion danach so auf:
   ```kotlin
   private fun fetchLatestRelease(): Release? {
       val connection = URL("https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest")
           .openConnection() as HttpURLConnection
       connection.setRequestProperty("Accept", "application/vnd.github+json")
       connection.connectTimeout = 5000
       connection.readTimeout = 5000
       return try {
           if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
           val body = connection.inputStream.bufferedReader().use { it.readText() }
           parseRelease(body)
       } finally {
           connection.disconnect()
       }
   }
   ```
   `data class Release` muss `internal` statt `private` sein, damit der Test sie sieht —
   in `UpdateChecker.kt` `private data class Release(...)` zu `internal data class
   Release(...)` ändern. Das ist die einzige Sichtbarkeitsänderung in diesem Schritt.
2. `JSONObject`-Exceptions (ungültiges JSON, fehlendes Feld) wirft `parseRelease` weiterhin
   nach oben durch — `fetchLatestRelease()` ist schon vorher über
   `runCatching { fetchLatestRelease() }.getOrNull()` im Aufrufer `checkInBackground`
   abgesichert, das ändert sich nicht.
3. Datei anlegen
   `app/src/test/java/dev/steinhorst/backbuttonhero/UpdateCheckerParseTest.kt`:
   ```kotlin
   package dev.steinhorst.backbuttonhero

   import org.junit.Assert.assertEquals
   import org.junit.Assert.assertNull
   import org.junit.Test

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
   ```
4. In `app/build.gradle.kts` die `jacocoCoverageVerification`-`violationRules` (Schritt 19)
   um eine weitere Regel ergänzen:
   ```kotlin
   rule {
       element = "CLASS"
       includes = listOf("dev.steinhorst.backbuttonhero.UpdateChecker")
       limit { minimum = "0.70".toBigDecimal() }
   }
   ```

## 4. Nicht anfassen

- Das Netzwerk-/Notification-Verhalten von `UpdateChecker` bleibt vollständig unverändert.
- `checkInBackground`, `notify`, `deliver` werden nicht angefasst.

## 5. Akzeptanzkriterien

- `./tools/verify.sh` grün, inklusive der neuen `UpdateChecker`-Coverage-Regel.
- Alle 4 neuen Tests grün.

## 6. Gate

`./tools/verify.sh`. Bei Rot: `git restore` auf `UpdateChecker.kt` + die neue Testdatei +
`app/build.gradle.kts`, stoppen, melden.

## 7. Commit-Message-Vorschlag

```
refactor: extract UpdateChecker's JSON parsing into a testable seam

parseRelease() is now internal and unit-tested without any network
access. fetchLatestRelease()'s network/error-handling behavior is
unchanged.
```
