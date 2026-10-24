# Schritt 30 — Doku-Update

## 1. Ziel

Eine `ARCHITECTURE.md` schreiben, die README um einen Third-Party-Licenses-Abschnitt
ergänzen, und `CLAUDE.md` um den neuen Test-/Gate-Workflow erweitern — nur Dokumentation,
kein Code.

## 2. Voraussetzungen

Schritt 29 ist committet und `./tools/verify.sh` läuft grün.

## 3. Exakte Aufgaben

1. Datei anlegen `docs/ARCHITECTURE.md`. Inhalt: eine straffe technische Übersicht (nicht
   das Feature-Inventar aus `docs/hardening/00-OVERVIEW.md` duplizieren, sondern
   darauf verweisen für das "Was", hier das "Wie" beschreiben):
   - Komponentendiagramm in Prosa: `SettingsActivity` ↔ `Prefs` ↔ `SwitcherService` ↔
     `BackGestureMachine`/`AccessibilityAppSource`/`SwitcherOverlay`/`UpdateChecker`.
   - Warum `BackGestureMachine` als Seam existiert (Verweis auf
     `docs/hardening/16-back-gesture-machine-seam.md`).
   - Der Test-Pyramid-Aufbau: Robolectric-Unit-Tests (Masse), View-Tree-Snapshots
     (UI-Schutz), ein Espresso-Smoke (Device-Realität), manuelle Checkliste (alles andere).
   - Verweis auf `docs/hardening/TEST-GAPS.md` für bewusst nicht automatisiertes.
2. `README.md` am Ende, vor dem bestehenden `## License`-Abschnitt, einen neuen Abschnitt
   `## Third-party licenses` einfügen:
   ```markdown
   ## Third-party licenses

   The app itself ships with zero runtime dependencies beyond the Kotlin standard library
   (Apache-2.0), which the Kotlin Gradle plugin adds automatically. The test suite under
   `app/src/test` and `app/src/androidTest` depends on JUnit (EPL-1.0), Robolectric (MIT),
   AndroidX Test / Espresso / UiAutomator (Apache-2.0), and JaCoCo (EPL-2.0) — none of these
   are included in the release APK; see `docs/hardening/37-license-audit.md` for the
   mechanical proof. LeakCanary (Apache-2.0) is `debugImplementation`-only and ships in
   debug builds alone, never in release.
   ```
3. `CLAUDE.md` öffnen, im Abschnitt „Build & CI commands" nach dem bestehenden
   `./gradlew lintDebug`-Absatz ergänzen:
   ```markdown
   ```sh
   ./tools/verify.sh          # the one gate: lint, unit tests, coverage, both builds
   ```

   `./tools/verify.sh` resolves JDK 17 itself (JAVA_HOME is not on PATH on this machine —
   see `docs/hardening/00-OVERVIEW.md`) and is the required check after every change.
   Git hooks (`.githooks/`) run a subset on commit and the full gate on push; CI
   (`.github/workflows/ci.yml`) runs the same `verify` task. See `docs/hardening/` for the
   full test-suite and hardening plan.
   ```
   Den bisherigen Satz „There is no test suite (`./gradlew test` has nothing to run)."
   ersetzen durch eine Beschreibung, die den jetzigen Stand korrekt wiedergibt (Robolectric-
   Unit-Tests, View-Tree-Snapshots, ein Espresso-Smoke, `BackGestureMachine` als testbare
   Seam für die Timing-Logik) — mit Verweis auf `docs/hardening/TEST-GAPS.md` für das, was
   weiterhin nur manuell geprüft wird.

## 4. Nicht anfassen

- Kein Code.
- Keine andere Stelle in `CLAUDE.md` oder `README.md` umformulieren als die zwei genannten.

## 5. Akzeptanzkriterien

- `docs/ARCHITECTURE.md` existiert.
- `README.md` enthält den neuen „Third-party licenses"-Abschnitt vor „License".
- `CLAUDE.md` erwähnt `./tools/verify.sh` und verweist auf `docs/hardening/`.
- `./tools/verify.sh` grün (unverändert, reine Doku).

## 6. Gate

`./tools/verify.sh`.

## 7. Commit-Message-Vorschlag

```
docs: add ARCHITECTURE.md, third-party licenses, and update CLAUDE.md

Documents the BackGestureMachine seam and test pyramid, lists the
test-only dependencies' licenses, and points CLAUDE.md at
./tools/verify.sh as the required gate.
```
