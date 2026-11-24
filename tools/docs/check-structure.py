#!/usr/bin/env python3
# SPDX-License-Identifier: 0BSD
"""docs/PROJECT-STRUCTURE.md names every tracked source file, and names no file that is gone.

  python3 tools/docs/check-structure.py    exit 1 with the missing and the stale names
"""

import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCES = re.compile(r"^app/src/(main|androidTest)/.*\.(kt|c)$")


def main():
    tracked = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, check=True).stdout.split()
    names = {pathlib.PurePath(p).name for p in tracked if SOURCES.match(p)}
    doc = (ROOT / "docs" / "PROJECT-STRUCTURE.md").read_text(encoding="utf-8")
    listed = set(re.findall(r"`([\w$.-]+\.(?:kt|c))`", doc))
    missing, stale = sorted(names - listed), sorted(listed - names)
    for name in missing:
        print(f"not in PROJECT-STRUCTURE.md: {name}")
    for name in stale:
        print(f"listed but gone: {name}")
    return 1 if missing or stale else 0


if __name__ == "__main__":
    sys.exit(main())
