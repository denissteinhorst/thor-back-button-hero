# Schritt 23 — CI: `verify`-Job + Caching + Reports

## 1. Ziel

Die CI auf dieselbe Gate-Kette umstellen, die lokal läuft, mit Gradle-Caching und
Test-/Coverage-Reports als herunterladbare Artefakte.

## 2. Voraussetzungen

Schritt 22 ist committet und `./tools/verify.sh` läuft grün.

## 3. Exakte Aufgaben

`.github/workflows/ci.yml` öffnen und den bestehenden `build-and-lint`-Job ersetzen durch:

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

jobs:
  verify:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"

      - name: Cache Gradle
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: gradle-${{ runner.os }}-${{ hashFiles('**/*.gradle.kts', 'gradle/wrapper/gradle-wrapper.properties') }}
          restore-keys: |
            gradle-${{ runner.os }}-

      - name: Run full gate (lint, tests, coverage, build)
        run: ./gradlew verify --console=plain

      - name: Upload lint report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: lint-report
          path: app/build/reports/lint-results-debug.html

      - name: Upload unit test report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: unit-test-report
          path: app/build/reports/tests/testDebugUnitTest

      - name: Upload coverage report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: coverage-report
          path: app/build/reports/jacoco/jacocoTestReport/html
```

**Hinweis:** `JAVA_HOME` muss in CI **nicht** explizit gesetzt werden — `setup-java@v4`
exportiert es bereits korrekt für den Runner. `./gradlew verify` direkt (ohne
`tools/verify.sh`) ist hier richtig, weil `tools/verify.sh`s Fallback-Logik für genau den
lokalen macOS-Homebrew-Fall gedacht ist, den es in CI nicht gibt.

## 4. Nicht anfassen

- `.github/workflows/release.yml` bleibt unverändert — dieser Schritt betrifft
  ausschließlich `ci.yml`.
- Keine neuen Branches/Trigger-Events hinzufügen, die der bestehende Workflow nicht schon
  hatte.

## 5. Akzeptanzkriterien

- Ein Push auf einen Test-Branch (oder ein offener PR) zeigt den `verify`-Job grün in der
  GitHub-Actions-Oberfläche, mit allen drei Artefakten (`lint-report`, `unit-test-report`,
  `coverage-report`) herunterladbar.
- Ein zweiter Lauf auf demselben Branch ohne Code-Änderung ist deutlich schneller als der
  erste (Cache-Treffer sichtbar in den Workflow-Logs unter „Cache Gradle").

## 6. Gate

`./tools/verify.sh` lokal **und** ein grüner CI-Lauf auf GitHub. Bei Rot in CI: Workflow-Logs
lesen, Ursache beheben (meist ein Pfad- oder Berechtigungsunterschied zwischen macOS-lokal
und Linux-CI), nicht den Job grün erzwingen, indem ein Teil der `verify`-Kette aus dem
CI-Lauf entfernt wird.

## 7. Commit-Message-Vorschlag

```
ci: run the same verify gate as locally, with Gradle caching

Replaces the old assembleDebug+lintDebug job with ./gradlew verify
(lint, unit tests, coverage, both build variants), caches ~/.gradle,
and uploads lint/test/coverage reports as artifacts.
```
