# Schritt 16 — Seam: `BackGestureMachine`-Extraktion

## 1. Ziel

Die Entscheidungslogik aus `SwitcherService.handleBack` **wortwörtlich**, ohne eine einzige
Verhaltensänderung, in eine eigenständige, unit-testbare Klasse `BackGestureMachine`
verschieben, indem `SystemClock`/`Handler`/die aufrufenden Methoden durch drei kleine
Interfaces ersetzt werden. Dies ist die einzige Produktivcode-Änderung in Phase 1.

**Dies ist der komplexeste Schritt des ganzen Plans.** Er berührt vier Stellen in
`SwitcherService.kt`, die leicht übersehen werden. Die Abschnitte 3.1–3.6 unten in genau
dieser Reihenfolge abarbeiten und keine überspringen.

## 2. Voraussetzungen

Schritt 15 ist committet, `docs/hardening/baseline/gesture-trace.txt` existiert, und
`./tools/verify.sh` läuft grün.

Vorher lesen: `docs/hardening/PITFALLS.md`.

## 3. Exakte Aufgaben

### 3.1 Bestandsaufnahme — was genau in `SwitcherService.kt` angefasst wird

Vor dem Beginn diese Zeilen in `app/src/main/java/dev/steinhorst/backbuttonhero/SwitcherService.kt`
nachschlagen, damit nichts vergessen wird:
```sh
grep -n "backDown\|comboFired\|holdFired\|tapHoldFired\|firstDownAt\|pressFollowsTap\|holdRunnable\|tapHoldRunnable\|singleTap\|detector\|handleBack\|singleTapDelay" \
  app/src/main/java/dev/steinhorst/backbuttonhero/SwitcherService.kt
```
Es gibt **vier** Stellen, die den Zustand des Automaten mitbenutzen und deshalb mitangepasst
werden müssen:
1. `onKeyEvent` → ruft `handleBack(event)` auf
2. `handleShoulder` → liest `backDown` (die erste Zeile: `if (!backDown) return false`)
3. `handleShoulder` → schreibt `comboFired = true` und entfernt `holdRunnable`/`tapHoldRunnable`
4. `onDestroy` → `handler.removeCallbacksAndMessages(null)` (bleibt unverändert, entfernt
   weiterhin alle Callbacks des gemeinsam genutzten `handler`)

### 3.2 Die drei Seam-Interfaces

Datei anlegen `app/src/main/java/dev/steinhorst/backbuttonhero/BackGestureMachine.kt`:

```kotlin
package dev.steinhorst.backbuttonhero

/** Replaces SystemClock.uptimeMillis() so tests can control time. */
interface GestureClock {
    fun uptimeMillis(): Long
}

/** Replaces Handler.postDelayed/removeCallbacks/hasCallbacks so tests can control scheduling. */
interface GestureScheduler {
    fun postDelayed(runnable: Runnable, delayMs: Long)
    fun removeCallbacks(runnable: Runnable)
    fun hasCallbacks(runnable: Runnable): Boolean
}

/** The side effects a completed gesture triggers - implemented by SwitcherService. */
interface GestureActions {
    fun swapScreens()
    fun openTopRecents()
    fun showSwitcher()
    fun onSingleTap()
}
```

### 3.3 `BackGestureMachine` — Gerüst mit den exponierten Zuständen

Im selben File, nach den Interfaces:

```kotlin
class BackGestureMachine(
    private val prefs: Prefs,
    private val clock: GestureClock,
    private val scheduler: GestureScheduler,
    private val actions: GestureActions,
    private val dlog: (String) -> Unit = {},
) {
    /** True while the Back key is physically held - read by SwitcherService.handleShoulder. */
    var backDown = false
        private set

    /** Set when a Back+shoulder combo ran, so the eventual UP does not also fire tap logic. */
    var comboFired = false
        private set

    private var holdFired = false
    private var tapHoldFired = false
    private var firstDownAt = 0L

    // Captured once at this press's DOWN, not recomputed at UP: singleTap may have already
    // fired on its own by the time a long-held second press is released, which would make a
    // hasCallbacks() check at UP time wrongly say "no tap was pending".
    private var pressFollowsTap = false

    private val holdRunnable = Runnable {
        holdFired = true
        dlog("RUNNABLE holdRunnable fired -> swapScreens()")
        actions.swapScreens()
    }
    private val tapHoldRunnable = Runnable {
        tapHoldFired = true
        dlog("RUNNABLE tapHoldRunnable fired -> openTopRecents()")
        actions.openTopRecents()
    }
    private val singleTap = Runnable {
        dlog("RUNNABLE singleTap fired -> onSingleTap()")
        actions.onSingleTap()
    }
    private val detector = DoubleTapDetector({ prefs.doubleTapMs }) {
        dlog("DETECTOR double-tap recognized -> showSwitcher()")
        scheduler.removeCallbacks(singleTap)
        actions.showSwitcher()
    }

    /** Called by SwitcherService.handleShoulder when a Back+shoulder combo fires. Mirrors the
     *  three statements that used to live inline there. */
    fun markComboFired() {
        comboFired = true
        scheduler.removeCallbacks(holdRunnable)
        scheduler.removeCallbacks(tapHoldRunnable)
    }

    // handleBack(event) and singleTapDelay() follow - see 3.4
}
```

