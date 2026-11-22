# SPDX-License-Identifier: 0BSD
"""Mechanical package move (refactoring R1). Moves Kotlin files into a subpackage of the app package,
rewrites only their `package` line, then adds the imports every source file now needs. Nothing below
a file's import header is touched; tools/contract/move-only.sh proves that afterwards.

Imports: a moved file gets an explicit import for every top-level name of another package that appears
as a word in its body (plus R/BuildConfig); every other file gets one for each moved name it mentions.
An explicit import outranks both same-package and star-imported names in Kotlin, so a moved name
keeps resolving to the same declaration it resolved to before. A word that only appears in a comment
or string can add an unused import, which is harmless.

Usage: movepkg.py <subpackage> <File.kt> [File.kt ...]     (subpackage like "input" or "recents.top")
"""
import os
import re
import subprocess
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ROOT_PKG = "dev.steinhorst.backbuttonhero"
SETS = ["app/src/main/java", "app/src/debug/java", "app/src/test/java", "app/src/androidTest/java"]
MODIFIERS = r"(?:(?:public|internal|private|data|sealed|enum|abstract|open|inline|value|annotation|const|fun interface|lateinit)\s+)*"
DECL = re.compile(r"^" + MODIFIERS + r"(class|object|interface|typealias|fun|val|var)\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(`?\w+`?)", re.M)
HEADER = re.compile(r"^(package\s|import\s|\s*$)")
GENERATED = ["R", "BuildConfig"]


def files():
    out = []
    for s in SETS:
        for d, _, names in os.walk(os.path.join(REPO, s)):
            out += [os.path.join(d, n) for n in names if n.endswith(".kt")]
    return out


def split(text):
    lines = text.splitlines(keepends=True)
    i = 0
    while i < len(lines) and HEADER.match(lines[i]):
        i += 1
    return lines[:i], "".join(lines[i:])


def package_of(text):
    m = re.search(r"^package\s+([\w.]+)", text, re.M)
    return m.group(1) if m else ""


def declarations(text):
    """Top-level, non-private names a file declares (column-0 declarations only)."""
    names = set()
    body = split(text)[1]
    for m in DECL.finditer(body):
        line = body[body.rfind("\n", 0, m.start()) + 1:body.find("\n", m.start())]
        if line.lstrip().startswith("private "):
            continue
        names.add(m.group(2).strip("`"))
    return names


def add_imports(path, needed):
    text = open(path, encoding="utf-8").read()
    head, body = split(text)
    present = {l.split()[1] for l in head if l.startswith("import ")}
    new = sorted(n for n in needed if n not in present)
    if not new:
        return False
    imports = sorted(present | set(new))
    pkg_line = [l for l in head if l.startswith("package ")]
    out = "".join(pkg_line) + "\n" + "".join(f"import {i}\n" for i in imports) + "\n" + body
    open(path, "w", encoding="utf-8").write(out)
    return True


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    sub, targets = argv[1], argv[2:]
    new_pkg = f"{ROOT_PKG}.{sub}"
    all_files = files()
    by_name = {}
    for p in all_files:
        by_name.setdefault(os.path.basename(p), []).append(p)
    for t in targets:
        if len(by_name.get(t, [])) != 1:
            print(f"ERROR {t}: {len(by_name.get(t, []))} matches")
            return 2
    moved = set()
    for t in targets:
        src = by_name[t][0]
        set_root = next(s for s in SETS if src.startswith(os.path.join(REPO, s)))
        dst_dir = os.path.join(REPO, set_root, *new_pkg.split("."))
        os.makedirs(dst_dir, exist_ok=True)
        dst = os.path.join(dst_dir, t)
        subprocess.run(["git", "-C", REPO, "mv", src, dst], check=True)
        text = open(dst, encoding="utf-8").read()
        text = re.sub(r"^package\s+[\w.]+", f"package {new_pkg}", text, count=1, flags=re.M)
        open(dst, "w", encoding="utf-8").write(text)
        moved.add(dst)

    all_files = files()
    owner = {}
    for p in all_files:
        text = open(p, encoding="utf-8").read()
        pkg = package_of(text)
        for n in declarations(text):
            if n in owner and owner[n] != pkg:
                print(f"ERROR top-level name {n} declared in {owner[n]} and {pkg}")
                return 2
            owner[n] = pkg
    for g in GENERATED:
        owner[g] = ROOT_PKG
    moved_names = {n for p in moved for n in declarations(open(p, encoding="utf-8").read())}
    changed = 0
    for p in all_files:
        text = open(p, encoding="utf-8").read()
        pkg = package_of(text)
        words = set(re.findall(r"\b\w+\b", split(text)[1]))
        relevant = words if p in moved else words & moved_names
        needed = {f"{owner[n]}.{n}" for n in relevant if n in owner and owner[n] != pkg}
        changed += add_imports(p, needed)
    print(f"moved {len(targets)} file(s) to {new_pkg}; imports added in {changed} file(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
