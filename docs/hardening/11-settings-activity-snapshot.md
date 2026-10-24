# Schritt 11 — Snapshot: `SettingsActivity` Hauptseite

## 1. Ziel

Die gesamte Hauptseite von `SettingsActivity` (alle 7 Karten-Sektionen, Reihenfolge,
Texte, Sichtbarkeiten) für Hell/Dunkel × EN/DE × die beiden erreichbaren Service-Status
als View-Tree-Snapshot einfrieren.

## 2. Voraussetzungen

Schritt 10 ist committet und `./tools/verify.sh` läuft grün.

**Vorher lesen: `docs/hardening/PITFALLS.md`, insbesondere P2** (Netzwerk-Thread in
`onCreate` — bekannt und akzeptiert), **P6** (Prüfpflicht beim dunklen Theme) und **P7**
(Arbeitsverzeichnis der Tests ist `app/`).

## 3. Exakte Aufgaben

Datei anlegen
`app/src/test/java/dev/steinhorst/backbuttonhero/SettingsActivitySnapshotTest.kt`:

```kotlin
package dev.steinhorst.backbuttonhero

import dev.steinhorst.backbuttonhero.support.Golden
import dev.steinhorst.backbuttonhero.support.ViewTree
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class SettingsActivitySnapshotTest {

    private fun snapshot(name: String) {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val root = activity.findViewById<android.view.View>(android.R.id.content)
        Golden.verify(name, ViewTree.describe(root))
    }

    @Config(qualifiers = "en-xxhdpi")
    @Test
    fun `main page in English, light theme`() = snapshot("settings_main_en_light")

    @Config(qualifiers = "en-xxhdpi-night")
    @Test
    fun `main page in English, dark theme`() = snapshot("settings_main_en_dark")

    @Config(qualifiers = "de-xxhdpi")
    @Test
    fun `main page in German, light theme`() = snapshot("settings_main_de_light")
}
```

Golden-Dateien aufnehmen (erstmalig — das ist keine Regel-2-Verletzung, da hier keine roten
Tests grün gemacht werden, sondern eine erste Baseline für neue Tests entsteht):
```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  UPDATE_GOLDENS=1 ./gradlew testDebugUnitTest --tests "*SettingsActivitySnapshotTest*" --console=plain
```
Danach **die drei entstandenen `.txt`-Dateien unter `app/src/test/resources/golden/`
öffnen und manuell gegen die Feature-Beschreibung in `00-OVERVIEW.md` prüfen** — sind alle
7 Sektionstitel, alle sichtbaren Zeilen und keine offensichtlich falschen Werte drin? Erst
dann erneut **ohne** `UPDATE_GOLDENS` laufen lassen, um zu bestätigen, dass der Vergleich
jetzt grün ist.

**Pflichtprüfung Dunkelmodus** (`PITFALLS.md` P6):
```sh
diff app/src/test/resources/golden/settings_main_en_light.txt \
     app/src/test/resources/golden/settings_main_en_dark.txt
```
Ist dieser Diff **leer**, hat der `night`-Qualifier nicht gegriffen — der dunkle Snapshot
schützt dann nichts und darf nicht als Erfolg verbucht werden. In dem Fall: **stoppen und dem
Nutzer melden**, dass `Theme.DeviceDefault.DayNight` unter Robolectric nicht umschaltet und
der Dunkelmodus-Schutz damit nur über `MANUAL-GESTURE-CHECKLIST.md` läuft. Die beiden
identischen Goldens nicht committen; stattdessen den `_dark`-Test entfernen und den Befund in
`TEST-GAPS.md` ergänzen.

## 4. Nicht anfassen

- Kein Produktivcode.
- `UPDATE_GOLDENS=1` nach diesem Schritt nicht mehr setzen, außer bei einer vom Nutzer
  ausdrücklich freigegebenen UI-Änderung.

## 5. Akzeptanzkriterien

- `./tools/verify.sh` grün (ohne `UPDATE_GOLDENS`).
- Drei Golden-Dateien existieren unter `app/src/test/resources/golden/` und sind committet.
- Ein manueller zweiter Lauf mit demselben Befehl (ohne `UPDATE_GOLDENS`) ist ebenfalls grün
  — beweist Determinismus.

## 6. Gate

`./tools/verify.sh`. Bei Rot (z. B. weil der `de-xxhdpi`-Qualifier auf dieser
Robolectric-Version einen Ressourcen-Fallback-Fehler wirft): die exakte Fehlermeldung lesen,
bei einem reinen Qualifier-Problem den Qualifier-String anpassen (z. B. `"de"` statt
`"de-xxhdpi"`), bei allem anderen stoppen und melden.

## 7. Commit-Message-Vorschlag

```
test: snapshot the SettingsActivity main page across theme and locale

Freezes layout, text, and visibility of all seven sections for
en-light, en-dark, and de-light via deterministic View-tree goldens.
```
