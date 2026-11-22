#!/usr/bin/env python3
# SPDX-License-Identifier: 0BSD
"""The instrumented tests (app/src/androidTest) on the attached Thor, inside the recovery transaction.

  python3 tools/devicetest/instrument.py [--serial SERIAL]

connectedDebugAndroidTest is not used: it installs on every attached device and uninstalls the app
afterwards, which would take the owner's settings and the transaction's own copy with it. Instead the
freshly built debug APK becomes an arm of the transaction (signature checked, data kept), the test APK
is installed beside it and removed again, and `am instrument` runs every expected test method. The
restore puts the original build and the owner's settings back and verifies both.
"""

import argparse
import os
import re
import subprocess
import sys

sys.dont_write_bytecode = True

from dt.adb import JAVA_HOME, REPO, WORK  # noqa: E402
from dt.device import PKG, Device  # noqa: E402

TEST_PKG = f"{PKG}.test"
RUNNER = f"{TEST_PKG}/androidx.test.runner.AndroidJUnitRunner"
SOURCES = os.path.join(REPO, "app", "src", "androidTest", "java")
APP_APK = os.path.join(REPO, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
TEST_APK = os.path.join(REPO, "app", "build", "outputs", "apk", "androidTest", "debug", "app-debug-androidTest.apk")


def expected_tests():
    """Class#method for every @Test in the instrumented sources, read from the sources themselves."""
    tests = set()
    for root, _, files in os.walk(SOURCES):
        for name in files:
            if not name.endswith(".kt"):
                continue
            text = open(os.path.join(root, name)).read()
            cls = re.search(r"^package ([\w.]+)", text, re.M).group(1) + "." + name[:-3]
            for m in re.finditer(r"@Test\s+fun\s+(`[^`]+`|\w+)", text):
                tests.add(f"{cls}#{m.group(1).strip('`')}")
    return tests


def results(output):
    """(passed, failed) Class#method sets from `am instrument -r` raw output."""
    passed, failed, current = set(), set(), {}
    for line in output.splitlines():
        m = re.match(r"INSTRUMENTATION_STATUS: (class|test)=(.*)", line)
        if m:
            current[m.group(1)] = m.group(2)
        m = re.match(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line)
        if m and "class" in current and "test" in current:
            name = f"{current['class']}#{current['test']}"
            code = int(m.group(1))
            if code == 0:
                passed.add(name)
            elif code in (-1, -2):
                failed.add(name)
    return passed - failed, failed


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial")
    args = parser.parse_args()
    expected = expected_tests()
    subprocess.run(["./gradlew", "assembleDebug", "assembleDebugAndroidTest", "--console=plain", "-q"],
                   cwd=REPO, env=dict(os.environ, JAVA_HOME=JAVA_HOME), check=True)
    d = Device(args.serial)
    d.prepare()
    d.begin()
    output = ""
    try:
        d.install(APP_APK)
        d.adb.run("install", "-r", "-t", TEST_APK, timeout=180)
        output = d.adb.run("shell", "am", "instrument", "-w", "-r", RUNNER, check=False, timeout=600)
    finally:
        d.adb.run("uninstall", TEST_PKG, check=False)
        restored = d.end()
    os.makedirs(WORK, exist_ok=True)
    raw = os.path.join(WORK, "instrument-output.txt")
    with open(raw, "w") as f:
        f.write(output)
    passed, failed = results(output)
    missing = expected - passed - failed
    unexpected = (passed | failed) - expected
    for name in sorted(passed):
        print(f"✓ {name}")
    for name in sorted(failed):
        print(f"✗ {name}")
    for stack in re.findall(r"INSTRUMENTATION_STATUS: stack=(.*?)(?=\nINSTRUMENTATION_STATUS)", output, re.S):
        print("    " + "\n    ".join(stack.strip().splitlines()[:8]))
    for name in sorted(missing):
        print(f"– {name} (expected, did not run)")
    for name in sorted(unexpected):
        print(f"? {name} (ran, not found in the sources)")
    print(f"raw output: {raw}")
    print(f"{len(passed)} passed, {len(failed)} failed, {len(missing)} missing of {len(expected)} expected; restored: {restored}")
    sys.exit(0 if not failed and not missing and not unexpected and restored else 1)


if __name__ == "__main__":
    main()