**Wichtig zu `backDown` und `comboFired`:** beide sind `var ... private set` mit einem echten
Backing-Field — eine öffentliche Lesbarkeit bei privatem Schreibzugriff. Das ist valides
Kotlin. Schreibe **nicht** einen eigenen Getter (`get() = ...`) dafür — eine Property mit
custom Getter kann kein `private set` haben und würde nicht kompilieren.

### 3.4 `handleBack` und `singleTapDelay` wortwörtlich übernehmen

Die beiden Methoden aus `SwitcherService.kt` **Zeile für Zeile** in `BackGestureMachine`
kopieren. Dabei genau diese und **nur diese** mechanischen Ersetzungen vornehmen:

| Im Original | In `BackGestureMachine` |
|---|---|
| `SystemClock.uptimeMillis()` | `clock.uptimeMillis()` |
| `handler.postDelayed(x, y)` | `scheduler.postDelayed(x, y)` |
| `handler.removeCallbacks(x)` | `scheduler.removeCallbacks(x)` |
| `handler.hasCallbacks(x)` | `scheduler.hasCallbacks(x)` |
| `dlog("...")` | `dlog("...")` — unverändert, trifft jetzt das injizierte Lambda |
| `prefs.doubleTapMs` usw. | unverändert |
| `onSingleTap()` | `actions.onSingleTap()` |
| `detector.onPress()` | unverändert |

**Verboten:** `when`-Zweige umsortieren, Bedingungen vereinfachen, lokale Variablen
(`now`, `sinceFirstDown`, `heldFor`, `scheduled`, `delay`) umbenennen, `dlog`-Strings ändern
(der Golden-Trace aus Schritt 15 vergleicht genau diese Strings!), oder Zeilen
zusammenfassen. Die drei mehrzeiligen Erklärkommentare in `handleBack` (über
`pressFollowsTap` am DOWN, über das Abbrechen bei DOWN statt UP, über die gestreckte
`singleTapDelay`) wandern **wortgleich** mit.

Importe, die `BackGestureMachine.kt` dafür braucht: `android.view.KeyEvent`.
`android.os.SystemClock` wird dort **nicht** mehr gebraucht (die Uhr kommt über `clock`).

### 3.5 `SwitcherService.kt` umbauen

1. Klassendeklaration erweitern:
   ```kotlin
   class SwitcherService : AccessibilityService(), GestureActions {
   ```
2. **Die vier Aktionsmethoden von `private` auf `override` umstellen** — eine
   Interface-Implementierung kann nicht `private` sein:
   ```kotlin
   override fun swapScreens() { /* Rumpf unverändert */ }
   override fun openTopRecents() { /* Rumpf unverändert */ }
   override fun showSwitcher() { /* Rumpf unverändert */ }
   override fun onSingleTap() { /* Rumpf unverändert */ }
   ```
   Nur das Schlüsselwort `private fun` → `override fun` ändern. **Kein Rumpf wird angefasst.**
   (Das weitet die Sichtbarkeit von privat auf öffentlich — die einzige Sichtbarkeitsänderung
   in diesem Schritt und unvermeidlich für das Interface.)
3. Die sechs Zustandsfelder, die drei `Runnable`-Felder, das `detector`-Feld, `handleBack`
   und `singleTapDelay` aus `SwitcherService.kt` **löschen** (sie leben jetzt in
   `BackGestureMachine`). `handler` bleibt — er wird weiterhin von `captureRunnable`,
   `killOnly` und `navigationFlick` gebraucht.
4. Adapter und Maschine ergänzen:
   ```kotlin
   private val gestureClock = object : GestureClock {
       override fun uptimeMillis() = SystemClock.uptimeMillis()
   }
   private val gestureScheduler = object : GestureScheduler {
       override fun postDelayed(runnable: Runnable, delayMs: Long) {
           handler.postDelayed(runnable, delayMs)
       }
       override fun removeCallbacks(runnable: Runnable) = handler.removeCallbacks(runnable)
       override fun hasCallbacks(runnable: Runnable) = handler.hasCallbacks(runnable)
   }
   private val backGesture by lazy {
       BackGestureMachine(prefs, gestureClock, gestureScheduler, this, ::dlog)
   }
   ```
   **Hinweis zu `postDelayed`:** `Handler.postDelayed` gibt `Boolean` zurück, das
   Interface deklariert `Unit`. Deshalb muss der Rumpf in `{ }`-Blockform stehen (wie oben),
   nicht als `= handler.postDelayed(...)` — sonst Typfehler.
5. `onKeyEvent` anpassen:
   ```kotlin
   if (event.keyCode == KeyEvent.KEYCODE_BACK) return backGesture.handleBack(event)
   ```
