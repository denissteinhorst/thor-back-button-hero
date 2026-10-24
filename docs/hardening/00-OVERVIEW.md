# Release-Hardening — Overview

## Ziel

`thor-back-button-hero` steht kurz vor dem ersten Release. Funktionen und Optik sind final,
validiert und **dürfen sich nicht mehr ändern** — kein Verhalten, kein Layout, kein Styling,
keine Texte, keine Animation, keine öffentliche API. Was fehlt, ist jedes Sicherheitsnetz:
kein Test, kein Pre-Commit-Hook, keine Coverage-Messung, CI prüft nur `assembleDebug` +
`lintDebug`. Diese Schrittliste friert den Ist-Zustand ein (Phase 1), erzwingt ihn dauerhaft
(Phase 2), räumt danach verhaltensneutral auf (Phase 3), optimiert nur mit Messbeleg
(Phase 4) und schließt mit einem Audit + Release-Checkliste (Phase 5).

Jeder Schritt ist für ein wörtlich ausführendes Modell geschrieben: konkrete Pfade, konkrete
Befehle, konkrete Codeskelette. Bei Mehrdeutigkeit: anhalten und den Nutzer fragen, nicht raten.

## Stack-Analyse

- Single-Module Android-App, Kotlin 1.9.24, AGP 8.5.2, Gradle 8.7 (Wrapper), JDK 17.
- `minSdk` 30, `compileSdk`/`targetSdk` 34. `applicationId` /
  `namespace` = `dev.steinhorst.backbuttonhero`. `versionCode = 1`, `versionName = "0.1.0"`.
- **Keine Dependencies** vor dieser Hardening-Arbeit — `app/build.gradle.kts` hatte keinen
  `dependencies`-Block. Kein XML-Layout, kein Compose, kein DI-Framework, keine Build-Variants
  außer debug/release.
- 1664 Zeilen Kotlin in 10 Dateien, ein einziges Package `dev.steinhorst.backbuttonhero`.
- CI: `.github/workflows/ci.yml` (push/PR auf `main`: `assembleDebug` + `lintDebug`),
  `.github/workflows/release.yml` (Tag `v*`: signiertes Release-APK → GitHub Releases).

### Toolchain-Besonderheiten — vor jedem Befehl lesen

