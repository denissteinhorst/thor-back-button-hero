# Schritt 17 — Characterization: `BackGestureMachine`

## 1. Ziel

Jeden Zweig von `BackGestureMachine.handleBack` mit Fake-Clock/Fake-Scheduler/Fake-Actions
abdecken — das ist der Kern des gesamten Sicherheitsnetzes dieses Plans.

## 2. Voraussetzungen

Schritt 16 ist committet (Trace-Gegenprobe bestanden, manuelle Checkliste bestätigt) und
`./tools/verify.sh` läuft grün.

**Vorher lesen: `docs/hardening/PITFALLS.md`, insbesondere P1** — die Testuhr darf niemals bei
`0` starten, sonst erkennt `DoubleTapDetector` grundsätzlich keinen Doppeltipp und mehrere
Tests unten wären unerklärlich rot.

## 3. Exakte Aufgaben

Datei anlegen `app/src/test/java/dev/steinhorst/backbuttonhero/BackGestureMachineTest.kt`.

### 3.1 Fakes

```kotlin
package dev.steinhorst.backbuttonhero

import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class FakeClock(var now: Long) : GestureClock {
    override fun uptimeMillis() = now
}

private class FakeScheduler : GestureScheduler {
    /** runnable -> the delay it was posted with. */
    val pending = linkedMapOf<Runnable, Long>()

    override fun postDelayed(runnable: Runnable, delayMs: Long) { pending[runnable] = delayMs }
    override fun removeCallbacks(runnable: Runnable) { pending.remove(runnable) }
    override fun hasCallbacks(runnable: Runnable) = pending.containsKey(runnable)

    /** Fires every pending runnable whose posted delay is <= [delayMs], in posting order. */
    fun fireDue(delayMs: Long) {
        val due = pending.filterValues { it <= delayMs }.keys.toList()
        due.forEach { pending.remove(it); it.run() }
    }

    fun fireAll() {
        val all = pending.keys.toList()
        pending.clear()
        all.forEach { it.run() }
    }
}

private class RecordingActions : GestureActions {
    var swapScreensCalls = 0
    var openTopRecentsCalls = 0
    var showSwitcherCalls = 0
    var onSingleTapCalls = 0
    override fun swapScreens() { swapScreensCalls++ }
    override fun openTopRecents() { openTopRecentsCalls++ }
    override fun showSwitcher() { showSwitcherCalls++ }
    override fun onSingleTap() { onSingleTapCalls++ }
}
```

### 3.2 Testgerüst

```kotlin
@RunWith(RobolectricTestRunner::class)
class BackGestureMachineTest {

    // Must be > 0: DoubleTapDetector uses last == 0L as its "no previous press" sentinel,
    // so a clock starting at 0 can never recognize a double tap. See PITFALLS.md P1.
    private val startTime = 10_000L

    private lateinit var prefs: Prefs
    private lateinit var clock: FakeClock
    private lateinit var scheduler: FakeScheduler
    private lateinit var actions: RecordingActions
    private lateinit var machine: BackGestureMachine

    @Before
    fun setUp() {
        prefs = Prefs(ApplicationProvider.getApplicationContext())
        clock = FakeClock(startTime)
        scheduler = FakeScheduler()
        actions = RecordingActions()
        machine = BackGestureMachine(prefs, clock, scheduler, actions)
    }

    /** A DOWN event whose downTime is the current fake-clock reading. */
    private fun pressDown() =
        KeyEvent(clock.now, clock.now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)

    /** An UP event for a press that went down at [downTime]. */
    private fun release(downTime: Long) =
        KeyEvent(downTime, clock.now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)
```

**Wichtig zu `DoubleTapDetector` im Test:** `DoubleTapDetector` liest die Zeit über
`SystemClock.uptimeMillis()` **direkt**, nicht über die injizierte `FakeClock`. Es hängt also
an Robolectrics Uhr, nicht an `clock.now`. Für die Doppeltipp-Tests heißt das: Robolectrics
Uhr muss zwischen den beiden Drücken mit vorrücken. Dafür in den betreffenden Tests
zusätzlich zur `FakeClock` die Robolectric-Uhr bewegen:

```kotlin
    private fun advanceBoth(ms: Long) {
        clock.now += ms
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(ms))
    }
```
`ShadowLooper.idleFor(Duration)` rückt in Robolectrics Standard-`PAUSED`-Looper-Modus sowohl
die Scheduler-Zeit als auch `SystemClock.uptimeMillis()` vor. Falls diese Methode in der
vorliegenden Robolectric-Version anders heißt, ist die Alternative
`org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(ms))` —
die exakte Signatur aus der Compiler-Fehlermeldung übernehmen, nicht die Testlogik ändern.

### 3.3 Die acht Testfälle

