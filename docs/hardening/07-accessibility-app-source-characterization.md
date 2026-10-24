# Schritt 07 — Characterization: `AccessibilityAppSource`

## 1. Ziel

Den Thumbnail-Cache und die gegen eine leere Fensterliste robusten Pfade von
`AccessibilityAppSource` absichern. Die MRU-Rekonstruktion aus echten Fenstern ist unter
Robolectric nicht herstellbar und bleibt eine dokumentierte Lücke.

## 2. Voraussetzungen

Schritt 06 ist committet und `./tools/verify.sh` läuft grün.

**Vorher lesen: `docs/hardening/PITFALLS.md`, insbesondere P3.** Dieser Schritt ist der
fragilste der Characterization-Reihe — er hängt davon ab, wie sich
`AccessibilityService.windowsOnAllDisplays` unter Robolectric verhält, und das ist nicht
garantiert.

## 3. Exakte Aufgaben

### 3.1 Zuerst klären, ob der Service überhaupt baubar ist

`AccessibilityAppSource` braucht ein `AccessibilityService` im Konstruktor. Bevor Tests
geschrieben werden, mit einem Wegwerf-Test prüfen, welcher der beiden Konstruktionswege
funktioniert:

```kotlin
// Variante A - ohne onServiceConnected():
val service = Robolectric.buildService(SwitcherService::class.java).create().get()

// Variante B - mit onCreate() und onServiceConnected():
val service = Robolectric.setupService(SwitcherService::class.java)
```

**Variante A ist die richtige Wahl.** Begründung: `SwitcherService.onServiceConnected()`
konstruiert selbst ein `AccessibilityAppSource` **und** ein `SwitcherOverlay`, und letzteres
ist unter Robolectric nicht zuverlässig instanziierbar (`TEST-GAPS.md` Punkt 3). Variante B
würde `onServiceConnected()` auslösen und daran scheitern. Variante A ruft nur `onCreate()`
auf und lässt die `lateinit`-Felder `overlay`/`source` uninitialisiert — das ist in Ordnung,
solange kein Test sie berührt.

### 3.2 Verhalten von `windowsOnAllDisplays` feststellen

Einen einzelnen Probe-Test schreiben und ausführen:
```kotlin
@Test
fun probe() {
    val service = Robolectric.buildService(SwitcherService::class.java).create().get()
    println("windowsOnAllDisplays = " + service.windowsOnAllDisplays)
}
```
Ausführen mit
`JAVA_HOME=... ./gradlew testDebugUnitTest --tests "*AccessibilityAppSourceTest*" --info`.

Drei möglichen Ergebnisse und was jeweils zu tun ist:
- **Leere `SparseArray`** (bester Fall): alle Tests aus 3.3 sind umsetzbar.
- **`null`**: `topApp`/`isCovered`/`record` nutzen im Produktivcode `?:`/`.orEmpty()` und
  sind damit trotzdem sicher — alle Tests aus 3.3 bleiben umsetzbar.
- **Exception** (z. B. `IllegalStateException`): dann sind `topApp` und `appsOnDisplay`
  **nicht** testbar. In dem Fall: diese beiden Testfälle aus 3.3 **weglassen**, nur die drei
  Thumbnail-Tests behalten, und in `docs/hardening/TEST-GAPS.md` unter Punkt 3 einen Satz
  ergänzen, dass `windowsOnAllDisplays` unter Robolectric wirft und deshalb auch `topApp`
  ungetestet bleibt. **Den Probe-Test danach wieder löschen.**

**Nicht** versuchen, `AccessibilityAppSource` oder `SwitcherService` umzubauen, damit die
Tests laufen — das wäre eine Produktivcode-Änderung außerhalb des Scopes.

### 3.3 Die Tests

Datei anlegen `app/src/test/java/dev/steinhorst/backbuttonhero/AccessibilityAppSourceTest.kt`:

```kotlin
package dev.steinhorst.backbuttonhero

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccessibilityAppSourceTest {

    private lateinit var source: AccessibilityAppSource

    @Before
    fun setUp() {
        // buildService(...).create() runs onCreate() but NOT onServiceConnected(), which would
        // try to build a SwitcherOverlay - not viable under Robolectric. See TEST-GAPS.md #3.
        val service = Robolectric.buildService(SwitcherService::class.java).create().get()
        source = AccessibilityAppSource(service)
    }

    private fun bitmap() = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

    @Test
    fun `saving a second thumbnail for the same package recycles the first`() {
        val first = bitmap()
        val second = bitmap()
        source.saveThumbnail("com.example.app", first)
        source.saveThumbnail("com.example.app", second)
        assertTrue("the replaced bitmap should have been recycled", first.isRecycled)
        assertFalse("the current bitmap must stay usable", second.isRecycled)
    }

    @Test
    fun `forget recycles the cached thumbnail for that package`() {
        val held = bitmap()
        source.saveThumbnail("com.example.app", held)
        source.forget("com.example.app")
        assertTrue(held.isRecycled)
    }

    @Test
    fun `forget leaves other packages' thumbnails untouched`() {
        val kept = bitmap()
        val dropped = bitmap()
        source.saveThumbnail("com.example.kept", kept)
        source.saveThumbnail("com.example.dropped", dropped)
        source.forget("com.example.dropped")
        assertTrue(dropped.isRecycled)
        assertFalse(kept.isRecycled)
    }

    @Test
    fun `forget on an unknown package does not throw`() {
        source.forget("com.never.seen")
    }

    // The two tests below depend on windowsOnAllDisplays being non-throwing under
    // Robolectric - see section 3.2. Drop them (and note it in TEST-GAPS.md) if the probe
    // showed an exception.

    @Test
    fun `topApp returns null when the display has no windows`() {
        assertNull(source.topApp(0))
    }

    @Test
    fun `appsOnDisplay returns an empty list when nothing was ever recorded`() {
        assertEquals(emptyList<RunningApp>(), source.appsOnDisplay(0))
    }
}
```

## 4. Nicht anfassen

- `AccessibilityAppSource.kt` und `SwitcherService.kt` bleiben unverändert.
- `record()` und `isCovered()` werden **nicht** getestet — dokumentierte Lücke
  (`TEST-GAPS.md`), kein Versehen.
- Den Probe-Test aus 3.2 nach der Klärung löschen, nicht committen.

## 5. Akzeptanzkriterien

- `./tools/verify.sh` grün.
- Mindestens die vier Thumbnail-Tests sind grün und committet.
- Falls die beiden letzten Tests weggelassen wurden: `TEST-GAPS.md` ist entsprechend ergänzt
  und die Commit-Message erwähnt es.

## 6. Gate

`./tools/verify.sh`. Bei Rot: zuerst 3.2 erneut prüfen (welche Variante, welches Verhalten).
Bei anhaltendem Rot: nur die neue Testdatei zurücknehmen, stoppen, melden.

## 7. Commit-Message-Vorschlag

```
test: characterize AccessibilityAppSource's thumbnail cache

Covers bitmap replacement, recycling on forget, per-package isolation,
and the unknown-package no-op. The MRU reconstruction from real
accessibility windows stays untested by design - see TEST-GAPS.md #3.
```