- **JDK 17 ist nicht im `PATH`.** Ein nackter `java -version` schlägt fehl ("Unable to locate
  a Java Runtime"). Das JDK liegt unter
  `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` (Homebrew keg-only).
  **Bis Schritt 03 existiert**, muss jeder Gradle-Aufruf das selbst mitgeben:
  ```sh
  JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew <task> --console=plain
  ```
  **Ab Schritt 03 ist der einzige Gate-Befehl `./tools/verify.sh`** — das Skript löst
  `JAVA_HOME` selbst auf. Nie den nackten `./gradlew` als Gate aufrufen.
- **Android SDK**: nur `platforms/android-34`, `build-tools/34.0.0`, `platform-tools`.
  **Kein Emulator-Image, kein `emulator`-Binary.** Jeder `androidTest`/`connectedDebugAndroidTest`
  braucht ein angeschlossenes physisches Gerät.
- **Gerät**: ein AYN Thor (Android 13 / SDK 33) ist typischerweise per `adb` verbunden
  (ID z. B. `c4c5bc62` — mit `adb devices` neu prüfen, ID kann wechseln). Device-Schritte sind
  explizit als solche markiert und laufen **nicht** im automatischen Gate.
- `local.properties` zeigt auf `sdk.dir=/opt/homebrew/share/android-commandlinetools` — lokal,
  nicht committed, in CI existiert diese Datei nicht und wird von AGP automatisch ersetzt.

### Baseline (gemessen am 2026-10-24, vor jeder Hardening-Änderung)

`assembleDebug` + `lintDebug`: **BUILD SUCCESSFUL**, Lint **0 Errors, 15 Warnings**:

| Regel | Fundstelle(n) |
|---|---|
| `DataExtractionRules` | `AndroidManifest.xml:16` (`android:allowBackup="false"`) |
| `ObsoleteSdkInt` | `res/mipmap-anydpi-v26` (minSdk 30 macht `-v26` überflüssig) |
| `UnusedResources` | `R.string.status_title`, `R.string.features_title` |
| `IconColors` | 5× `ic_launcher_foreground.png` als Notification-Icon (nicht rein weiß) |
| `IconLauncherShape` | 5× `mipmap-*/ic_launcher.png` als Round-Icon ohne Kreisform |
| `MonochromeLauncherIcon` | `mipmap-anydpi-v26/ic_launcher.xml` fehlt `<monochrome>` |

**Diese 15 Warnungen sind der einzufrierende Ist-Zustand.** Sie werden in Schritt 24 per
Lint-Baseline-Datei eingefroren — **nicht behoben**, weil jeder Fix (z. B. ein neues
Notification-Icon) eine optische Änderung wäre.

## Feature- und Flow-Inventar (das zu Schützende)

**Back-Taste** — `SwitcherService.handleBack`, ein Zustandsautomat über DOWN/UP-Paare:
1. Einzeltipp → `GLOBAL_ACTION_BACK` *oder* Launcher des unteren Screens (`Prefs.singleAction`)
2. Doppeltipp (innerhalb `doubleTapMs`) → Switcher-Overlay auf dem unteren Screen
3. Halten für `holdMs` → Apps von Top- und Bottom-Screen tauschen (`swapScreens`)
4. Tippen, dann innerhalb `tapHoldMs` erneut halten → System-Recents auf dem oberen Screen
   statt Swap (`pressFollowsTap`-Logik — siehe Kommentar im Code, der beim Seam-Schritt 16
   wörtlich mitwandert)
5. Halten + L1/L2/R1/R2 → eine von 19 konfigurierbaren Aktionen, je mit wählbarem Ziel-Screen
6. Unbelegte Shoulder-Buttons: Event wird nicht konsumiert, App/Spiel sieht normalen Input

**Switcher-Overlay** — `SwitcherOverlay`: horizontale Scroll-Karten (Icon + Label +
Screenshot-Thumbnail), Tap öffnet die App, Swipe-up schließt sie (`CardTouch`-Gestenlogik),
„Close all"-Button mit „Both screens"-Toggle, Leerzustand „No recent apps", als
`TYPE_ACCESSIBILITY_OVERLAY`-Fenster fest an das untere Display gepinnt.

**MRU-App-Liste** — `AccessibilityAppSource`: rekonstruiert pro Display eine
Most-Recently-Used-Liste ausschließlich aus `windowsOnAllDisplays`, filtert ein festes
`ignored`-Set (Launcher/SystemUI/OEM-Pakete), cached Thumbnails separat im Speicher, erkennt
überdeckte Displays (`isCovered`).

**Settings-Bildschirm** — `SettingsActivity` + `SettingsUiKit`: 7 Karten-Sektionen (Single-Tap,
Double-Tap, Tap-Hold, Hold, 4× Shoulder-Button, Help/Debug, Reset, Update), 3
Toggle+Slider-Paare, 1 Single-Action-Chooser, je Shoulder-Button ein Action-Chooser plus
bedingt sichtbarer Screen-Toggle, Service-Health-Anzeige mit 3 Zuständen (ON/UNRESPONSIVE/OFF)
und „Repair service"-Flow, Reset-Bestätigungsdialog, Update-Check mit 3 Ergebnisdialogen,
Versionszeile, GitHub-Link. 6 `AlertDialog`-Flows insgesamt. Hell/Dunkel über
`Theme.DeviceDefault.DayNight`. Lokalisiert EN + DE (88 bzw. 87 Strings — `app_name` ist
`translatable="false"`, die Differenz von 1 ist korrekt, kein Übersetzungsfehler).

**Update-Check** — `UpdateChecker`: pollt die GitHub-Releases-API direkt per
`HttpURLConnection` + manuellem JSON-Parsing, erwartet Tags im Format `v<versionCode>`, postet
bei neuerer Version eine Notification mit Deep-Link zur Release-Seite. Bewusst kein
In-App-Download/-Install.

**Prefs-Migrationen** — `Prefs.init`: drei Einmal-Migrationen (`navigation_flick_defaults_v1`,
`navigation_flick_directions_v2`, Entfernung des veralteten `@swap_screens`-Sentinels).
Hochriskant für Bestandsinstallationen und bisher völlig ungetestet.

## Risiken

- **Timing-Logik ist nur auf echtem Dual-Screen-Gerät beobachtbar.** Kein Emulator-Image
  vorhanden. Deshalb: Golden-Logcat-Trace (Schritt 15) + manuelle Checkliste als Rückgrat,
  Unit-Tests erst nach der Seam-Extraktion (Schritt 16/17) möglich.
- **Git-Historie hat nur einen Commit, fast der gesamte Baum ist untracked.** Ohne
  Baseline-Commit (Schritt 01) gibt es kein `git restore`, auf das die Gate-Regel
  zurückfallen könnte — Schritt 01 ist deshalb nicht verschiebbar.
- **`SwitcherService.isConnected` hat `private set`** — der Service-Zustand `ON` ist aus
  einem Robolectric-Test nicht erreichbar (siehe `TEST-GAPS.md`).
- **Lizenz-/Dependency-Scope**: Test-Bibliotheken dürfen nur `test`/`androidTest`/`debug`
  sein, niemals `implementation`. Schritt 37 beweist das mechanisch über den
  Release-Klassenpfad.

## Entschiedene Leitplanken

| Thema | Entscheidung | Begründung |
|---|---|---|
| Dependencies | `test`/`androidTest`/`debug` erlaubt (siehe Schritt 02 für exakte Versionen) | Produktionscode bleibt dependency-frei; Lizenzen aller Kandidaten (JUnit EPL-1.0, Robolectric MIT, androidx.test/Espresso/UiAutomator Apache-2.0, JaCoCo EPL-2.0, LeakCanary Apache-2.0) stehen in keinem Konflikt zu 0BSD, weil keine davon im Release-APK landet |
| UI-Schutz | Primär deterministische View-Tree-Textsnapshots (Robolectric, plattformunabhängig); Roborazzi-Pixel-Goldens nur als expliziter **optionaler** Schritt 21, Aufnahme/Verifikation nur in Linux-CI | Pixel-Goldens sind zwischen macOS-lokal und Linux-CI wegen Font-Rendering nicht reproduzierbar — würden den Gate-Befehl unzuverlässig machen |
| Device-Tests | Automatisches Gate läuft komplett ohne Gerät (JVM); zwei zeitlich begrenzte Device-Schritte (15, 18) + eine schriftliche manuelle Checkliste | kein Emulator-Image verfügbar; das einzige Gerät ist das Alltagsgerät des Nutzers |
| Back-Automat | Eine einzige, wortwörtliche Extraktion (`BackGestureMachine`) am Ende von Phase 1 (Schritt 16), nicht erst in Phase 3 | ohne diese Extraktion bleibt der kritischste Code der App für den gesamten Rest des Plans ungetestet |

## Verworfene Optionen

- **Spotless/ktlint**: würde 1664 Zeilen konsistenten Single-Author-Code umformatieren →
  riesiger Diff ohne Mehrwert, verstößt gegen „keine Gelegenheitsverbesserungen".
- **`allWarningsAsErrors`**: würde dazu zwingen, die 15 bestehenden Lint-Warnungen durch
  `@Suppress`-Annotationen im Produktionscode zu unterdrücken statt einzufrieren. Stattdessen:
  Lint-Baseline-Datei + `warningsAsErrors` nur für *neue* Warnungen (Schritt 24).
- **Emulator in CI**: kein System-Image installiert; die Dual-Display-Realität des Thor ist
  ohnehin nicht emulierbar.
- **Husky/lint-staged**: kein Node.js-Projekt. Stattdessen reine Git-Hooks
  (`.githooks/pre-commit`, `.githooks/pre-push`) mit Gradle-Selbstinstallation (Schritt 22).

## Reihenfolge

Phase 0 (Baseline-Commit) → Phase 1 (Test-Suite, Schritte 02–21) → Phase 2 (Hooks & CI,
22–25) → Phase 3 (Architektur-Review, 26–30) → Phase 4 (Performance, 31–36) → Phase 5
(Abschluss, 37–38). Jede Phase setzt voraus, dass alle Schritte der vorherigen Phase grün
committed sind. Innerhalb einer Phase ist die Nummerierung die Abarbeitungsreihenfolge.

## Gate-Befehl

**Bis Schritt 03 existiert er noch nicht** — Schritt 01/02 verwenden die
`JAVA_HOME=...`-Langform direkt. **Ab Schritt 03:**

```sh
./tools/verify.sh
```

Das Skript löst `JAVA_HOME` selbst auf und ruft `./gradlew verify --console=plain` auf. Der
`verify`-Gradle-Task bündelt Lint, Unit-Tests, Coverage-Verifikation und beide Build-Varianten
(Debug + Release). Dieser eine Befehl ist ab Schritt 03 die Abnahme für *jeden* Schritt.

## Globale Regeln — gelten für jeden einzelnen Schritt

1. **Eiserne Regel: keine Änderung an Features, Optik oder Texten.** Wird ein Test nach
   einer Produktivcode-Änderung rot, ist die Änderung falsch, nicht der Test. Einzige
   Ausnahme: ein nachweislich fehlerhafter *neuer* Test aus demselben Schritt.
2. **Golden-Dateien/Snapshots nie neu aufnehmen, um einen roten Test grün zu machen.**
   Neuaufnahme nur mit ausdrücklicher Freigabe des Nutzers für eine beabsichtigte UI-Änderung
   — die in diesem gesamten Plan nicht vorkommt.
3. **Nach jedem Schritt läuft der Gate-Befehl.** Bei Rot: Schritt per `git restore .` (vor
   Commit) oder `git revert <sha>` (nach Commit) zurücknehmen, **stoppen und dem Nutzer
   melden**. Nicht weiterbasteln, nicht den nächsten Schritt versuchen.
4. **Ein Commit pro Schritt**, mit der in der jeweiligen Datei vorgeschlagenen Message.
   Hooks niemals mit `--no-verify` umgehen.
5. **Nur der im Schritt genannte Scope.** Keine Formatierungen, keine Umbenennungen, keine
   Refactorings außerhalb der genannten Aufgabe — auch wenn etwas Angrenzendes verbesserbar
   aussieht.
6. **A11y- und Lint-Befunde werden berichtet, nicht behoben.** Ein Fix wäre fast immer eine
   sichtbare Änderung (neues Icon, neue contentDescription-Logik) → verboten in diesem Plan.
7. **Phase 4 misst vor jeder Änderung.** Keine Optimierung ohne vorherige Baseline-Messung,
   keine Änderung ohne Re-Messung danach.
8. `docs/hardening/PROGRESS.md` nach jedem abgeschlossenen und committeten Schritt abhaken.

## Begleitdokumente

| Datei | Zweck | Wann lesen |
|---|---|---|
| `PITFALLS.md` | Konkrete technische Fallstricke dieser Codebasis (P1–P8), die mehrere Schritte betreffen und sonst zu rotem Gate mit nicht offensichtlicher Ursache führen | **Vor** den Schritten 02, 06, 07, 08, 11, 12, 13, 14, 16, 17, 19, 24, 31 — die jeweilige Schritt-Datei nennt die relevanten Punkte |
| `TEST-GAPS.md` | Was mit den erlaubten Mitteln bewusst **nicht** automatisiert testbar ist, und welcher Ersatzmechanismus greift | Bevor versucht wird, etwas zu testen, das sich hartnäckig nicht testen lässt |
| `MANUAL-GESTURE-CHECKLIST.md` | Die Geräte-Checkliste, die der **Nutzer** abarbeitet | Schritte 15, 16, 36 — und nach jeder Produktivcode-Änderung |
| `PROGRESS.md` | Checkliste aller 38 Schritte | Nach jedem abgeschlossenen Schritt abhaken |

## Bei Unklarheit

Wenn eine Aufgabe in einer Schritt-Datei mehrdeutig ist oder eine Entscheidung verlangt, die
nicht in der Datei steht: **anhalten, dem Nutzer die konkrete Frage stellen, nicht raten.**
Das gilt insbesondere für alles, was Verhalten, Optik oder Texte berühren könnte.
