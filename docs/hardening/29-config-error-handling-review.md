# Schritt 29 — Konfiguration & Fehlerbehandlung-Review

## 1. Ziel

Konfiguration (Versionierung, Signing, Secrets-Handling) und die bestehenden
`runCatching`-Stellen systematisch durchsehen und als Report dokumentieren — **Review,
nicht Umbau**: nur eindeutig risikofreie, nicht verhaltensändernde Korrekturen werden direkt
vorgenommen, alles andere wird gemeldet statt verändert.

## 2. Voraussetzungen

Schritt 28 ist committet und `./tools/verify.sh` läuft grün.

## 3. Exakte Aufgaben

1. **Versionierungs-Konsistenz prüfen:**
   ```sh
   grep -n "versionCode\|versionName" app/build.gradle.kts
   grep -n "removePrefix(\"v\")" app/src/main/java/dev/steinhorst/backbuttonhero/UpdateChecker.kt
   grep -n "v\*" .github/workflows/release.yml
   ```
   Bestätigen: `versionCode = 1` passt zum Tag-Format `v<versionCode>` (Tag `v1`), das
   `UpdateChecker.parseRelease` erwartet, das `release.yml`s Trigger-Pattern `v*` matcht.
   Bei einer Inkonsistenz: **nicht automatisch ändern** — melden, da eine Korrektur hier das
   Release-Tagging-Verfahren selbst betrifft, eine Entscheidung des Nutzers.
2. **Secrets-Handling prüfen:**
   ```sh
   grep -n "RELEASE_KEYSTORE\|RELEASE_KEY" app/build.gradle.kts .github/workflows/release.yml
   git log --all --source -- "*.jks" "*.keystore" 2>&1 | head -5
   ```
   Bestätigen: kein Keystore je committet, alle Secrets kommen aus `System.getenv(...)` bzw.
   `secrets.*` in der GitHub-Actions-Workflow-Syntax, nie als Literal im Code.
3. **`runCatching`-Stellen inventarisieren:**
   ```sh
   grep -rn "runCatching" app/src/main/java
   ```
   Für jede Fundstelle (`SwitcherService.openSystemSettings`, `SettingsActivity.openUrl`,
   `UpdateChecker.checkInBackground`'s `fetchLatestRelease`-Aufruf, `SwitcherOverlay.dismiss`,
   `AccessibilityAppSource.iconOf`/`labelOf`) im Report notieren: fängt sie eine erwartbare,
   dokumentierte Fehlerquelle ab (gut) oder verschluckt sie etwas, das den Nutzer eigentlich
   informieren sollte (Kandidat für eine spätere, vom Nutzer zu entscheidende Änderung)?
   **Keine dieser Stellen in diesem Schritt verändern** — jede hätte ein beobachtbares
   Verhalten (ein Toast mehr/weniger, ein Log-Eintrag), was gegen die eiserne Regel verstößt.
4. Report schreiben: `docs/hardening/CONFIG-REVIEW.md` mit drei Abschnitten
   (Versionierung, Secrets, Fehlerbehandlung), je mit Fundstelle, Bewertung, und — falls
   zutreffend — einer expliziten Empfehlung „Diskussion mit Nutzer erforderlich, keine
   automatische Änderung in diesem Plan".

## 4. Nicht anfassen

- Keine `runCatching`-Stelle, keine Signing-Config, keine Versions-Konstante in diesem
  Schritt ändern. Dieser Schritt produziert ausschließlich `CONFIG-REVIEW.md`.
- Falls eine echte Inkonsistenz gefunden wird: nicht reparieren, nur dokumentieren und an
  den Nutzer melden.

## 5. Akzeptanzkriterien

- `docs/hardening/CONFIG-REVIEW.md` existiert mit allen drei Abschnitten.
- `./tools/verify.sh` grün (unverändert, da keine Produktivcode-Datei angefasst wurde).
- `git diff --stat` zeigt für diesen Schritt ausschließlich die neue Report-Datei.

## 6. Gate

`./tools/verify.sh`. Da keine Produktivcode-Änderung erfolgt, ist ein Rot hier
unwahrscheinlich — trotzdem ausführen, um sicherzustellen, dass der Review-Schritt selbst
keine versehentliche Datei verändert hat.

## 7. Commit-Message-Vorschlag

```
docs: add configuration and error-handling review report

Pure review: versioning/tag-format consistency, secrets handling, and
every runCatching site are inventoried in CONFIG-REVIEW.md. No code
changed - any real finding is flagged for the user's decision.
```
