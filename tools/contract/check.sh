#!/usr/bin/env bash
# SPDX-License-Identifier: 0BSD
# Static contract check (GC). First lets Gradle bring every output the checks read up to date:
# Gradle's content hashing decides freshness, so an unchanged input never forces a rebuild and a
# changed one always does. contract.py run on its own instead refuses outputs older than an input.
# Usage: tools/contract/check.sh [--phase S|R] [--only A1,A3]
set -euo pipefail
cd "$(dirname "$0")/../.."

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
export JAVA_HOME
./gradlew -q --console=plain assembleDebug assembleRelease testDebugUnitTest jacocoTestReport lintDebug >/dev/null
CONTRACT_GRADLE_FRESH=1 exec python3 -I tools/contract/contract.py check "$@"
