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
- [x] 11 — Snapshot: `SettingsActivity` Hauptseite
- [x] 12 — Snapshot: Shoulder-Rows
- [x] 13 — Snapshot: Dialoge
- [x] 14 — A11y-Inventar (Report, kein Fix)
- [x] 15 — Device: Golden-Logcat-Trace + manuelle Checkliste schreiben
- [x] 16 — Seam: `BackGestureMachine`-Extraktion
- [x] 17 — Characterization: `BackGestureMachine`
- [x] 18 — Device: Espresso-Smoke auf `SettingsActivity`
- [x] 19 — Coverage-Ratchet (JaCoCo Pro-Klassen-Minima)
- [x] 20 — Suite-Stabilität (5× Lauf, Flake-Report)
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

**Letzter grüner Gate-Lauf:** 2026-10-24 — Schritt 20: `./tools/verify.sh` grün, inklusive
`jacocoCoverageVerification`; fünf unabhängige Unit-Test-Läufe waren 5/5 grün.

**Wichtiger Fund in Schritt 12 — behoben:** `ViewTree.kt`s `when`-Block prüfte `is TextView`
vor `is CompoundButton`. Da `Switch` in Android eine `TextView`-Unterklasse ist, gewann immer
der `TextView`-Zweig und `checked=...` wurde **nie** für irgendeinen Switch ausgegeben — ein
echtes Loch im UI-Schutz (widerspricht der eigenen Spezifikation in `00-OVERVIEW.md`).
Commit `c7c32ad` korrigiert die Zweig-Reihenfolge und nimmt die bereits committeten
Schritt-11-Goldens neu auf (Diff zeigt nur die neu erscheinende `checked=`-Spalte, sonst
keine Änderung).

**Schritt 11 — Qualifier-Fix:** `en-xxhdpi-night` scheiterte an Robolectrics
Qualifier-Parser (falsche Reihenfolge). Korrigiert zu `en-night-xxhdpi` (nightMode muss vor
density stehen) — reine Qualifier-Formatkorrektur, von Schritt 11s eigener Gate-Anweisung
ausdrücklich erlaubt. Der Dunkelmodus-Diff ist **nicht leer** (160 Zeilen, jede Textfarbe
ändert sich) — der Qualifier greift tatsächlich, keine Eskalation nötig.

**Schritt 13:** nur die 3 im Scope stehenden Dialoge gesnapshotet; die 3
Update-Check-Ergebnisdialoge sind als Lücke in `TEST-GAPS.md` #6 dokumentiert (roher Thread
nicht synchronisierbar).

**Schritt 14:** 2 informative A11y-Funde in `A11Y-REPORT.md` dokumentiert (unbeschriftete
Switches, minHeightDp=0-Messartefakt) — bewusst **nicht** behoben.

**Nebenbefund, nicht mein Zutun:** `docs/gesture-explainer.html` und `docs/gestures.gif`
zeigen als lokal modifiziert (nicht von mir angefasst, nicht committet) — offenbar eigene
Arbeit des Nutzers am Gesture-Explainer parallel zu dieser Session. Unangetastet gelassen.

**Schritt 15 — Device-Session erfolgreich:** AYN Thor war per `adb` verbunden
(`c4c5bc62`), Accessibility-Service bereits aktiviert, kein manueller Permission-Schritt
nötig. `DEBUG_GESTURES` temporär auf `true`, Nutzer hat live Einzeltipp, Doppeltipp,
Tippen-dann-Halten (×2) und Halten ausgeführt. Trace enthält alle 7 geforderten
Entscheidungstypen. `DEBUG_GESTURES` zurück auf `false`, `SwitcherService.kt` danach
byte-identisch zum committeten Zustand (`git diff` leer), Gerät neu installiert.

**Nebenbefund, weiterhin nicht mein Zutun:** `README.md` zeigt jetzt zusätzlich zu
`docs/gesture-explainer.html`/`docs/gestures.gif` als lokal modifiziert — konsistent mit
deiner parallelen Arbeit am Gesture-Explainer. Weiterhin unangetastet gelassen.

**Schritte 16–18:** `BackGestureMachine` wurde verhaltensneutral extrahiert und mit acht
deterministischen Unit-Tests charakterisiert. Trace-Gegenprobe und vollständige manuelle
Checkliste waren ohne Entscheidungsabweichung; der Espresso-Smoke lief nach Entfernen des
doppelten ADB-Transports mit 1 Test und 0 Fehlern durch.

**Schritt 19:** JaCoCo benötigt für die von Robolectric geladenen App-Klassen
`includeNoLocationClasses`; ohne diese Einstellung enthielt die `.exec`-Datei eine Session,
der Report zeigte aber für alle Klassen 0 %. Gemessene Line-Coverage: BackGestureMachine
90,77 %, Prefs 100 %, DoubleTapDetector 100 %, AccessibilityAppSource 60 %, SettingsUiKitKt
100 %, UpdateChecker 34,62 %. Der Ratchet schützt die vier in Schritt 19 vorgesehenen Klassen;
UpdateChecker kommt nach dem Parse-Seam in Schritt 26 hinzu.
