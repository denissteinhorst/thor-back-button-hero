#!/usr/bin/env bash
# SPDX-License-Identifier: 0BSD
# Proves that every static contract check rejects a deliberate fault. Each case runs one check against
# a broken copy (never the repository's own files) and must FAIL; the untouched baseline must PASS.
# Usage: tools/contract/selftest.sh
set -uo pipefail
cd "$(dirname "$0")/../.."

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
export JAVA_HOME
# Same freshness basis as check.sh: Gradle updates the outputs by content first.
./gradlew -q --console=plain assembleDebug assembleRelease testDebugUnitTest jacocoTestReport lintDebug >/dev/null || exit 2
export CONTRACT_GRADLE_FRESH=1

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0

check() {  # check <expected exit> <label> <check id> [VAR=value ...]
  local expected="$1" label="$2" id="$3"
  shift 3
  local out code
  out="$(env "$@" python3 -I tools/contract/contract.py check --only "$id" 2>&1)"
  code=$?
  if [ "$code" -eq "$expected" ]; then
    echo "ok    $label"
  else
    echo "WRONG $label: expected exit $expected, got $code"
    echo "$out" | sed 's/^/      /'
    failures=$((failures + 1))
  fi
}

fixtures() {  # a private copy of the fixtures and lists
  rm -rf "$work/fx" "$work/lists"
  cp -R tools/contract/baseline "$work/fx"
  mkdir "$work/lists" && cp tools/contract/{classes,wire-strings,renames}.txt "$work/lists/"
}

rewrite_apk() {  # rewrite_apk <source> <target> <entry> : flips one byte of <entry>
  python3 -I - "$1" "$2" "$3" <<'EOF'
import sys, zipfile
src, dst, name = sys.argv[1:]
with zipfile.ZipFile(src) as a, zipfile.ZipFile(dst, "w") as b:
    for info in a.infolist():
        data = a.read(info)
        if info.filename == name:
            data = bytes([data[0] ^ 1]) + data[1:]
        b.writestr(info, data)
EOF
}

release=app/build/outputs/apk/release/app-release-unsigned.apk

echo "Baseline:"
# A0 (release DEX unchanged) holds only in S-phases; R-phases change code by design.
if [ "${CONTRACT_PHASE:-S}" = S ]; then all="A0,A1,A2,A3,A4,A5,A6,A7,A8,A9,A10,A11"; else all="A1,A2,A3,A4,A5,A6,A7,A8,A9,A10,A11"; fi
check 0 "unchanged outputs pass every check" "$all"

echo "Faults:"
rewrite_apk "$release" "$work/dex.apk" classes.dex
check 1 "A0 a changed DEX byte" A0 CONTRACT_PHASE=S CONTRACT_RELEASE_APK="$work/dex.apk"

fixtures; printf 'E: uses-permission (line=1)\n  A: android:name="android.permission.CAMERA"\n' >> "$work/fx/manifest-release.txt"
check 1 "A1 an added permission" A1 CONTRACT_FIXTURES="$work/fx"

fixtures; sed -i '' '5d' "$work/fx/resources-release.txt"
check 1 "A2 a changed resource entry" A2 CONTRACT_FIXTURES="$work/fx"

rewrite_apk "$release" "$work/lib.apk" lib/arm64-v8a/libpadhelper.so
check 1 "A3 a changed native helper" A3 CONTRACT_RELEASE_APK="$work/lib.apk"

fixtures; sed -i '' 's/^root TaskRemover$/root TaskRemoverMoved/' "$work/lists/classes.txt"
check 1 "A4 a renamed root entry point" A4 CONTRACT_LISTS="$work/lists"
fixtures; sed -i '' 's/^component SwitcherService$/root SettingsActivity/' "$work/lists/classes.txt"
check 1 "A4 a component without main() listed as root" A4 CONTRACT_LISTS="$work/lists"

fixtures; printf 'release\tdev.steinhorst.backbuttonhero.RENAMED_ACTION\n' >> "$work/lists/wire-strings.txt"
check 1 "A5 a missing wire string" A5 CONTRACT_LISTS="$work/lists"
fixtures; printf 'release\tdev.steinhorst.backbuttonhero.IScreenRecorde\n' >> "$work/lists/wire-strings.txt"
check 1 "A5 a string present only inside a longer one" A5 CONTRACT_LISTS="$work/lists"

