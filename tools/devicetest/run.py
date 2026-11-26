#!/usr/bin/env python3
"""The complete on-device test of Back Button Hero, driven over adb on a connected AYN Thor.

  python3 tools/devicetest/run.py --full            every area, layered tests in both of dual mode's layers
  python3 tools/devicetest/run.py --only sources --layers home   chosen areas in the Home layer
  python3 tools/devicetest/run.py --only ayn,media  selected areas
  python3 tools/devicetest/run.py --changed         areas the current branch's changes can affect (both layers
                                                    when they touch dual mode)
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


def changed_files(base_ref="main"):
    """Production files changed since the merge base with [base_ref], plus unstaged and untracked ones."""
    result = subprocess.run(["git", "-C", REPO, "merge-base", "HEAD", base_ref], capture_output=True, text=True)
    base = result.stdout.strip()
    if result.returncode or not base:
        raise SystemExit(f"--changed: no merge base with {base_ref!r}")
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
    parser.add_argument("--base", default="main", help="with --changed: compare against the merge base with this ref "
                        "(default main)")
    parser.add_argument("--layers", help="back, home or back,home: which layers layered tests run in "
                        "(default: both with --full or when --changed touches dual mode, otherwise back)")
    args = parser.parse_args()
    layers = tuple(args.layers.split(",")) if args.layers else None
    if layers and any(layer not in runner.LAYERS for layer in layers):
        parser.error(f"--layers takes {', '.join(runner.LAYERS)}")

    if args.list:
        areas = {}
        for a, title, _ in runner.expand(runner.LAYERS):
            areas.setdefault(a, []).append(title)
        for a, titles in areas.items():
            print(f"\n{a}")
            print("\n".join(f"  - {t}" for t in titles))
        return 0

    selected = None
    if args.only:
        try:
            selected = runner.parse_only(args.only)
        except ValueError as e:
            parser.error(str(e))
    elif args.changed:
        files = changed_files(args.base)
        selected = runner.areas_for(files)
        print("Changed: " + (", ".join(files) or "nothing"))
        for path in runner.unmapped(files):
            print(f"Unmapped, so every area runs: {path}")
        print("Only app/src/main/ is mapped; debug, test, harness and build changes need --only or --full.")
        if selected is not None and not selected:
            print("No on-device area is affected.")
            return 0
    if layers is None:
        # A change that can affect dual mode proves both layers; anything else keeps to Back's.
        both = args.full or (args.changed and (selected is None or "dual" in selected))
        layers = runner.LAYERS if both else ("back",)
    print("Areas: " + ("all" if selected is None else ", ".join(sorted(selected))) + " — layers: " + ", ".join(layers))

    device = Device(args.serial)
    device.prepare()
    if args.restore:
        print("Restored." if device.end() else "Nothing to restore.")
        return 0

    expected_ids = runner.expected(selected, layers)
    device.begin()
    try:
        results, path = runner.run(device, selected, f"report-{time.strftime('%Y%m%d-%H%M%S')}.json", layers,
                                   expected_ids)
    finally:
        device.log("Restoring the owner's settings …")
        device.end()
    code, lines = runner.verdict(results, expected_ids)
    print("\n" + "\n".join(lines) + f"\n{path}")
    return code


if __name__ == "__main__":
    sys.exit(main())
