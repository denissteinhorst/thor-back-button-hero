# Fortschritt

Nach jedem abgeschlossenen, committeten und grün gegateten Schritt hier abhaken. Nicht
vorab abhaken, nicht mehrere Schritte parallel anfangen. Bei einem roten Gate: Schritt NICHT
abhaken, zurücknehmen (siehe globale Regel 3 in `00-OVERVIEW.md`), stoppen, melden.

**Zuerst `00-OVERVIEW.md` lesen, dann `PITFALLS.md`.** Letzteres beschreibt acht konkrete
technische Fallen (Uhr bei 0, Netzwerk-Thread in `onCreate`, Robolectric-Fensterzugriff,
JaCoCo-Konfiguration, unsigniertes Release-APK, DayNight-Theme, Arbeitsverzeichnis,
Lint auf Testquellen), die sonst zu rotem Gate mit nicht offensichtlicher Ursache führen.

## Phase 0 — Baseline

- [x] 01 — Baseline-Commit + Build-/Lint-Baseline-Dump

## Phase 1 — Test-Suite (Safety Net)

- [x] 02 — Test-Infrastruktur (Dependencies, Robolectric-Config, Smoke-Test)
- [x] 03 — Gate-Befehl (`verify`-Task + `tools/verify.sh`)
- [x] 04 — Characterization: `Prefs` Defaults + Accessoren
- [x] 05 — Characterization: `Prefs`-Migrationen + `resetToDefaults()`
- [x] 06 — Characterization: `DoubleTapDetector`
- [x] 07 — Characterization: `AccessibilityAppSource`
- [x] 08 — Characterization: `SettingsUiKit`-Helfer
- [x] 09 — Ressourcen-Integrität (DE/EN-Parität, Lint-Warnungs-Inventar)
- [x] 10 — View-Tree-Snapshot-Infrastruktur
- [ ] 11 — Snapshot: `SettingsActivity` Hauptseite
- [ ] 12 — Snapshot: Shoulder-Rows
- [ ] 13 — Snapshot: Dialoge
- [ ] 14 — A11y-Inventar (Report, kein Fix)
- [ ] 15 — Device: Golden-Logcat-Trace + manuelle Checkliste schreiben
- [ ] 16 — Seam: `BackGestureMachine`-Extraktion
- [ ] 17 — Characterization: `BackGestureMachine`
- [ ] 18 — Device: Espresso-Smoke auf `SettingsActivity`
- [ ] 19 — Coverage-Ratchet (JaCoCo Pro-Klassen-Minima)
- [ ] 20 — Suite-Stabilität (5× Lauf, Flake-Report)
- [ ] 21 — Optional: Roborazzi-Pixel-Goldens (nur Linux-CI)

## Phase 2 — Pre-Commit-Hooks & CI

- [ ] 22 — Self-installing Git-Hooks
- [ ] 23 — CI: `verify`-Job + Caching + Reports
- [ ] 24 — Lint-Ratchet (Baseline-Datei + `warningsAsErrors`)
- [ ] 25 — Branch-Protection-Doku + Dependabot

## Phase 3 — Best-Practice & Architektur (verhaltensneutral)

- [ ] 26 — `UpdateChecker`-Parse-Seam + Tests
- [ ] 27 — `CardTouch`-Extraktion + Tests
- [ ] 28 — Toter Code entfernen
- [ ] 29 — Konfiguration & Fehlerbehandlung-Review
- [ ] 30 — Doku (`ARCHITECTURE.md`, README-Lizenzabschnitt, CLAUDE.md-Update)

## Phase 4 — Performance & Memory (erst messen, dann ändern)

- [ ] 31 — Baseline-Messung (meminfo, gfxinfo, Startzeit, APK-Größe)
- [ ] 32 — LeakCanary + Leak-Inventar-Report
- [ ] 33 — Fix: Thumbnail-Cache begrenzen (nur falls belegt)
- [ ] 34 — Fix: Bitmap-Churn in `captureFrontApp`
- [ ] 35 — Fix: PackageManager-Label/Icon-Cache
- [ ] 36 — Re-Messung + Vergleichsreport

## Phase 5 — Abschluss

- [ ] 37 — Lizenz- & Dependency-Audit
- [ ] 38 — Release-Checkliste + finaler Gesamtlauf

---

**Letzter grüner Gate-Lauf:** 2026-10-24 — Schritt 10: `./tools/verify.sh` grün. Commits
04–10: `2c72e22`, `dbbff35`, `f467920`, `34dad32`, `1ee5a38`, `bae67f1`, `d9cc670`.

**Erledigt — Hinweis zu Schritt 09 aufgelöst:** der Lint-Diff gegen die Schritt-01-Baseline
war wie vorhergesagt nicht leer (genau die 5 `GradleDependency`-Warnungen aus Schritt 02,
keine neue Produktivcode-Warnung). `docs/hardening/baseline/lint-warning-inventory.txt`
spiegelt jetzt den tatsächlichen Stand (20 Warnungen) wider.

**Schritt 07 — Probe-Ergebnis:** `windowsOnAllDisplays` liefert unter Robolectric für
`SwitcherService` eine leere, nicht-null Liste (`{0=[]}`) — bester Fall laut Schrittdatei.
`topApp`/`appsOnDisplay` sind daher für den leeren Zustand mitgetestet, nicht nur die
Thumbnail-Pfade.

**Noch offen für spätere Schritte:**
- Schritt 11: Pflichtprüfung, ob der `night`-Qualifier unter der installierten
  Robolectric-Version tatsächlich auf das dunkle Theme umschaltet (`PITFALLS.md` P6).
- Schritt 16/17: `BackGestureMachine`-Seam — komplexester verbleibender Schritt vor Phase 2.
