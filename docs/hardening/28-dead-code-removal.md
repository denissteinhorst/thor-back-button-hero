# Schritt 28 — Toter Code entfernen

## 1. Ziel

Die drei konkret belegten ungenutzten Fundstellen entfernen: `SettingsPage.Card.setVisible`,
`R.string.status_title`, `R.string.features_title` (inkl. ihrer DE-Pendants).

## 2. Voraussetzungen

Schritt 27 ist committet und `./tools/verify.sh` läuft grün.

## 3. Exakte Aufgaben

1. **Vor jeder Löschung den Fund erneut belegen** (er könnte sich seit der Discovery
   geändert haben):
   ```sh
   grep -rn "setVisible" app/src/main/java
   grep -rn "status_title" app/src/main
   grep -rn "features_title" app/src/main
   ```
   Erwartet: `setVisible` nur in der Definition in `SettingsUiKit.kt`, keine Aufrufstelle.
   `status_title`/`features_title` nur in `values/strings.xml` und `values-de/strings.xml`,
   keine `R.string.status_title`/`R.string.features_title`-Referenz in `.kt`-Dateien.
   Zeigt der `grep` eine Aufrufstelle, die bei der Discovery nicht gesehen wurde: **stoppen,
   nicht löschen, dem Nutzer den Fund melden.**
2. In `app/src/main/java/dev/steinhorst/backbuttonhero/SettingsUiKit.kt` die Methode
   ```kotlin
   fun setVisible(row: View, visible: Boolean) {
       row.visibility = if (visible) View.VISIBLE else View.GONE
   }
   ```
   vollständig entfernen (liegt in `inner class Card`).
3. In `app/src/main/res/values/strings.xml` die Zeile
   ```xml
   <string name="status_title">Service</string>
   ```
   entfernen.
4. In `app/src/main/res/values/strings.xml` die Zeile
   ```xml
   <string name="features_title">Back button</string>
   ```
   entfernen.
5. In `app/src/main/res/values-de/strings.xml` die entsprechenden DE-Einträge für
   `status_title` und `features_title` suchen und ebenfalls entfernen (nur falls vorhanden —
   mit `grep -n "status_title\|features_title" app/src/main/res/values-de/strings.xml`
   vorher prüfen).
6. Schritt 09's `ResourceIntegrityTest` erneut laufen lassen — die DE/EN-Paritätsprüfung muss
   nach dieser Löschung weiterhin grün sein, weil auf beiden Seiten symmetrisch entfernt
   wurde.
7. Golden-Dateien aus Schritt 11–14 erneut (**ohne** `UPDATE_GOLDENS`) laufen lassen — diese
   beiden Strings werden in keinem der gesnapshoteten Screens angezeigt (sie sind laut
   Lint bereits als `UnusedResources` markiert), die Snapshots dürfen sich **nicht** ändern.
   Ändert sich einer: stoppen, nicht die Golden-Datei neu aufnehmen, sondern prüfen, ob der
   String doch irgendwo gebraucht wurde (Widerspruch zu Aufgabe 1).

## 4. Nicht anfassen

- Keine weiteren „aufräumbar aussehenden" Stellen in `SettingsUiKit.kt` oder `strings.xml`
  anfassen — nur die drei belegten Funde aus diesem Schritt.
- Lint-Baseline (`app/lint-baseline.xml`) nach dieser Löschung **muss** sich verändern (die
  beiden `UnusedResources`-Einträge verschwinden aus der Lint-Ausgabe, weil die Ressourcen
  nicht mehr existieren) — das ist erwartet. Baseline-Datei neu generieren wie in Schritt 24
  beschrieben (`lintDebug` laufen lassen, AGP schreibt die Baseline neu), danach prüfen:
  jetzt exakt 13 Fundstellen statt 15.

## 5. Akzeptanzkriterien

- `./tools/verify.sh` grün.
- `grep -rn "setVisible\|status_title\|features_title" app/src/main` liefert keinen
  Treffer mehr.
- `app/lint-baseline.xml` enthält nach Neugenerierung exakt 13 Fundstellen (15 minus die 2
  `UnusedResources`-Einträge).
- Alle bestehenden Snapshot-Tests weiterhin grün ohne Neuaufnahme.

## 6. Gate

`./tools/verify.sh`. Bei Rot (insbesondere falls ein Snapshot sich ändert): stoppen, nicht
die Golden-Datei neu aufnehmen, melden.

## 7. Commit-Message-Vorschlag

```
refactor: remove unused setVisible helper and two unused string resources

setVisible had no call site. status_title and features_title (plus
their DE counterparts) were flagged UnusedResources since the Phase 0
baseline. Lint baseline regenerated: 15 -> 13 entries.
```