cp -R app/src/test/resources/golden "$work/golden"; echo " " >> "$work/golden/settings_main_en_light.txt"
check 1 "A6 an edited golden" A6 CONTRACT_GOLDENS="$work/golden"
rm "$work/golden/settings_main_en_light.txt"
check 1 "A6 a deleted golden" A6 CONTRACT_GOLDENS="$work/golden"
cp -R app/src/test/resources/storage "$work/storage"; echo "x" >> "$work/storage/fresh.txt"
check 1 "A6 an edited stored-data fixture" A6 CONTRACT_STORAGE="$work/storage"
cp -R app/src/test/resources/commands "$work/commands"; echo "x" >> "$work/commands/privileged.txt"
check 1 "A6 an edited command fixture" A6 CONTRACT_COMMANDS="$work/commands"

cp -R app/build/test-results/testDebugUnitTest "$work/results"
python3 -I - "$work/results" <<'EOF'
import glob, sys
path = sorted(glob.glob(sys.argv[1] + "/*BackGestureMachineTest.xml"))[0]
text = open(path).read()
start = text.index("<testcase")
end = text.index("</testcase>", start) + len("</testcase>") if "</testcase>" in text[start:text.index("<testcase", start + 1)] else text.index("/>", start) + 2
open(path, "w").write(text[:start] + text[end:])
EOF
check 1 "A7 a deleted test case" A7 CONTRACT_TEST_RESULTS="$work/results"
rm -rf "$work/results"; cp -R app/build/test-results/testDebugUnitTest "$work/results"
python3 -I - "$work/results" <<'EOF'
import glob, re, sys
path = sorted(glob.glob(sys.argv[1] + "/*PrefsMigrationTest.xml"))[0]
text = open(path).read()
text = re.sub(r'(<testcase [^>]*?)/>', r'\1><failure message="x"/></testcase>', text, count=1)
open(path, "w").write(text)
EOF
check 1 "A7 a failing test case" A7 CONTRACT_TEST_RESULTS="$work/results"

cp app/build.gradle.kts "$work/build.gradle.kts"
sed -i '' 's/"\([a-z.]*\)RecentsSelection" to "1.00"/"\1RecentsSelection" to "0.90"/' "$work/build.gradle.kts"
check 1 "A8 a lowered ratchet" A8 CONTRACT_BUILD_FILE="$work/build.gradle.kts"
cp app/build.gradle.kts "$work/build.gradle.kts"
sed -i '' 's/"\([a-z.]*\)RecentsSelection" to "1.00"/"\1RecentsSelectionGone" to "1.00"/' "$work/build.gradle.kts"
check 1 "A8 a ratchet whose class vanished" A8 CONTRACT_BUILD_FILE="$work/build.gradle.kts"

fixtures; sed -i '' 's/kotlin-stdlib:2.4.21/kotlin-stdlib:2.4.20/' "$work/fx/classpath-release.txt"
check 1 "A9 a changed release dependency" A9 CONTRACT_FIXTURES="$work/fx"

sed 's/const val DEBUG_GESTURES = false/const val DEBUG_GESTURES = true/' \
  app/src/main/java/dev/steinhorst/backbuttonhero/SwitcherService.kt > "$work/SwitcherService.kt"
check 1 "A10 gesture tracing left on" A10 CONTRACT_SWITCHER_SERVICE="$work/SwitcherService.kt"

sed 's#</issues>#<issue id="NewWarning" message="new"/></issues>#' app/build/reports/lint-results-debug.xml > "$work/lint.xml"
check 1 "A11 a new lint finding" A11 CONTRACT_LINT_XML="$work/lint.xml"

echo "Setup:"
check 2 "a missing artifact is BUILD REQUIRED, never PASS" A3 CONTRACT_RELEASE_APK="$work/none.apk"
touch -t 200001010000 "$work/dex.apk"
check 2 "contract.py alone refuses an output older than its inputs" A0 CONTRACT_PHASE=S CONTRACT_GRADLE_FRESH= \
  CONTRACT_FORCE_FRESHNESS=1 CONTRACT_RELEASE_APK="$work/dex.apk"
fixtures; rm "$work/fx/goldens.sha256"
check 2 "a missing fixture is BUILD REQUIRED, never PASS" A6 CONTRACT_FIXTURES="$work/fx"

if [ "$failures" -ne 0 ]; then
  echo "$failures selftest case(s) behaved wrongly"
  exit 1
fi
echo "All selftest cases behaved as intended."
