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
