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
