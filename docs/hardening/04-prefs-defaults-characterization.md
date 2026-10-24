# Schritt 04 — Characterization: `Prefs` Defaults + Accessoren

## 1. Ziel

Jeden Default-Wert und jeden get/set-Accessor in `Prefs.kt` (außer den Init-Migrationen,
die Schritt 05 abdeckt) mit einem Characterization-Test festhalten.

## 2. Voraussetzungen

Schritt 03 ist committet und `./tools/verify.sh` läuft grün.

## 3. Exakte Aufgaben

Datei anlegen `app/src/test/java/dev/steinhorst/backbuttonhero/PrefsDefaultsTest.kt`.
Jeder Test verwendet einen frischen `Prefs`-Instanz auf einem frischen
`ApplicationProvider.getApplicationContext()` (Robolectric isoliert `SharedPreferences`
pro Test automatisch — keine manuelle Bereinigung nötig, aber zur Klarheit trotzdem in
`@Before` eine neue `Prefs`-Instanz erzeugen).

Abzudeckende Fälle (ein `@Test` je Zeile, benannt nach dem Muster
`` `doubleTapEnabled defaults to true` ``):

| Property | Erwarteter Default | Konstante |
|---|---|---|
| `doubleTapEnabled` | `true` | `Prefs.DEFAULT_DOUBLE_TAP_ENABLED` |
| `doubleTapMs` | `300L` | `Prefs.DEFAULT_DOUBLE_TAP_MS` |
| `holdEnabled` | `true` | `Prefs.DEFAULT_HOLD_ENABLED` |
| `holdMs` | `1000L` | `Prefs.DEFAULT_HOLD_MS` |
| `tapHoldEnabled` | `true` | `Prefs.DEFAULT_TAP_HOLD_ENABLED` |
| `tapHoldMs` | `1000L` | `Prefs.DEFAULT_TAP_HOLD_MS` |
| `singleAction` | `Prefs.SINGLE_LAUNCHER` | — |
| `singleLauncher` | `null`, weil `SINGLE_LAUNCHER` kein gültiges `ComponentName`-Flatten-Format ist | — |

Zusätzlich je ein Test, der setzt und zurückliest (Round-Trip), für:
- `doubleTapEnabled = false` → liest `false`
- `doubleTapMs = 450L` → liest `450L`
- `holdEnabled = false` → liest `false`
- `holdMs = 1500L` → liest `1500L`
- `tapHoldEnabled = false` → liest `false`
- `tapHoldMs = 1700L` → liest `1700L`
- `singleAction = Prefs.SINGLE_BACK` → liest `Prefs.SINGLE_BACK`
- `shoulderAction(ShoulderButton.L2)` ohne vorheriges Setzen → leerer String `""`
- `setShoulderAction(ShoulderButton.L2, "com.example.app")` → `shoulderAction(L2)` liest
  `"com.example.app"`
- `shoulderDisplay(ShoulderButton.R2)` ohne vorheriges Setzen → `Prefs.DISPLAY_BOTTOM`
  (weil R2 nicht zu den v1-Defaults mit vorbelegtem L1/R1 gehört — bewusst mit R2 testen,
  nicht mit L1, um den reinen Default ohne Migrationseinfluss zu sehen)
- `setShoulderDisplay(ShoulderButton.R2, Prefs.DISPLAY_TOP)` → liest `Prefs.DISPLAY_TOP`
- `Prefs.isShoulderApp("")` → `false`
- `Prefs.isShoulderApp("@home")` → `false`
- `Prefs.isShoulderApp("com.example.app")` → `true`

Jeder Test instanziiert `Prefs` einmal in `@Before`, liest/setzt und prüft mit `assertEquals`
bzw. `assertTrue`/`assertFalse`/`assertNull`.

## 4. Nicht anfassen

- `Prefs.kt` wird **nicht** verändert — reine Characterization, kein Produktivcode-Fix.
- `Prefs.init`-Migrationslogik wird hier nicht getestet (gehört zu Schritt 05); jeder Test in
  diesem Schritt arbeitet mit einer frischen, migrierten `Prefs`-Instanz und testet nur den
  Zustand *nach* der Erstinitialisierung.

## 5. Akzeptanzkriterien

- `./tools/verify.sh` grün.
- `JAVA_HOME=... ./gradlew testDebugUnitTest --tests "*PrefsDefaultsTest*" --console=plain`
  zeigt alle ~18 Tests grün, 0 übersprungen.

## 6. Gate

`./tools/verify.sh`. Bei Rot: `git restore .` auf die neue Testdatei beschränkt
(`git restore app/src/test/java/dev/steinhorst/backbuttonhero/PrefsDefaultsTest.kt`), löschen,
stoppen, melden.

## 7. Commit-Message-Vorschlag

```
test: characterize Prefs defaults and accessors

Locks in every default value and get/set round-trip in Prefs.kt so a
future change to a default is caught immediately.
```
