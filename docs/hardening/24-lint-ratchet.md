# Schritt 24 — Lint-Ratchet

## 1. Ziel

Die 15 bestehenden Lint-Warnungen per Baseline-Datei einfrieren (nicht beheben — das wäre
eine optische Änderung) und jede **neue** Warnung ab jetzt als Build-Fehler behandeln.

## 2. Voraussetzungen

Schritt 23 ist committet und `./tools/verify.sh` läuft grün.

**Vorher lesen: `docs/hardening/PITFALLS.md`, insbesondere P8** — `lintDebug` analysiert auch
die Testquellen, `warningsAsErrors` kann also ab jetzt an neuem **Testcode** scheitern. Die
richtige Reaktion ist dann, die Warnung in der Testdatei zu beheben, nicht
`warningsAsErrors` abzuschalten und nicht den Testfund in die Baseline aufzunehmen.

## 3. Exakte Aufgaben

1. Lint-Baseline-Datei erzeugen:
   ```sh
   JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
     ./gradlew -Dlint.baselines.continue=true updateLintBaseline --console=plain
   ```
   Falls der Task `updateLintBaseline` in AGP 8.5.2 nicht existiert: stattdessen in
   `app/build.gradle.kts` im `android { }`-Block zunächst
   ```kotlin
   lint {
       baseline = file("lint-baseline.xml")
   }
   ```
   ergänzen, dann `JAVA_HOME=... ./gradlew lintDebug --console=plain` einmal laufen lassen —
   AGP erzeugt `app/lint-baseline.xml` beim ersten Lauf automatisch, wenn die Datei noch
   nicht existiert und `baseline` konfiguriert ist.
2. Öffnen `app/lint-baseline.xml` und zählen: muss exakt die 15 aus Schritt 01 bekannten
   Warnungs-Fundstellen enthalten (`DataExtractionRules`, `ObsoleteSdkInt`,
   2× `UnusedResources`, 5× `IconColors`, 5× `IconLauncherShape`, `MonochromeLauncherIcon`).
   Enthält die Datei mehr oder weniger: **stoppen und melden**, nicht blind committen —
   eine abweichende Zahl bedeutet, dass sich seit Schritt 01 etwas am Lint-Ergebnis geändert
   hat, das zuerst verstanden werden muss.
3. In `app/build.gradle.kts`, im selben `lint { }`-Block, ergänzen:
   ```kotlin
   lint {
       baseline = file("lint-baseline.xml")
       warningsAsErrors = true
       abortOnError = true
   }
   ```
4. Erneuten Lauf bestätigen: `JAVA_HOME=... ./gradlew lintDebug --console=plain` muss mit
   der Baseline weiterhin grün sein (die 15 bekannten Warnungen sind durch die Baseline
   unterdrückt, `warningsAsErrors` greift nur für neue, nicht in der Baseline enthaltene
   Funde).
5. Testfall für die Ratchet-Wirkung (nur lokal, **nicht committen**): temporär eine
   offensichtliche neue Lint-Warnung einbauen (z. B. eine ungenutzte lokale Variable in
   einer Testdatei), `lintDebug` laufen lassen → muss jetzt fehlschlagen. Danach die
   temporäre Änderung mit `git restore` wieder entfernen.

## 4. Nicht anfassen

- Keine der 15 bestehenden Lint-Ursachen beheben (kein neues Icon, keine
  `dataExtractionRules`-XML, keine Ressourcen-Löschung außerhalb von Schritt 28's
  spezifisch belegtem Scope).
- `app/lint-baseline.xml` nicht händisch nachbearbeiten — nur durch den AGP-Mechanismus
  generieren lassen.

## 5. Akzeptanzkriterien

- `./tools/verify.sh` grün.
- `app/lint-baseline.xml` existiert, committet, enthält exakt 15 Fundstellen.
- Der in Aufgabe 5 beschriebene manuelle Ratchet-Test bestätigt: eine neue Warnung lässt den
  Build fehlschlagen.

## 6. Gate

`./tools/verify.sh`. Bei Rot: falls die Baseline-Zahl abweicht, nicht raten — dem Nutzer die
genaue Abweichung (welche Regel, wie viele Fundstellen mehr/weniger) melden und auf
Entscheidung warten, bevor committet wird.

## 7. Commit-Message-Vorschlag

```
build: freeze existing lint warnings via baseline, ratchet new ones

app/lint-baseline.xml locks in the 15 known warnings from the Phase 0
baseline. warningsAsErrors now fails the build on anything new.
```
