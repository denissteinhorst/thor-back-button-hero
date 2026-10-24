# Schritt 03 — Gate-Befehl

## 1. Ziel

Einen einzigen, stets gleichen Befehl schaffen, der ab jetzt für jeden weiteren Schritt die
Abnahme ist: `./tools/verify.sh`.

## 2. Voraussetzungen

Schritt 02 ist committet und grün.

## 3. Exakte Aufgaben

1. Im root `build.gradle.kts` (nicht `app/build.gradle.kts`) folgenden Task registrieren —
   am Dateiende anfügen:
   ```kotlin
   tasks.register("verify") {
       group = "verification"
       description = "Single gate: lint, unit tests, debug+release build."
       dependsOn(
           ":app:lintDebug",
           ":app:testDebugUnitTest",
           ":app:assembleDebug",
           ":app:assembleRelease",
       )
   }
   ```
   **Wichtig:** `:app:jacocoCoverageVerification` gehört **noch nicht** in diese Liste — der
   JaCoCo-Task existiert erst nach Schritt 19. Schritt 19 erweitert diesen `dependsOn`-Block
   um genau diese eine Zeile. Wird hier schon eingetragen, schlägt der Task mit „task not
   found" fehl.
2. Datei anlegen `tools/verify.sh`:
   ```sh
   #!/usr/bin/env bash
   set -euo pipefail
   cd "$(dirname "$0")/.."

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
     echo "tools/verify.sh: no JDK 17 found. Set JAVA_HOME explicitly." >&2
     exit 1
   fi
   export JAVA_HOME

   exec ./gradlew verify --console=plain
   ```
3. Ausführbar machen:
   ```sh
   chmod +x tools/verify.sh
   ```
4. Testlauf: `./tools/verify.sh` — **ohne** vorher `JAVA_HOME` zu setzen, um die
   Selbstauflösung zu prüfen.

## 4. Nicht anfassen

- `app/build.gradle.kts` bleibt unverändert — der neue Task gehört ins **root**
  `build.gradle.kts`.
- Keine anderen Tasks in den `dependsOn`-Block aufnehmen als die vier genannten.

## 5. Akzeptanzkriterien

- `unset JAVA_HOME; ./tools/verify.sh` → `BUILD SUCCESSFUL`, keine manuelle `JAVA_HOME`-Angabe
  nötig.
- `./tools/verify.sh` ist ab jetzt der einzige in Schritt-Dateien referenzierte Gate-Befehl.

## 6. Gate

Der in Aufgabe 4 beschriebene Testlauf selbst ist das Gate für diesen Schritt (es gibt noch
keinen vorherigen `verify`, gegen den man prüfen könnte). Bei Rot: `git restore
build.gradle.kts` und `tools/verify.sh` löschen, stoppen, melden.

## 7. Commit-Message-Vorschlag

```
build: add verify gate task and tools/verify.sh

Single entry point for all future steps: ./tools/verify.sh resolves
JDK 17 automatically and runs lint + unit tests + both build variants.
jacocoCoverageVerification is intentionally not yet wired in — added in
step 19 once the JaCoCo plugin exists.
```
