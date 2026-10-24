# Schritt 01 — Baseline-Commit

## 1. Ziel

Den kompletten aktuell untracked/modifizierten Arbeitsbaum als einen einzigen Baseline-Commit
festhalten und den heutigen Build-/Lint-Zustand als Referenzdatei ablegen, damit jeder
spätere Schritt ein `git restore`/`git revert` als Rückfalllinie hat.

## 2. Voraussetzungen

Keine — dies ist der erste Schritt.

## 3. Exakte Aufgaben

1. Prüfen, was committet würde (nichts Destruktives in diesem Schritt — nur Lesen):
   ```sh
   git status --short
   ```
   Erwartet: `M README.md` plus `??`-Zeilen für `.github/`, `.gitignore`, `CLAUDE.md`,
   `LICENSE`, `app/`, `artwork/`, `build.gradle.kts`, `docs/`, `gradle.properties`,
   `gradle/`, `gradlew`, `gradlew.bat`, `settings.gradle.kts`.
2. Sicherstellen, dass `local.properties` **nicht** mit committet wird (enthält den
   lokalen SDK-Pfad). `.gitignore` enthält bereits `local.properties` — mit
   `git check-ignore -v local.properties` bestätigen, dass die Regel greift.
3. Build-Baseline erzeugen (JDK ist noch nicht im PATH, `JAVA_HOME` explizit mitgeben):
   ```sh
   mkdir -p docs/hardening/baseline
   JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
     ./gradlew clean assembleDebug assembleRelease lintDebug --console=plain \
     > docs/hardening/baseline/build-baseline.txt 2>&1; \
     echo "exit code: $?" >> docs/hardening/baseline/build-baseline.txt
   ```
4. Lint-Report als Baseline kopieren:
   ```sh
   cp app/build/reports/lint-results-debug.txt docs/hardening/baseline/lint-baseline-report.txt
   ```
5. `docs/hardening/baseline/build-baseline.txt` öffnen und prüfen: `BUILD SUCCESSFUL` und
   `exit code: 0`. Falls nicht: **stoppen und dem Nutzer melden** — ohne grüne Baseline darf
   kein Baseline-Commit entstehen.
6. `docs/hardening/baseline/lint-baseline-report.txt` öffnen und prüfen: `0 errors, 15
   warnings`. Eine andere Zahl ist kein Fehler dieses Schritts, aber muss im Commit-Kommentar
   und in `docs/hardening/00-OVERVIEW.md` (Abschnitt „Baseline") als abweichend vermerkt
   werden, statt sie zu „fixen".
7. Alles stagen und committen:
   ```sh
   git add -A
   git status --short   # Review: keine Datei mit "secret", "keystore", ".jks", ".env" dabei
   git commit -m "chore: baseline commit before release hardening"
   ```

## 4. Nicht anfassen

- Keine Datei inhaltlich ändern außer den beiden neuen Baseline-Dateien unter
  `docs/hardening/baseline/`. Dieser Schritt ist ein reiner Commit des bestehenden
  Arbeitsbaums, keine Code-Änderung.
- `local.properties`, `*.keystore`, `*.jks` dürfen nie gestaged werden.

## 5. Akzeptanzkriterien

- `git log --oneline -1` zeigt den neuen Baseline-Commit.
- `git status --short` ist danach leer.
- `docs/hardening/baseline/build-baseline.txt` enthält `BUILD SUCCESSFUL`.
- `docs/hardening/baseline/lint-baseline-report.txt` existiert und ist nicht leer.

## 6. Gate

Es gibt noch keinen `verify`-Befehl (kommt erst in Schritt 03). Gate für diesen Schritt ist
der in Aufgabe 3 ausgeführte Build selbst — er muss grün sein, bevor committet wird. Bei Rot:
**nicht committen**, Fehlerausgabe dem Nutzer melden, stoppen.

## 7. Commit-Message-Vorschlag

```
chore: baseline commit before release hardening

Captures the full working tree (app, build config, CI workflows, docs)
as the starting point for the test-suite and audit work in docs/hardening/.
Build + lint baseline recorded under docs/hardening/baseline/.
```
