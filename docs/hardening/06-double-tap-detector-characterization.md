# Schritt 06 — Characterization: `DoubleTapDetector`

## 1. Ziel

`DoubleTapDetector` (18 Zeilen, reine Logik) vollständig abdecken — inklusive des
`last == 0L`-Sentinels, der leicht zu einer falschen Testannahme führt.

## 2. Voraussetzungen

Schritt 05 ist committet und `./tools/verify.sh` läuft grün.

**Vorher lesen: `docs/hardening/PITFALLS.md`, insbesondere P1.**

## 3. Exakte Aufgaben

`DoubleTapDetector` liest die Zeit über `SystemClock.uptimeMillis()` **direkt** (nicht
injiziert), braucht also Robolectric, damit die Uhr kontrollierbar ist.

**Der entscheidende Punkt:** `last` ist mit `0L` initialisiert und die Bedingung lautet
`if (last != 0L && now - last <= windowMs())`. `0L` ist also der Sentinel für „noch kein
Druck". Steht die Uhr beim ersten `onPress()` auf `0`, wird `last = 0` gesetzt — und der
zweite Druck erkennt wegen `last != 0L` == `false` **keinen** Doppeltipp. Deshalb muss in
jedem Test die Uhr **vor** dem ersten Druck von 0 weg vorgerückt werden.

Datei anlegen `app/src/test/java/dev/steinhorst/backbuttonhero/DoubleTapDetectorTest.kt`:

```kotlin
package dev.steinhorst.backbuttonhero

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class DoubleTapDetectorTest {

    /** Advances Robolectric's clock, which is what SystemClock.uptimeMillis() reads. */
    private fun advance(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    @Before
    fun moveClockAwayFromZero() {
        // DoubleTapDetector treats last == 0L as "no previous press", so a press recorded at
        // uptime 0 can never complete a double tap. See PITFALLS.md P1.
        advance(10_000)
    }

    @Test
    fun `two presses within the window trigger a double tap`() {
        var fired = 0
        val detector = DoubleTapDetector({ 300L }) { fired++ }
        detector.onPress()
        advance(100)
        detector.onPress()
        assertEquals(1, fired)
    }

    @Test
    fun `two presses exactly at the window boundary trigger a double tap`() {
        var fired = 0
        val detector = DoubleTapDetector({ 300L }) { fired++ }
        detector.onPress()
        advance(300)
        detector.onPress()
        assertEquals(1, fired)
    }

    @Test
    fun `two presses just past the window do not trigger a double tap`() {
        var fired = 0
        val detector = DoubleTapDetector({ 300L }) { fired++ }
        detector.onPress()
        advance(301)
        detector.onPress()
        assertEquals(0, fired)
    }

    @Test
    fun `a single press never fires`() {
        var fired = 0
        val detector = DoubleTapDetector({ 300L }) { fired++ }
        detector.onPress()
        assertEquals(0, fired)
    }

    @Test
    fun `after a double tap the state resets, so a third press starts a new window`() {
        var fired = 0
        val detector = DoubleTapDetector({ 300L }) { fired++ }
        detector.onPress()
        advance(100)
        detector.onPress()      // double tap #1
        advance(100)
        detector.onPress()      // lone third press
        assertEquals(1, fired)
    }

    @Test
    fun `a fourth press after the reset completes a second double tap`() {
        var fired = 0
        val detector = DoubleTapDetector({ 300L }) { fired++ }
        detector.onPress()
        advance(100)
        detector.onPress()      // double tap #1
        advance(100)
        detector.onPress()      // starts a new window
        advance(100)
        detector.onPress()      // double tap #2
        assertEquals(2, fired)
    }

    @Test
    fun `windowMs is read fresh on every press, not captured at construction`() {
        var currentWindow = 100L
        var fired = 0
        val detector = DoubleTapDetector({ currentWindow }) { fired++ }
        detector.onPress()
        currentWindow = 1000L
        advance(500)            // outside the old 100ms window, inside the new 1000ms one
        detector.onPress()
        assertEquals(1, fired)
    }
}
```

**Falls `idleFor` nicht existiert:** die Alternative ist
`org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(ms))`. Die exakte
Signatur aus der Compiler-Fehlermeldung übernehmen — **nicht** die Testlogik umbauen und
nicht `Thread.sleep` verwenden (das würde die Tests langsam und flaky machen, ohne die
Robolectric-Uhr zu bewegen).

## 4. Nicht anfassen

- `DoubleTapDetector.kt` bleibt unverändert. Der `last == 0L`-Sentinel wird **nicht**
  „verbessert" — auf einem echten Gerät ist `uptimeMillis()` nach dem Boot nie 0, das
  Verhalten ist in der Praxis korrekt.

## 5. Akzeptanzkriterien

- `./tools/verify.sh` grün, alle 7 Tests grün:
  ```sh
  JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
    ./gradlew testDebugUnitTest --tests "*DoubleTapDetectorTest*" --console=plain
  ```

## 6. Gate

`./tools/verify.sh`. Bei Rot: zuerst prüfen, ob die Uhr tatsächlich von 0 weg vorgerückt
wurde (`@Before`-Block vorhanden und `advance` wirksam). Bei anhaltendem Rot: nur die neue
Testdatei zurücknehmen, stoppen, melden.

## 7. Commit-Message-Vorschlag

```
test: fully characterize DoubleTapDetector

Covers the window boundary on both sides, the lone-press case, state
reset after a double tap, two consecutive double taps, and that
windowMs is read live. The clock is moved off zero first - last == 0L
is the detector's "no previous press" sentinel.
```
