# Schritt 25 — Branch-Protection-Doku + Dependabot

## 1. Ziel

Dokumentieren, wie die CI-Gate-Kette über GitHub Branch-Protection erzwungen wird (da Hooks
per `--no-verify` umgehbar sind), und automatische Dependency-Updates für Gradle und GitHub
Actions einrichten.

## 2. Voraussetzungen

Schritt 24 ist committet und `./tools/verify.sh` läuft grün.

## 3. Exakte Aufgaben

1. Datei `docs/hardening/BRANCH-PROTECTION.md` anlegen mit exakt dieser Anleitung (GitHub
   erlaubt das nicht per Code/API ohne Repo-Admin-Token — deshalb Dokumentation statt
   Automatisierung):
   ```markdown
   # Branch Protection für `main`

   Hooks (siehe Schritt 22) sind per `git commit --no-verify` umgehbar. Die eigentliche
   Durchsetzung passiert über GitHub Branch Protection, einmalig von einem Repo-Admin
   einzurichten unter **Settings → Branches → Add branch protection rule**:

   - Branch name pattern: `main`
   - ✅ Require a pull request before merging
   - ✅ Require status checks to pass before merging
     - Status check: `verify` (aus `.github/workflows/ci.yml`, Schritt 23)
   - ✅ Require branches to be up to date before merging
   - ✅ Do not allow bypassing the above settings (auch für Admins, falls gewünscht)

   Ohne diese Einstellung ist die CI-Gate-Kette aus Schritt 23 nur eine Anzeige, keine
   Durchsetzung — ein Merge mit rotem `verify`-Check wäre weiterhin möglich.
   ```
2. Datei `.github/dependabot.yml` anlegen:
   ```yaml
   version: 2
   updates:
     - package-ecosystem: "gradle"
       directory: "/"
       schedule:
         interval: "weekly"
       open-pull-requests-limit: 5

     - package-ecosystem: "github-actions"
       directory: "/"
       schedule:
         interval: "weekly"
       open-pull-requests-limit: 5
   ```

## 4. Nicht anfassen

- Branch-Protection selbst wird in diesem Schritt **nicht** über die GitHub-API gesetzt
  (würde Admin-Rechte auf dem Remote-Repo brauchen, die dieser Plan nicht voraussetzt) —
  nur dokumentiert. Falls der Nutzer explizit Admin-Zugriff bestätigt und die Einrichtung
  per `gh api` wünscht, das als separate, vom Nutzer bestätigte Aktion behandeln, nicht
  automatisch in diesem Schritt ausführen.
- Keine Änderung an `.github/workflows/ci.yml` oder `release.yml` in diesem Schritt.

## 5. Akzeptanzkriterien

- `docs/hardening/BRANCH-PROTECTION.md` existiert und benennt den exakten Status-Check-Namen
  (`verify`), der in GitHub als Pflicht-Check ausgewählt werden muss.
- `.github/dependabot.yml` existiert, ist syntaktisch valides YAML
  (`python3 -c "import yaml; yaml.safe_load(open('.github/dependabot.yml'))"` oder
  gleichwertig schlägt nicht fehl).

## 6. Gate

`./tools/verify.sh` (betrifft reine Doku-/Config-Dateien, Build darf dadurch nicht
beeinflusst werden). Bei Rot: unwahrscheinlich für diesen Schritt, da keine Build-Datei
angefasst wird — trotzdem laufen lassen, bei Rot stoppen und melden.

## 7. Commit-Message-Vorschlag

```
docs: document branch protection setup, add Dependabot

Hooks are bypassable with --no-verify; the real enforcement is GitHub
branch protection requiring the verify status check, documented here
for a repo admin to apply manually. Dependabot watches Gradle and
Actions dependencies weekly.
```