6. In `handleShoulder` die erste Zeile anpassen:
   ```kotlin
   if (!backGesture.backDown) return false
   ```
7. In `handleShoulder` die drei Zeilen
   ```kotlin
   comboFired = true
   handler.removeCallbacks(holdRunnable)
   handler.removeCallbacks(tapHoldRunnable)
   ```
   durch **eine** Zeile ersetzen:
   ```kotlin
   backGesture.markComboFired()
   ```

### 3.6 Kompilieren und Importe bereinigen

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :app:compileDebugKotlin --console=plain
```
Meldet der Compiler ungenutzte Importe in `SwitcherService.kt` (z. B. `android.os.SystemClock`
wird noch für `gestureClock` und `captureFrontApp`s `lastCapture` gebraucht — also
wahrscheinlich **kein** Import entfällt): nur tatsächlich ungenutzte entfernen, nichts auf
Verdacht.

## 4. Nicht anfassen

- Keine Zeile in `Prefs.kt`, `DoubleTapDetector.kt`, `SwitcherOverlay.kt`,
  `AccessibilityAppSource.kt`, `SettingsActivity.kt`, `SettingsUiKit.kt`, `UpdateChecker.kt`.
- Keine Umformung der `when`-Logik in `handleBack`.
- Keine Änderung an einem `dlog`-String (der Golden-Trace vergleicht sie wörtlich).
- Keine Änderung an `DEBUG_GESTURES` (bleibt `false` im committeten Zustand).
- `onDestroy`s `handler.removeCallbacksAndMessages(null)` bleibt wie es ist — es räumt
  weiterhin alle Callbacks auf, auch die von `BackGestureMachine` über denselben `handler`
  geposteten.

## 5. Akzeptanzkriterien

1. `./tools/verify.sh` grün.
2. `grep -c "handleBack" app/src/main/java/dev/steinhorst/backbuttonhero/SwitcherService.kt`
   liefert `1` (nur noch der delegierende Aufruf in `onKeyEvent`).
3. **Trace-Gegenprobe auf dem Gerät** (das eigentliche Akzeptanzkriterium, nicht „Tests grün"):
   `DEBUG_GESTURES` temporär auf `true`, `installDebug`, die Back-Sektion aus
   `MANUAL-GESTURE-CHECKLIST.md` in derselben Reihenfolge wie in Schritt 15 durchgehen, dann:
   ```sh
   adb logcat -c
   adb logcat -s BackButtonHero:D > /tmp/gesture-trace-new-raw.txt &
   # Nutzer führt die Back-Sektion der Checkliste durch, danach den logcat beenden
   grep -oE 'BackButtonHero: .*' /tmp/gesture-trace-new-raw.txt > /tmp/gesture-trace-new.txt
   diff docs/hardening/baseline/gesture-trace.txt /tmp/gesture-trace-new.txt
   ```
   Der `diff` muss leer sein. Danach `DEBUG_GESTURES` zurück auf `false` **und neu
   installieren**.
   *Hinweis:* kleine Abweichungen in den `+Nms since previous DOWN`-Zahlen sind unvermeidlich,
   weil der Nutzer nicht millisekundengenau gleich tippt. **Relevant ist die Reihenfolge und
   Art der Entscheidungen** (welche `RUNNABLE`/`DETECTOR`/`ACTION`-Zeilen in welcher Folge
   erscheinen), nicht die absoluten Millisekundenwerte. Weicht nur eine Zahl ab: in Ordnung.
   Weicht eine Entscheidungszeile ab, fehlt eine oder kommt eine hinzu: **Fehler**.
4. Nutzer arbeitet `MANUAL-GESTURE-CHECKLIST.md` **vollständig** ab (auch die
   Shoulder-Button-Sektion, da `handleShoulder` mitangepasst wurde) und bestätigt keine
   Abweichung.

## 6. Gate

`./tools/verify.sh` **plus** Kriterium 3 **plus** Kriterium 4. Bei Rot in irgendeinem der drei:
```sh
git restore app/src/main/java/dev/steinhorst/backbuttonhero/SwitcherService.kt
rm app/src/main/java/dev/steinhorst/backbuttonhero/BackGestureMachine.kt
```
**stoppen und exakt melden, welcher der drei Checks fehlgeschlagen ist** — nicht weiterbasteln,
auch nicht bei einem scheinbar kleinen Trace-Diff.

## 7. Commit-Message-Vorschlag

```
refactor: extract Back-key decision logic into BackGestureMachine

Line-for-line move of SwitcherService.handleBack into a standalone
class with injected clock, scheduler, and actions. The four action
methods become override (private -> public) to satisfy GestureActions;
handleShoulder now reads backDown and calls markComboFired() instead
of touching the machine's fields directly.

No behavior change: verified by an identical gesture-decision trace
and the full manual gesture checklist. This is the seam that makes
the timing logic unit-testable in step 17.
```
