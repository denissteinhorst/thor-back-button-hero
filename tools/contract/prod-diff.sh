#!/usr/bin/env bash
# Fails when production inputs differ from the frozen refactoring baseline.
#
# "Production" is everything that can change the shipped APK: app/src/main/** (Kotlin, C,
# resources, manifest) and every artifact-affecting build input. The comparison covers the
# working tree, including staged, unstaged and untracked (non-ignored) files, so a check
# before committing sees the same state the commit would contain.
#
# app/build.gradle.kts also holds test-only configuration (coverage ratchets, test
# dependencies). It is compared strictly anyway: during the safety-net phases any edit to it
# must be reviewed explicitly rather than silently classified as test-only.
#
# Usage: tools/contract/prod-diff.sh [baseline-commit]
# The baseline defaults to $PROD_BASELINE or the S0 source commit below.
set -euo pipefail
cd "$(dirname "$0")/../.."

baseline="${1:-${PROD_BASELINE:-a032d080db3366fd9cf986d5c6d1764f7e13f313}}"

paths=(
  app/src/main
  app/build.gradle.kts
  app/lint-baseline.xml
  build.gradle.kts
  settings.gradle.kts
  gradle.properties
  gradle/wrapper
  gradle/libs.versions.toml
  gradlew
  gradlew.bat
)

if ! git rev-parse --verify --quiet "${baseline}^{commit}" >/dev/null; then
  echo "prod-diff: baseline ${baseline} is not a commit in this repository" >&2
  exit 2
fi

changed="$(git diff --name-status "$baseline" -- "${paths[@]}")"
untracked="$(git ls-files --others --exclude-standard -- "${paths[@]}" | sed 's/^/?\t/')"
report="$(printf '%s\n%s\n' "$changed" "$untracked" | sed '/^$/d')"

if [ -n "$report" ]; then
  echo "prod-diff: production inputs differ from baseline ${baseline}:" >&2
  echo "$report" >&2
  exit 1
fi
echo "prod-diff: production inputs identical to baseline ${baseline}"
