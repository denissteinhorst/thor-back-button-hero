#!/usr/bin/env python3
# SPDX-License-Identifier: 0BSD
"""In-place update on the attached Thor, inside the recovery transaction (S7, oracle F5).

  python3 tools/devicetest/upgrade.py [--serial SERIAL] [--apk CANDIDATE.apk]

Without --apk the candidate is a fresh debug build of the working tree, installed first as the session's
arm and then updated over itself: a baseline-to-baseline update that validates the orchestration (the
owner's installed build may predate the receiver's read-only `status` op the transaction relies on). Three stores are put in place before the update, each from a frozen
fixture in app/src/test/resources/storage:

- populated: every entry written one by one through the app's own SharedPreferences (debug receiver),
  covering string, int, long and boolean, plus one float probe the fixture family does not contain;
- legacy: a synthesized pre-1.1 file written while the app is stopped; the app migrates it when it
  starts, and the update must keep the migrated entries (expected: legacy-v1.txt);
- fresh: the app's own reset, which applies a fresh install's defaults (expected: fresh.txt).

Each update is observed BEFORE the prerequisites are restored: the service must still be enabled and
bind again by itself, and the process must have been replaced. Then the typed raw entries must equal
the expected ones exactly. Nothing is uninstalled or cleared; the restore puts the original build and
the owner's settings back and verifies both. Raw observations go to app/build/devicetest/.
"""

import argparse
import json
import os
import re
import shlex
import sys
import time
from xml.sax.saxutils import escape, quoteattr

sys.dont_write_bytecode = True

import subprocess  # noqa: E402

from dt.adb import JAVA_HOME, REPO  # noqa: E402
from dt.device import ACTION, PKG, SERVICE, Device, parse_prefs, prefs_differences  # noqa: E402

