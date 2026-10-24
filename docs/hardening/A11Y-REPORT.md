# A11y-Inventar — Settings-Hauptseite

Erzeugt von `app/src/test/java/dev/steinhorst/backbuttonhero/AccessibilityInventoryTest.kt`,
eingefroren in `app/src/test/resources/golden/a11y_inventory.txt`. 24 klickbare Elemente
insgesamt (en-xxhdpi, Dichte 3.0, frischer Service-Zustand `OFF`).

**Dieser Report ist rein informativ — kein Fund wird in diesem Plan behoben** (globale
Regel 6 in `00-OVERVIEW.md`). Die Entscheidung, ob und wann etwas davon vor dem Release
behoben wird, liegt beim Nutzer.

## Methodik

Jede Row mit `minHeightDp < 48` (Android-Mindest-Touch-Target) oder `contentDescription=
(none)` bei einem Row-Typ **ohne** ohnehin sichtbaren Text wird unten aufgeführt.
`chooserRow`/`toggleRow`/`actionRow`/`linkRow`-Container selbst tragen immer sichtbaren
Titeltext und sind von der zweiten Regel ausgenommen — ein Screenreader liest diesen Text
vor, unabhängig vom `contentDescription`-Attribut. Ausgenommen sind davon die **inneren**
`Switch`-Widgets, die kein eigenes `contentDescription` und auch keinen eigenen sichtbaren
Text (`text=""`) haben.

## Funde

| # | Fund | Betroffene Elemente | Schweregrad | Referenz |
|---|---|---|---|---|
| 1 | `Switch`-Widgets sind unabhängig fokussierbar (`focusable=true`), tragen aber weder sichtbaren Text noch `contentDescription`. Navigiert ein Screenreader-Nutzer per Swipe durch einzelne fokussierbare Elemente statt per Touch-Exploration, kann der Switch isoliert als bloßes „Switch" ohne Kontext vorgelesen werden, getrennt vom Titel der umgebenden Row. | Alle 7 `Switch`-Instanzen: Double-Tap-, Tap-Hold-, Hold-Enable-Toggles sowie die vier Shoulder-Button-„Use top screen"-Toggles | Informativ — die Row selbst (die denselben Toggle auslöst) trägt den Titel; Touch-Exploration liest die Row korrekt vor. Nur der reine Swipe-Navigationspfad ist betroffen. | `SettingsUiKit.kt:138` (`switch = Switch(activity).apply { ... }`, keine `contentDescription` gesetzt) |
| 2 | `view.minimumHeight` ist für jeden `Switch` `0` (die Zeile "minHeightDp=0" in der Golden-Datei). Das native `Switch`-Widget hat laut Android-Framework ein eigenes intrinsisches Mindest-Touch-Target, das nicht über die `minimumHeight`-Property abgebildet wird — der gemessene Wert von 0 ist ein Artefakt dieser Messmethode, nicht notwendigerweise ein echtes 0dp-Touch-Target. Zusätzlich liegt jeder Switch innerhalb einer 56–72dp hohen Row mit identischer Klick-Aktion (`row.setOnClickListener { switch.toggle() }`), die den Switch selbst für die Touch-Bedienung redundant macht. | Dieselben 7 `Switch`-Instanzen wie Fund 1 | Informativ, voraussichtlich kein echter Mangel — Messmethode-Artefakt, nicht bestätigt ohne manuelle Device-Prüfung. | `SettingsUiKit.kt:131–150` (`toggleRow`) |

## Keine Funde bei

- Alle 4 `chooserRow`-Instanzen für L1/R1/L2/R2 tragen bereits ein korrekt befülltes
  `contentDescription` (`shoulder_action_accessibility`-String, z. B. „L1 action:
  Navigation-Flick Left. Double-tap to change.") — siehe `SettingsActivity.kt`,
  `updateShoulderRow()`.
- `linkRow` für „GitHub repository" trägt `contentDescription="Open GitHub repository"`.
- Jede `LinearLayout`-Row (Container von `chooserRow`/`toggleRow`/`actionRow`/`linkRow`) hat
  `minHeightDp` ≥ 48 (56, 72, oder 48 je nach Row-Typ) — keine Verletzung des
  Android-Mindest-Touch-Targets auf Row-Ebene.
- `warningRow` (Service-Status-Zeile) ist im getesteten Zustand (frischer Service, `OFF`)
  nicht klickbar (`onClick` nicht übergeben in `SettingsActivity.build()`) und erscheint
  deshalb korrekt nicht im Inventar.
