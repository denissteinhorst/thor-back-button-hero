#!/usr/bin/env bash
# SPDX-License-Identifier: 0BSD
# S8 sensitivity drills for the R1 move-only protection. Each drill applies one compiling source
# mutant to a disposable worktree of HEAD (never this checkout), rebuilds only what its check
# reads, and must be KILLED by the named check. A mutant that does not build is INVALID, never
# KILLED: a broken build proves nothing about the check.
# Usage: tools/contract/drills.sh [drill ...]     (default: all)
set -uo pipefail
cd "$(dirname "$0")/../.."
repo="$(pwd)"

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
export JAVA_HOME

base="$(mktemp -d)"
cleanup() {
  for wt in "$base"/*/; do [ -d "$wt" ] && git -C "$repo" worktree remove --force "$wt" >/dev/null 2>&1; done
  rm -rf "$base"
}
trap cleanup EXIT
pkg=app/src/main/java/dev/steinhorst/backbuttonhero

# Rename a Kotlin symbol by word boundary in every source, test and manifest file, and its file.
rename_symbol() {  # rename_symbol <old> <new>
  grep -rlw --include='*.kt' --include='*.xml' "$1" app/src | xargs perl -pi -e "s/\\b$1\\b/$2/g"
  git mv "$pkg/$1.kt" "$pkg/$2.kt"
}

# --- mutants --------------------------------------------------------------------------------------
# Each prints the check ids and Gradle tasks it needs, after applying itself in the worktree.

m_component_renamed() {  # a frozen manifest component moved, all references kept consistent
  rename_symbol SwitcherService SwitcherServiceMoved
  echo "A4|assembleDebug assembleRelease"
}
m_root_moved_string_kept() {  # a root entry point moved to a subpackage; the app_process name still says the old one
  mkdir -p "$pkg/recents"
  git mv "$pkg/TaskRemover.kt" "$pkg/recents/TaskRemover.kt"
  perl -pi -e 's/^package dev\.steinhorst\.backbuttonhero$/package dev.steinhorst.backbuttonhero.recents\n\nimport dev.steinhorst.backbuttonhero.RecentTaskQuery\nimport dev.steinhorst.backbuttonhero.Shell/' \
    "$pkg/recents/TaskRemover.kt"
  echo "A4|assembleDebug assembleRelease"
}
m_root_main_lost() {  # a root entry point keeps its name but main() stops being static
  perl -0pi -e 's/\@JvmStatic\n(\s*fun main)/$1/' "$pkg/TaskRemover.kt"
  echo "A4|assembleDebug assembleRelease"
}
m_ratchet_selector_empty() {  # a ratcheted class renamed; its coverage rule now selects nothing
  rename_symbol LevelSteps LevelStepsMoved
  echo "A8|testDebugUnitTest jacocoTestReport"
}
m_test_class_moved_unmapped() {  # a test class renamed without a reviewed renames.txt entry
  local t=app/src/test/java/dev/steinhorst/backbuttonhero
  perl -pi -e 's/\bclass DoubleTapDetectorTest\b/class DoubleTapDetectorMovedTest/' "$t/DoubleTapDetectorTest.kt"
  git mv "$t/DoubleTapDetectorTest.kt" "$t/DoubleTapDetectorMovedTest.kt"
  echo "A7|testDebugUnitTest"
}
m_wire_string_changed() {  # a moved class drags a wire string along (broadcast action renamed)
  local value
  value="$(awk -F'\t' '$1=="release" && $2 ~ /^dev\.steinhorst\.backbuttonhero\./ {print $2; exit}' tools/contract/wire-strings.txt)"
  grep -rl --include='*.kt' --include='*.xml' -F "\"$value\"" app/src/main | xargs perl -pi -e "s/\\Q$value\\E/${value}_MOVED/g"
  echo "A5|assembleDebug assembleRelease"
}

ALL=(m_component_renamed m_root_moved_string_kept m_root_main_lost m_ratchet_selector_empty
     m_test_class_moved_unmapped m_wire_string_changed)
[ $# -gt 0 ] && ALL=("$@")

bad=0
for drill in "${ALL[@]}"; do
  wt="$base/$drill"
  git -C "$repo" worktree add -q --detach "$wt" HEAD || { echo "INVALID  $drill: worktree"; bad=1; continue; }
  cp "$repo/local.properties" "$wt/" 2>/dev/null
  cp -R "$repo/tools/contract/." "$wt/tools/contract/"  # the checks under test, not HEAD's copy
  spec="$(cd "$wt" && "$drill")"
  ids="${spec%%|*}" tasks="${spec#*|}"
  log="$base/$drill.log"
  if [ -z "$(git -C "$wt" status --porcelain -- app)" ]; then
    echo "INVALID  $drill: mutant changed nothing"; bad=1; continue
  fi
  # shellcheck disable=SC2086
  if ! (cd "$wt" && ./gradlew -q --console=plain $tasks -x installGitHooks >"$log" 2>&1); then
    echo "INVALID  $drill: mutant does not build or its tests fail ($tasks)"; grep -m5 -E '^e: |error:|FAILED' "$log" | sed 's/^/         /'
    bad=1; continue
  fi
  out="$(cd "$wt" && CONTRACT_GRADLE_FRESH=1 python3 -I tools/contract/contract.py check --phase R --only "$ids" 2>&1)"
  code=$?
  case $code in
    1) echo "KILLED   $drill by $ids: $(echo "$out" | grep -m1 -E '^ *(FAIL|-)' | sed 's/^ *//')" ;;
    0) echo "SURVIVED $drill: $ids passed"; bad=1 ;;
    *) echo "INVALID  $drill: $ids exit $code"; echo "$out" | tail -3 | sed 's/^/         /'; bad=1 ;;
  esac
  git -C "$repo" worktree remove --force "$wt" >/dev/null 2>&1
done
exit $bad