APP_APK = os.path.join(REPO, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
FIXTURES = os.path.join(REPO, "app", "src", "test", "resources", "storage")
FLOAT_PROBE = ("s7_float_probe", ("float", "0.25"))
# The input of StoredDataContractTest's pre-1.1 migration test, entry for entry.
LEGACY_INPUT = {
    "single_action": ("string", "back"),
    "double_enabled": ("boolean", "false"),
    "tap_hold_enabled": ("boolean", "true"),
    "toggle_buttons": ("string", "l3"),
    "toggle_hold_ms": ("long", "1700"),
    "r2_package": ("string", "@swap_screens"),
    "app_exceptions": ("string", "com.example.game\t15\tGame\ttrue\nnot a valid line"),
    "hold_ms": ("long", "1300"),
}
BIND_TIMEOUT_S = 20


def unescape(text):
    """Reverses the fixture writer's escaping of backslash, newline and tab."""
    out, i = [], 0
    while i < len(text):
        if text[i] == "\\" and i + 1 < len(text):
            out.append({"n": "\n", "t": "\t", "\\": "\\"}.get(text[i + 1], "\\" + text[i + 1]))
            i += 2
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def read_fixture(text):
    """A storage fixture as name -> (XML tag, value text), the shape parse_prefs gives."""
    entries = {}
    for line in text.splitlines():
        if not line:
            continue
        key, kind, value = line.split("\t", 2)
        if kind not in ("string", "int", "long", "boolean", "float"):
            raise ValueError(f"fixture entry {key!r} has unsupported type {kind!r}")
        if key in entries:
            raise ValueError(f"fixture entry {key!r} appears twice")
        entries[key] = (kind, unescape(value))
    return entries


def load(name):
    with open(os.path.join(FIXTURES, name)) as f:
        return read_fixture(f.read())


def prefs_file(entries):
    """A SharedPreferences XML file holding exactly [entries]."""
    lines = ["<?xml version='1.0' encoding='utf-8' standalone='yes' ?>", "<map>"]
    for key, (kind, value) in sorted(entries.items()):
        if kind == "string":
            lines.append(f"    <string name={quoteattr(key)}>{escape(value)}</string>")
        else:
            lines.append(f"    <{kind} name={quoteattr(key)} value={quoteattr(value)} />")
    lines.append("</map>")
    return "\n".join(lines) + "\n"


def escape_value(text):
    return text.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")


def seed_script(entries):
    """One broadcast that writes every entry in a single commit through the app's SharedPreferences;
    its reply must count them all. Pushed as a file: the full store exceeds a comfortable command line."""
    text = "".join(f"{key}\t{kind}\t{escape_value(value)}\n" for key, (kind, value) in sorted(entries.items()))
    return (f"am broadcast -a {ACTION} -p {PKG} --es op putall --es entries {shlex.quote(text)}"
            " 2>&1\n")


def seed_count(reply):
    """The number of entries the app wrote, or None for any other reply."""
    m = re.search(r'data="OK (\d+)"', reply)
    return int(m.group(1)) if m else None


def judge(observation):
    """The update's failures as read before any repair: an empty list means it kept the service."""
    problems = []
    if not observation.get("enabled"):
        problems.append("the service was no longer enabled after the update")
    if observation.get("accessibility_enabled") != "1":
        problems.append(f"accessibility_enabled was {observation.get('accessibility_enabled')!r} after the update")
    if not observation.get("bound"):
        problems.append(f"the service did not bind again within {BIND_TIMEOUT_S} s without help")
    if not observation.get("process_replaced"):
        problems.append("the app's process was not replaced, so no cold start was observed")
    return problems


def observe(d, pid_before):
    """Reads the service state as the update left it, without touching anything."""
    enabled = SERVICE in d.sh("settings get secure enabled_accessibility_services").split(":")
    access = d.sh("settings get secure accessibility_enabled")
    start = time.monotonic()
    bound = False
    while time.monotonic() - start < BIND_TIMEOUT_S:
        if d.service_bound():
            bound = True
            break
        time.sleep(0.25)
    pid = d.sh(f"pidof {PKG}")
    return {"enabled": enabled, "accessibility_enabled": access, "bound": bound,
            "bound_after_s": round(time.monotonic() - start, 2) if bound else None,
            "pid_before": pid_before, "pid_after": pid, "process_replaced": bool(pid) and pid != pid_before}


def settled(d, expected, timeout=15):
    """Waits until the settings file on disk holds exactly [expected]; returns the differences left."""
    deadline = time.monotonic() + timeout
    while True:
        differences = prefs_differences(expected, parse_prefs(d.prefs_xml()))
        if not differences or time.monotonic() > deadline:
            return differences
        time.sleep(0.5)


def update(d, apk, expected):
    pid = d.sh(f"pidof {PKG}")
    observation = d.install(apk, observe=lambda: observe(d, pid))
    after = parse_prefs(d.prefs_xml())
    return {"observation": observation, "problems": judge(observation),
            "differences": prefs_differences(expected, after), "entries": len(after)}


def scenario_populated(d, apk):
    expected = dict(load("populated.txt"))
    expected[FLOAT_PROBE[0]] = FLOAT_PROBE[1]
    d.app("reset")
    # With the service bound, one commit of every entry runs each of its preference listeners on the
    # main thread and outlasts the broadcast timeout. The store is an existing install's, so it is
    # written with the service off, and the service is on again before the update.
    enabled = [s for s in d.sh("settings get secure enabled_accessibility_services").split(":") if s not in ("", "null")]
    d.sh("settings put secure enabled_accessibility_services " + shlex.quote(":".join(s for s in enabled if s != SERVICE) or "null"))
    d.eventually(lambda: not d.service_bound(), 15, "the service did not unbind for seeding")
    remote = "/data/local/tmp/bbh-seed.sh"
    d.adb.push_text(seed_script(expected), remote)
    reply = d.sh(f"sh {remote}", timeout=120)
    d.sh(f"rm {remote}")
    d.reconnect()
    if seed_count(reply) != len(expected):
        return {"seed_failures": [f"the app answered {reply[-300:]!r} for {len(expected)} entries"]}
    pending = settled(d, expected)
    if pending:
        return {"seed_failures": ["not on disk: " + p for p in pending[:10]]}
    return update(d, apk, expected)


def scenario_legacy(d, apk):
    d.restore_prefs_file(prefs_file(LEGACY_INPUT))
    expected = load("legacy-v1.txt")
    pending = settled(d, expected)
    if pending:
        return {"seed_failures": ["the start-up migration differs: " + p for p in pending[:10]]}
    return update(d, apk, expected)


def scenario_fresh(d, apk):
    d.app("reset")
    expected = load("fresh.txt")
    pending = settled(d, expected)
    if pending:
        return {"seed_failures": ["the reset differs: " + p for p in pending[:10]]}
    return update(d, apk, expected)


SCENARIOS = {"populated": scenario_populated, "legacy": scenario_legacy, "fresh": scenario_fresh}


def passed(result):
    return not result.get("seed_failures") and not result.get("problems") and not result.get("differences")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial")
    parser.add_argument("--apk", help="candidate APK (default: a fresh debug build of the working tree)")
    parser.add_argument("--only", choices=sorted(SCENARIOS), action="append")
    args = parser.parse_args()
    apk = args.apk or APP_APK
    if not args.apk:
        subprocess.run(["./gradlew", "assembleDebug", "--console=plain", "-q"], cwd=REPO,
                       env=dict(os.environ, JAVA_HOME=JAVA_HOME), check=True)
    d = Device(args.serial)
    d.prepare()
    d.begin()
    results = {}
    try:
        d.install(apk)
        for name in args.only or list(SCENARIOS):
            print(f"-- {name}")
            results[name] = SCENARIOS[name](d, apk)
    finally:
        restored = d.end()
    out_dir = os.path.join(REPO, "app", "build", "devicetest")
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "upgrade-result.json"), "w") as f:
        json.dump(results, f, indent=2, sort_keys=True)
    ok = 0
    for name, result in results.items():
        good = passed(result)
        ok += good
        print(("✓ " if good else "✗ ") + name, json.dumps(result.get("observation")) if good else "")
        for line in result.get("seed_failures", []) + result.get("problems", []) + result.get("differences", [])[:10]:
            print("    " + line)
    print(f"{ok} of {len(results)} updates kept the service and the stored data; restored: {restored}")
    sys.exit(0 if ok == len(results) and restored else 1)


if __name__ == "__main__":
    main()
