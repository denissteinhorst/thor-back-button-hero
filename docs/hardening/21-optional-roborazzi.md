# Schritt 21 — Optional: Roborazzi-Pixel-Goldens

## 1. Ziel

Zusätzlich zu den View-Tree-Textsnapshots (Schritte 10–13) echte Pixel-Goldens für die
Settings-Hauptseite anlegen, verifiziert ausschließlich in der Linux-CI.

## 2. Empfehlung: diesen Schritt überspringen

**Standardentscheidung ist „überspringen".** Begründung:

- Der UI-Schutz steht bereits: die Schritte 11–13 frieren Layout, Sichtbarkeiten,
  Textgrößen, Farben, Paddings und alle Texte deterministisch ein. Pixel-Goldens fügen nur
  das hinzu, was dort nicht erfasst ist — im Wesentlichen Drawable-Rendering (Corner-Radius
  eines `GradientDrawable`, Switch-Tint).
- Der Preis ist hoch: ein zusätzliches Gradle-Plugin, ein Test-Pfad, der lokal auf macOS
  systematisch andere Pixel produziert als die Linux-CI, und damit ein Mechanismus, der
  entweder nur in CI sinnvoll läuft oder lokal dauernd unecht rot ist.
- Diese App hat eine einzige, handgebaute Settings-Seite. Das Verhältnis von Aufwand und
  Risiko zu zusätzlichem Schutz ist schlecht.

**Vorgehen:** In `docs/hardening/PROGRESS.md` bei Schritt 21 eintragen
`[x] 21 — übersprungen (Standardentscheidung, siehe 21-optional-roborazzi.md)` und direkt mit
Schritt 22 weitermachen. Kein Commit nötig außer der `PROGRESS.md`-Zeile.

## 3. Exakte Aufgaben — nur wenn der Nutzer den Schritt ausdrücklich verlangt

Falls der Nutzer Pixel-Goldens explizit möchte, **zuerst zurückfragen** und nicht auf Verdacht
umsetzen — denn die folgende Umsetzung hängt von Versionsdetails ab, die vor Ort geprüft
werden müssen:

1. Aktuelle Roborazzi-Version ermitteln, die zu Robolectric 4.12.2 und AGP 8.5.2 passt
   (Roborazzi-Releases nennen die kompatible Robolectric-Version in den Release Notes).
   Version **nicht** raten.
2. Plugin und Dependency in `app/build.gradle.kts` ergänzen, mit der in 1 ermittelten
   Version. Das `-compose`-Artefakt wird **nicht** gebraucht (diese App nutzt kein Compose).
3. Eine eigene Testklasse `SettingsActivityPixelSnapshotTest.kt` anlegen, die
   `captureRoboImage(...)` auf `android.R.id.content` aufruft. Getrennt von den bestehenden
   Snapshot-Tests, damit der Pfad unabhängig abschaltbar bleibt.
4. In `app/build.gradle.kts` dafür sorgen, dass diese Klasse vom normalen
   `testDebugUnitTest`-Lauf **ausgeschlossen** ist, damit `./tools/verify.sh` lokal
   unberührt bleibt:
   ```kotlin
   tasks.withType<Test>().configureEach {
       if (System.getenv("ROBORAZZI") != "1") {
           exclude("**/SettingsActivityPixelSnapshotTest*")
       }
   }
   ```
5. In `.github/workflows/ci.yml` einen **separaten** Job `pixel-snapshots` auf
   `ubuntu-latest` ergänzen, der mit `ROBORAZZI=1` genau diese Klasse im
   Verifikationsmodus ausführt. Die Goldens werden **in der CI aufgenommen** (einmalig, per
   manuell ausgelöstem Lauf mit Record-Modus), als Artefakt heruntergeladen und committet —
   nie lokal auf macOS aufgenommen.

## 4. Nicht anfassen

- `./tools/verify.sh` darf durch diesen Schritt weder langsamer noch instabiler werden.
- Keine View-Tree-Golden-Datei aus Schritt 10–13 ersetzen oder löschen.

## 5. Akzeptanzkriterien

**Bei Überspringen:** `PROGRESS.md` enthält den Eintrag mit Begründung. Fertig.

**Bei Umsetzung:** `./tools/verify.sh` lokal grün und in der Laufzeit unverändert; der
separate CI-Job grün gegen committete PNGs; ein lokaler Lauf ohne `ROBORAZZI=1` führt die
Pixel-Klasse nicht aus.

## 6. Gate

`./tools/verify.sh`. Bei Unsicherheit über Versionen oder Mechanik: **überspringen** statt
einen instabilen Mechanismus einzuchecken. Ein unzuverlässiger Gate-Befehl ist schädlicher
als ein fehlender optionaler Zusatztest.

## 7. Commit-Message-Vorschlag

Bei Überspringen:
```
docs: skip optional Roborazzi pixel snapshots

View-tree snapshots in steps 11-13 already freeze layout, styling, and
text deterministically. Pixel goldens would add cross-platform
rendering drift for marginal extra coverage on a single hand-built
screen.
```
