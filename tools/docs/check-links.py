#!/usr/bin/env python3
# SPDX-License-Identifier: 0BSD
"""Every relative link and #anchor in docs/, README.md, AGENTS.md and CLAUDE.md resolves.

  python3 tools/docs/check-links.py        exit 1 and one line per broken link
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
LINK = re.compile(r"(?<!!)\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)|!\[[^\]]*\]\(([^)\s]+)\)")
FENCE = re.compile(r"^(```|~~~)")


def anchors(path, cache={}):
    if path not in cache:
        found, counts, fenced = set(), {}, False
        for line in path.read_text(encoding="utf-8").splitlines():
            if FENCE.match(line):
                fenced = not fenced
            if fenced or not line.startswith("#"):
                continue
            text = line.lstrip("#").strip().lower()
            text = re.sub(r"[`*_]|\[([^\]]*)\]\([^)]*\)", r"\1", text)
            slug = re.sub(r"[^\w\- ]", "", text).replace(" ", "-")
            n = counts.get(slug, 0)
            counts[slug] = n + 1
            found.add(slug if n == 0 else f"{slug}-{n}")
        cache[path] = found
    return cache[path]


def files():
    # The ADR template's links are placeholders.
    yield from sorted(p for p in (ROOT / "docs").rglob("*.md") if p.name != "0000-template.md")
    for name in ("README.md", "AGENTS.md", "CLAUDE.md"):
        if (ROOT / name).exists():
            yield ROOT / name


def main():
    broken = []
    for doc in files():
        fenced = False
        for number, line in enumerate(doc.read_text(encoding="utf-8").splitlines(), 1):
            if FENCE.match(line):
                fenced = not fenced
            if fenced:
                continue
            for match in LINK.finditer(re.sub(r"`[^`]*`", "", line)):
                target = match.group(1) or match.group(2)
                if re.match(r"[a-z]+:", target):
                    continue
                path, _, anchor = target.partition("#")
                resolved = (doc.parent / path).resolve() if path else doc
                # Above the repository root is a GitHub web route (../../releases), not a file.
                if ROOT not in resolved.parents and resolved != ROOT:
                    continue
                where = f"{doc.relative_to(ROOT)}:{number}"
                if not resolved.exists():
                    broken.append(f"{where}: missing {target}")
                elif anchor and resolved.suffix == ".md" and anchor.lower() not in anchors(resolved):
                    broken.append(f"{where}: no anchor #{anchor} in {resolved.relative_to(ROOT)}")
    for line in broken:
        print(line)
    return 1 if broken else 0


if __name__ == "__main__":
    sys.exit(main())