```kotlin
    @Test
    fun `a quick tap with double-tap enabled schedules singleTap instead of firing it`() {
        machine.handleBack(pressDown())
        machine.handleBack(release(startTime))
        assertEquals(0, actions.onSingleTapCalls)
        assertTrue(scheduler.pending.isNotEmpty())
    }

    @Test
    fun `letting the single-tap delay elapse with no second press fires onSingleTap once`() {
        machine.handleBack(pressDown())
        machine.handleBack(release(startTime))
        scheduler.fireAll()
        assertEquals(1, actions.onSingleTapCalls)
    }

    @Test
    fun `two quick presses within doubleTapMs open the switcher, never a single tap`() {
        prefs.doubleTapMs = 300L
        machine.handleBack(pressDown())
        machine.handleBack(release(startTime))
        advanceBoth(100)
        val secondDown = clock.now
        machine.handleBack(pressDown())
        machine.handleBack(release(secondDown))
        assertEquals(1, actions.showSwitcherCalls)
        assertEquals(0, actions.onSingleTapCalls)
        scheduler.fireAll()
        assertEquals(0, actions.onSingleTapCalls)
    }

    @Test
    fun `holding past holdMs swaps screens and the UP does not also fire tap logic`() {
        prefs.holdEnabled = true
        prefs.holdMs = 1000L
        machine.handleBack(pressDown())
        scheduler.fireDue(1000L)
        assertEquals(1, actions.swapScreensCalls)
        advanceBoth(1000)
        machine.handleBack(release(startTime))
        assertEquals(0, actions.onSingleTapCalls)
    }

    @Test
    fun `with hold disabled, nothing is scheduled at DOWN and UP still schedules a single tap`() {
        prefs.holdEnabled = false
        prefs.tapHoldEnabled = false
        machine.handleBack(pressDown())
        assertTrue(scheduler.pending.isEmpty())
        advanceBoth(2000)
        machine.handleBack(release(startTime))
        scheduler.fireAll()
        assertEquals(1, actions.onSingleTapCalls)
    }

    @Test
    fun `tap then hold again opens top recents instead of swapping screens`() {
        prefs.tapHoldEnabled = true
        prefs.tapHoldMs = 1000L
        machine.handleBack(pressDown())
        machine.handleBack(release(startTime))
        advanceBoth(100)
        machine.handleBack(pressDown())            // pressFollowsTap latches true here
        scheduler.fireDue(1000L)
        assertEquals(1, actions.openTopRecentsCalls)
        assertEquals(0, actions.swapScreensCalls)
    }

    @Test
    fun `a second press cancels the pending singleTap at DOWN, so it never fires mid-hold`() {
        // Regression guard for the pressFollowsTap latch-at-DOWN comment in the source.
        prefs.doubleTapEnabled = true
        prefs.tapHoldEnabled = true
        machine.handleBack(pressDown())
        machine.handleBack(release(startTime))
        advanceBoth(50)
        machine.handleBack(pressDown())
        scheduler.fireAll()
        assertEquals(0, actions.onSingleTapCalls)
    }

    @Test
    fun `with double-tap disabled, UP fires onSingleTap immediately and schedules nothing`() {
        prefs.doubleTapEnabled = false
        machine.handleBack(pressDown())
        machine.handleBack(release(startTime))
        assertEquals(1, actions.onSingleTapCalls)
        assertTrue(scheduler.pending.isEmpty())
    }
}
```

### 3.4 Wenn ein Test rot ist

Reihenfolge der Ursachenprüfung — **in dieser Reihenfolge**, nicht abkürzen:
1. **Startet die Uhr bei 0?** (PITFALLS P1) — `startTime` muss > 0 sein, und
   `advanceBoth` muss Robolectrics Uhr mit vorrücken, nicht nur `clock.now`.
2. **Trifft der Test eine falsche Annahme über die Logik?** `BackGestureMachine.kt` Zeile für
   Zeile gegen die Testannahme lesen. Wenn der Test falsch ist: Test korrigieren (das ist der
   „nachweislich fehlerhafte neue Test" aus der eisernen Regel 1).
3. **Hat Schritt 16 tatsächlich etwas verändert?** Wenn die Logik von der ursprünglichen
   `SwitcherService.handleBack` abweicht: **Schritt 16 ist fehlerhaft.** Zurück zu Schritt 16,
   Trace-Gegenprobe wiederholen, stoppen und melden. **Nicht** die Logik hier „passend machen".

## 4. Nicht anfassen

- `BackGestureMachine.kt` wird in diesem Schritt **nicht** verändert — reine Characterization
  der in Schritt 16 wortgetreu verschobenen Logik.
- Kein anderer Produktivcode.

## 5. Akzeptanzkriterien

- `./tools/verify.sh` grün.
- Alle 8 Tests grün:
  ```sh
  JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
    ./gradlew testDebugUnitTest --tests "*BackGestureMachineTest*" --console=plain
  ```
- Zweiter Lauf desselben Befehls mit `--rerun` ebenfalls grün (Determinismus).

## 6. Gate

`./tools/verify.sh`. Bei Rot: die Ursachenprüfung aus 3.4 abarbeiten. Führt sie zu Befund 3
(Schritt 16 hat Verhalten verändert): stoppen und melden, nicht selbst korrigieren.

## 7. Commit-Message-Vorschlag

```
test: characterize every branch of BackGestureMachine

Covers single tap, double tap, hold-to-swap, tap-then-hold-recents,
hold and double-tap disabled, and the pressFollowsTap latch-at-DOWN
edge case. The fake clock deliberately starts at 10s - a clock at 0
collides with DoubleTapDetector's last == 0L sentinel.
```
