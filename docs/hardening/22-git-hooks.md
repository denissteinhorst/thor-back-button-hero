# Schritt 22 — Self-installing Git-Hooks

## 1. Ziel

`pre-commit` (schnell) und `pre-push` (vollständig) als Git-Hooks einrichten, die sich nach
`git clone` + einem ersten Gradle-Aufruf automatisch aktivieren, ohne manuellen Schritt.

## 2. Voraussetzungen

Schritt 21 ist abgeschlossen oder bewusst übersprungen, Phase 1 ist vollständig committet
und `./tools/verify.sh` läuft grün.

## 3. Exakte Aufgaben

1. Verzeichnis `.githooks/` anlegen.
2. Datei `.githooks/pre-commit`:
   ```sh
   #!/usr/bin/env bash
   set -euo pipefail
   cd "$(git rev-parse --show-toplevel)"

   if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
     if command -v /usr/libexec/java_home >/dev/null 2>&1; then
       JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
     fi
   fi
   if { [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; } \
       && [ -x /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java ]; then
     JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
   fi
   if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
     echo "pre-commit: no JDK 17 found. Set JAVA_HOME explicitly." >&2
     exit 1
   fi
   export JAVA_HOME

   echo "pre-commit: compiling + running unit tests..."
   ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --console=plain
   ```
3. Datei `.githooks/pre-push`:
   ```sh
   #!/usr/bin/env bash
   set -euo pipefail
   cd "$(git rev-parse --show-toplevel)"
   echo "pre-push: running the full gate (./tools/verify.sh)..."
   ./tools/verify.sh
   ```
4. Beide ausführbar machen: `chmod +x .githooks/pre-commit .githooks/pre-push`.
5. Selbstinstallation über Gradle: im root `build.gradle.kts` ergänzen:
   ```kotlin
   tasks.register<Exec>("installGitHooks") {
       description = "Points git at .githooks so the hooks work right after a clone."
       commandLine("git", "config", "core.hooksPath", ".githooks")
       isIgnoreExitValue = true
   }
   ```
   **Nicht** `tasks.register("...") { doLast { exec { ... } } }` verwenden — `Project.exec {}`
   innerhalb einer Task-Action ist in Gradle 8 deprecated und in Gradle 9 entfernt. Der
   `Exec`-Tasktyp oben ist der unterstützte Weg. `isIgnoreExitValue = true` verhindert, dass
   ein Build in einem Verzeichnis ohne Git-Repo (z. B. ein entpacktes Source-Archiv)
   fehlschlägt.
   In `app/build.gradle.kts` sicherstellen, dass dieser Task vor jedem Build läuft — den
   `preBuild`-Task des `:app`-Moduls davon abhängig machen:
   ```kotlin
   tasks.named("preBuild") {
       dependsOn(":installGitHooks")
   }
   ```
6. Testen: `git config --unset core.hooksPath` (falls schon gesetzt durch einen vorherigen
   manuellen Test), dann `JAVA_HOME=... ./gradlew tasks --console=plain` ausführen (ein
   beliebiger Task, der `preBuild` auslöst) und danach `git config core.hooksPath` prüfen —
   muss `.githooks` zeigen.
7. Mit einer belanglosen Testdatei (z. B. einer temporären Leerzeile in einer bereits
   bestehenden Markdown-Datei unter `docs/hardening/baseline/`, danach wieder entfernt)
   verifizieren, dass `git commit` den `pre-commit`-Hook tatsächlich ausführt (sichtbare
   Ausgabe „pre-commit: compiling...").

## 4. Nicht anfassen

- Kein bestehender Task außer `preBuild`'s `dependsOn`-Liste wird verändert.
- Keine Hooks mit `--no-verify` umgehen, auch nicht zum Testen dieses Schritts — wenn ein
  Hook beim Testen fehlschlägt, den Hook-Inhalt korrigieren, nicht umgehen.

## 5. Akzeptanzkriterien

- Frischer Klon in ein temporäres Verzeichnis
  (`git clone . /tmp/hooks-test-clone`), dort ein beliebiger Gradle-Aufruf → danach
  `git -C /tmp/hooks-test-clone config core.hooksPath` zeigt `.githooks`, **ohne** dass
  manuell `installGitHooks` aufgerufen wurde.
- Ein Commit-Versuch mit absichtlich kaputtem Kotlin-Code (nur im Testklon, nicht
  committen) lässt `pre-commit` mit Nicht-Null-Exit fehlschlagen und der Commit wird
  verweigert.
- `./tools/verify.sh` grün im Hauptrepo.
- Temporären Testklon danach löschen: `rm -rf /tmp/hooks-test-clone`.

## 6. Gate

`./tools/verify.sh`. Bei Rot: `git restore build.gradle.kts app/build.gradle.kts`,
`.githooks/` löschen, stoppen, melden.

## 7. Commit-Message-Vorschlag

```
build: add self-installing pre-commit/pre-push git hooks

pre-commit runs compile + unit tests; pre-push runs the full
./tools/verify.sh gate. Installed automatically via a Gradle task
wired into :app:preBuild, so a fresh clone activates them without a
manual step. Still bypassable with --no-verify - see step 23 for the
CI enforcement and step 25 for the branch-protection recommendation.
```
