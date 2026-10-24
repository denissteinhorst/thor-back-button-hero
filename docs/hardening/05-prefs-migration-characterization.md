# Schritt 05 — Characterization: `Prefs`-Migrationen + `resetToDefaults()`

## 1. Ziel

Die drei Einmal-Migrationen in `Prefs.init` und `resetToDefaults()` gegen Regression
abzusichern — das ist der höchste Datenrisiko-Code im Projekt (betrifft Bestandsinstallationen).

## 2. Voraussetzungen

Schritt 04 ist committet und `./tools/verify.sh` läuft grün.

## 3. Exakte Aufgaben

Datei anlegen `app/src/test/java/dev/steinhorst/backbuttonhero/PrefsMigrationTest.kt`.
Jeder Test manipuliert die `SharedPreferences` **direkt** (nicht über `Prefs`), bevor er eine
`Prefs`-Instanz erzeugt, um einen vorherigen Installationszustand zu simulieren:

```kotlin
private fun rawPrefs(context: Context) =
    context.getSharedPreferences("back_button_hero", Context.MODE_PRIVATE)
```

Abzudeckende Fälle:

1. **Frische Installation** (keine Keys gesetzt) → nach `Prefs(context)`-Konstruktion:
   `rawPrefs.getBoolean("navigation_flick_defaults_v1", false)` ist `true`,
   `shoulderAction(L1) == Prefs.SHOULDER_FLICK_LEFT`,
   `shoulderAction(R1) == Prefs.SHOULDER_FLICK_RIGHT`,
   `rawPrefs.getBoolean("navigation_flick_directions_v2", false)` ist `true`.
2. **v1-Bestand ohne v2** — vor Konstruktion `rawPrefs.edit()
   .putBoolean("navigation_flick_defaults_v1", true)
   .putString("l1_package", Prefs.SHOULDER_FLICK_LEFT)
   .putString("r1_package", Prefs.SHOULDER_FLICK_RIGHT).apply()` (kein v2-Flag) →
   nach Konstruktion: `shoulderAction(L1) == Prefs.SHOULDER_FLICK_RIGHT` (vertauscht!),
   `shoulderAction(R1) == Prefs.SHOULDER_FLICK_LEFT` (vertauscht!),
   `rawPrefs.getBoolean("navigation_flick_directions_v2", false)` ist `true`.
3. **v1+v2 bereits vorhanden, Nutzer hat eigene Werte gesetzt** — vor Konstruktion beide
   Flags auf `true` setzen, `l1_package` auf `"com.custom.app"` setzen → nach Konstruktion:
   `shoulderAction(L1) == "com.custom.app"` (unverändert — Migration greift nicht erneut).
4. **`@swap_screens`-Altlast** — vor Konstruktion `rawPrefs.edit()
   .putString("l2_package", "@swap_screens").apply()` (plus v1/v2-Flags gesetzt, damit nur
   diese Migration greift) → nach Konstruktion: `shoulderAction(L2) == ""`.
5. **Zweite Konstruktion ist ein No-Op** — eine `Prefs`-Instanz erzeugen, dann auf demselben
   Context eine zweite erzeugen, dazwischen `singleAction = Prefs.SINGLE_BACK` setzen →
   nach der zweiten Konstruktion ist `singleAction` immer noch `Prefs.SINGLE_BACK`
   (keine Migration überschreibt einen expliziten Nutzerwert erneut).
6. **`resetToDefaults()`** — alle Properties auf Nicht-Default-Werte setzen (inkl.
   `setShoulderAction(L1, "com.other.app")`), dann `resetToDefaults()` aufrufen → jede
   Property entspricht wieder exakt der Default-Tabelle aus Schritt 04, **und**
   `shoulderAction(R2)` (das `resetToDefaults()` nicht explizit setzt) ist wieder `""`.

## 4. Nicht anfassen

- `Prefs.kt` bleibt unverändert.
- Kein Test darf den `init`-Block selbst verändern oder umgehen — jeder Test konstruiert
  `Prefs` ganz normal über den öffentlichen Konstruktor.

## 5. Akzeptanzkriterien

- `./tools/verify.sh` grün.
- Alle 6 Testfälle (ggf. mehr `@Test`-Methoden durch Aufspaltung) grün.

## 6. Gate

`./tools/verify.sh`. Bei Rot: nur die neue Testdatei zurücknehmen, stoppen, melden.

## 7. Commit-Message-Vorschlag

```
test: characterize Prefs one-time migrations and resetToDefaults

Covers fresh install, v1-without-v2, already-migrated, the retired
@swap_screens sentinel cleanup, migration idempotency, and reset.
```
