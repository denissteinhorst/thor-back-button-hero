#!/usr/bin/env bash
# SPDX-License-Identifier: 0BSD
# Move-only proof (R1): compiles the base revision in a disposable worktree (cached per commit under
# app/build/move-only/), compiles this tree, and runs moveonly.py on main and unit-test classes.
# Usage: tools/contract/move-only.sh [base-rev]   (default: the R1 base recorded below)
set -uo pipefail
cd "$(dirname "$0")/../.."
repo="$(pwd)"
rev="$(git rev-parse "${1:-f3f8dbf}")" || exit 2

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
export JAVA_HOME
out=app/build/intermediates/built_in_kotlinc
tasks="compileDebugKotlin compileDebugUnitTestKotlin compileDebugAndroidTestKotlin"
cache="$repo/app/build/move-only/$rev"

if [ ! -d "$cache/debug" ]; then
  wt="$(mktemp -d)/base"
  git worktree add -q --detach "$wt" "$rev" || exit 2
  cp local.properties "$wt/" 2>/dev/null
  # shellcheck disable=SC2086
  if ! (cd "$wt" && ./gradlew -q --console=plain $tasks -x installGitHooks >/dev/null); then
    git worktree remove --force "$wt"; echo "SETUP base build failed"; exit 2
  fi
  mkdir -p "$cache"
  for v in debug debugUnitTest debugAndroidTest; do cp -R "$wt/$out/$v" "$cache/$v"; done
  git worktree remove --force "$wt"
fi

# shellcheck disable=SC2086
./gradlew -q --console=plain $tasks >/dev/null || { echo "SETUP candidate build failed"; exit 2; }
code=0
for v in debug debugUnitTest debugAndroidTest; do
  task="compile$(echo "${v:0:1}" | tr a-z A-Z)${v:1}Kotlin"
  [ "$v" = debug ] && task=compileDebugKotlin
  echo "== $v"
  python3 -I tools/contract/moveonly.py "$cache/$v/$task/classes" "$out/$v/$task/classes" "$rev" $([ "$v" = debug ] || echo --tests)
  c=$?
  [ $c -gt $code ] && code=$c
done
exit $code
