# Schritt 38 — Release-Checkliste + finaler Gesamtlauf

## 1. Ziel

Den gesamten Plan mit einem letzten, vollständigen Gate-Lauf abschließen und eine
Release-Checkliste für den tatsächlichen `v1`-Tag hinterlassen.

## 2. Voraussetzungen

Alle Schritte 01–37 sind committet (bzw. die explizit als optional/bedingt markierten
bewusst übersprungen und so in `PROGRESS.md` vermerkt), `./tools/verify.sh` läuft grün.

## 3. Exakte Aufgaben

1. Finalen Gesamtlauf ausführen und das Ergebnis sichern:
   ```sh
   ./tools/verify.sh 2>&1 | tee docs/hardening/baseline/final-verify-run.txt
   ```
2. Coverage-Report final prüfen:
   ```sh
   open app/build/reports/jacoco/jacocoTestReport/html/index.html
   ```
   oder den XML-Report auf die in Schritt 19/26/27 festgelegten Minima prüfen
   (`BackGestureMachine` ≥ 0.95, `Prefs` ≥ 0.90, `DoubleTapDetector` = 1.00,
   `AccessibilityAppSource` ≥ 0.80, `CardTouch` ≥ 0.90, `UpdateChecker` ≥ 0.70).
3. `docs/hardening/PROGRESS.md` öffnen, bestätigen: jeder Schritt ist entweder abgehakt oder
   explizit mit Begründung als übersprungen markiert (Schritt 21, ggf. 33/34/35).
4. Datei anlegen `docs/hardening/RELEASE-CHECKLIST.md`:
   ```markdown
   # Release-Checkliste für v1

   Vor dem Setzen des `v1`-Tags (siehe CLAUDE.md „Version tag convention"):

   - [ ] `./tools/verify.sh` lokal grün
   - [ ] CI (`verify`-Job in `.github/workflows/ci.yml`) auf `main` grün
   - [ ] `docs/hardening/PROGRESS.md` zeigt alle Schritte abgehakt oder begründet
         übersprungen
   - [ ] `docs/hardening/LICENSE-AUDIT.md` bestätigt einen dependency-freien
         Release-Klassenpfad
   - [ ] `app/build.gradle.kts` → `versionCode` wurde seit dem letzten Tag erhöht (aktuell:
         siehe `defaultConfig.versionCode`)
   - [ ] Tag-Name entspricht exakt `v<versionCode>` (siehe `UpdateChecker.parseRelease`)
   - [ ] `MANUAL-GESTURE-CHECKLIST.md` wurde für den finalen Build einmal komplett
         durchgearbeitet
   - [ ] Keine der 15 (bzw. 13 nach Schritt 28) Lint-Baseline-Warnungen hat sich unerwartet
         vermehrt (`app/lint-baseline.xml` seit letztem Review unverändert oder bewusst
         aktualisiert)
   - [ ] `RELEASE_KEYSTORE_*`-Secrets in den Repo-Settings hinterlegt (für
         `.github/workflows/release.yml`)
   - [ ] Branch-Protection auf `main` gemäß `docs/hardening/BRANCH-PROTECTION.md` aktiv
   ```
5. `docs/hardening/baseline/final-verify-run.txt` auf `BUILD SUCCESSFUL` prüfen.

## 4. Nicht anfassen

- Kein Code-Fix in diesem letzten Schritt — findet der finale Gesamtlauf noch etwas Rotes,
  gehört das zurück zu dem Schritt, der es verursacht hat, nicht in einen Ad-hoc-Fix hier.
- Kein tatsächliches Tagging/Release in diesem Schritt — die Checkliste wird erstellt, nicht
  abgearbeitet (das Tagging ist eine eigene, vom Nutzer auszulösende Aktion außerhalb dieses
  Plans).

## 5. Akzeptanzkriterien

- `docs/hardening/baseline/final-verify-run.txt` enthält `BUILD SUCCESSFUL`.
- Alle Coverage-Minima aus Schritt 19/26/27 sind erfüllt.
- `docs/hardening/RELEASE-CHECKLIST.md` existiert mit allen 9 Punkten.
- `docs/hardening/PROGRESS.md` ist vollständig (abgehakt oder begründet übersprungen).

## 6. Gate

`./tools/verify.sh`. Bei irgendeinem Rot an dieser Stelle: **nicht in diesem Schritt
reparieren** — identifizieren, welcher frühere Schritt betroffen ist, dorthin zurückkehren,
reparieren, von dort aus erneut bis zu diesem Schritt vorarbeiten.

## 7. Commit-Message-Vorschlag

```
docs: final verify run and release checklist for v1

Confirms all coverage minima are met and every plan step is accounted
for in PROGRESS.md. RELEASE-CHECKLIST.md is the last gate before
tagging v1.
```
