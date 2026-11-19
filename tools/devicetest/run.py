#!/usr/bin/env python3
"""The complete on-device test of Back Button Hero, driven over adb on a connected AYN Thor.

  python3 tools/devicetest/run.py --full            every area (run on request; takes a while)
  python3 tools/devicetest/run.py --only ayn,media  selected areas
  python3 tools/devicetest/run.py --changed         areas the current branch's changes can affect
  python3 tools/devicetest/run.py --list            the areas and their tests
  python3 tools/devicetest/run.py --restore         put back settings a crashed run left behind

Needs the debug build installed (its test receiver configures the app), USB debugging and AYN's
pservice (SELinux permissive, the Thor's default). The owner's app settings and every system value
the tests touch are saved first and restored afterwards, also when a test fails. See
docs/DEVICE-TEST.md.
"""

import argparse
import subprocess
import sys
import time

sys.dont_write_bytecode = True

from dt import runner  # noqa: E402
from dt.adb import REPO  # noqa: E402
from dt.device import Device  # noqa: E402
import suites  # noqa: E402,F401 - registers the tests in order


def changed_files():
    base = subprocess.run(["git", "-C", REPO, "merge-base", "HEAD", "main"], capture_output=True, text=True).stdout.strip()
    names = set()
    for args in (["diff", "--name-only", base], ["diff", "--name-only"], ["ls-files", "--others", "--exclude-standard"]):
        out = subprocess.run(["git", "-C", REPO, *args], capture_output=True, text=True).stdout
        names.update(l for l in out.splitlines() if l.startswith("app/src/main/"))
    return sorted(names)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--full", action="store_true")
    mode.add_argument("--only")
    mode.add_argument("--changed", action="store_true")
    mode.add_argument("--list", action="store_true")
    mode.add_argument("--restore", action="store_true")
    parser.add_argument("--serial")
    args = parser.parse_args()

    if args.list:
        areas = {}
        for a, title, _ in runner.TESTS:
            areas.setdefault(a, []).append(title)
        for a, titles in areas.items():
            print(f"\n{a}")
            print("\n".join(f"  - {t}" for t in titles))
        return 0

    selected = None
    if args.only:
        selected = set(args.only.split(","))
    elif args.changed:
        files = changed_files()
        selected = runner.areas_for(files)
        print("Changed: " + (", ".join(files) or "nothing"))
        if selected is not None and not selected:
            print("No on-device area is affected.")
            return 0
    print("Areas: " + ("all" if selected is None else ", ".join(sorted(selected))))

    device = Device(args.serial)
    device.prepare()
    if args.restore:
        print("Restored." if device.end() else "Nothing to restore.")
        return 0

    device.begin()
    try:
        results, path = runner.run(device, selected, f"report-{time.strftime('%Y%m%d-%H%M%S')}.json")
    finally:
        device.log("Restoring the owner's settings …")
        device.end()
    counts = {s: sum(r["status"] == s for r in results) for s in ("PASS", "FAIL", "ERROR", "SKIP")}
    print(f"\n{counts['PASS']} passed, {counts['FAIL']} failed, {counts['ERROR']} errors, {counts['SKIP']} skipped — {path}")
    return 0 if counts["FAIL"] == counts["ERROR"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
