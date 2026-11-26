# SPDX-License-Identifier: 0BSD
"""Move-only proof for package moves (refactoring R1).

Compares a base revision with the working tree:
  source    every Kotlin file still exists under the same file name, and its text below the
            package/import header is byte-identical;
  bytecode  every compiled class still exists under the same binary simple name, and its
            disassembly is identical once our own package path is erased; so a moved import that
            binds a different symbol (an Android class of the same simple name, say) shows up;
  frozen    every class listed in classes.txt is still compiled in the root package;
  other     no other file under app/src changed.
Simple file and class names must be unique project-wide, which is what makes erasing our package
path safe; the tool refuses to run otherwise.

Usage: moveonly.py <base-classes-dir> <candidate-classes-dir> <base-rev> [--tests]
  --tests: a test source set (no frozen-class check)            (exit 0 pass, 1 fail, 2 setup)
"""
import os
import re
import subprocess
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PKG = "dev/steinhorst/backbuttonhero"
OUR_PATH = re.compile(r"dev[/.]steinhorst[/.]backbuttonhero(?:[/.][a-z][a-z0-9_]*)*[/.](?=[A-Z_$])")
HEADER = re.compile(r"^(package\s|import\s|\s*$)")


class Setup(Exception):
    pass


def body(text):
    """The file below its package/import header (leading blank lines included in the header)."""
    out = text.splitlines(keepends=True)
    i = 0
    while i < len(out) and HEADER.match(out[i]):
        i += 1
    return "".join(out[i:])


def git(*args):
    return subprocess.run(["git", "-C", REPO, *args], check=True, capture_output=True, text=True).stdout


def kotlin_files(paths):
    by_name = {}
    for p in paths:
        if p.endswith(".kt"):
            name = os.path.basename(p)
            if name in by_name:
                raise Setup(f"file name {name} is not unique ({by_name[name]}, {p})")
            by_name[name] = p
    return by_name


def check_sources(base_rev):
    problems = []
    base_paths = git("ls-tree", "-r", "--name-only", base_rev, "app/src").split()
    now_paths = git("ls-files", "--cached", "--others", "--exclude-standard", "app/src").split()
    now_paths = [p for p in now_paths if os.path.exists(os.path.join(REPO, p))]
    base_kt, now_kt = kotlin_files(base_paths), kotlin_files(now_paths)
    for name in sorted(set(base_kt) | set(now_kt)):
        if name not in now_kt:
            problems.append(f"source {name} vanished")
        elif name not in base_kt:
            problems.append(f"source {name} is new")
        else:
            old = git("show", f"{base_rev}:{base_kt[name]}")
            new = open(os.path.join(REPO, now_kt[name]), encoding="utf-8").read()
            if body(old) != body(new):
                problems.append(f"source {name} body changed ({base_kt[name]} -> {now_kt[name]})")
    others_base = {p for p in base_paths if not p.endswith(".kt")}
    others_now = {p for p in now_paths if not p.endswith(".kt")}
    for p in sorted(others_base ^ others_now):
        problems.append(f"non-Kotlin file {'added' if p in others_now else 'removed'}: {p}")
    changed = git("diff", "--name-only", base_rev, "--", *sorted(others_base & others_now)).split() if others_base & others_now else []
    problems += [f"non-Kotlin file changed: {p}" for p in changed]
    return problems


def classes(root):
    found = {}
    for d, _, files in os.walk(root):
        for f in files:
            if f.endswith(".class"):
                if f in found:
                    raise Setup(f"class name {f} is not unique under {root}")
                found[f] = os.path.join(d, f)
    if not found:
        raise Setup(f"no classes under {root}")
    return found


def disassemble(paths):
    """javap per class, with our package path erased."""
    javap = os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "javap")
    out = {}
    for name, path in paths.items():
        text = subprocess.run([javap, "-c", "-p", "-constants", path], check=True, capture_output=True, text=True).stdout
        text = "\n".join(l for l in text.splitlines() if not l.startswith("Compiled from"))
        out[name] = OUR_PATH.sub("BBH/", text)
    return out


def exceptions():
    out = {}
    for line in open(os.path.join(REPO, "tools", "contract", "move-exceptions.txt")):
        if line.strip() and not line.startswith("#"):
            name, kind, _ = line.split(None, 2)
            out[name + ".class"] = kind
    return out


def mask(text, kind):
    """Masks the reviewed difference only: the int constant loaded first in hashCode()."""
    if kind != "data-object-hash":
        raise Setup(f"unknown exception kind {kind}")
    return re.sub(r"(public int hashCode\(\);\n\s+Code:\n\s+0: ldc\s+#\d+\s+// int )-?\d+", r"\1<hash>", text)


def check_bytecode(base_dir, now_dir):
    base, now = classes(base_dir), classes(now_dir)
    allowed = exceptions()
    problems = [f"class {n} vanished" for n in sorted(set(base) - set(now))]
    problems += [f"class {n} is new" for n in sorted(set(now) - set(base))]
    common = {n: base[n] for n in set(base) & set(now)}
    a, b = disassemble(common), disassemble({n: now[n] for n in common})
    for n in allowed:
        if n in common:
            a[n], b[n] = mask(a[n], allowed[n]), mask(b[n], allowed[n])
    problems += [f"class {n} bytecode differs" for n in sorted(common) if a[n] != b[n]]
    return problems, now


def check_frozen(now_classes, now_dir):
    problems = []
    for line in open(os.path.join(REPO, "tools", "contract", "classes.txt")):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        kind, name = line.split()
        if kind == "debug":
            continue
        path = now_classes.get(name + ".class")
        expected = os.path.join(now_dir, PKG, name + ".class")
        if path is None or os.path.normpath(path) != os.path.normpath(expected):
            problems.append(f"frozen {kind} {name} is not in the root package")
    return problems


def main(argv):
    tests = "--tests" in argv
    argv = [a for a in argv if a != "--tests"]
    if len(argv) != 4:
        print(__doc__)
        return 2
    base_dir, now_dir, base_rev = argv[1:]
    try:
        problems = check_sources(base_rev)
        bytecode, now_classes = check_bytecode(base_dir, now_dir)
        problems += bytecode + ([] if tests else check_frozen(now_classes, now_dir))
    except (Setup, subprocess.CalledProcessError, OSError) as e:
        print(f"SETUP {e}")
        return 2
    for p in problems:
        print(f"FAIL {p}")
    print("PASS move-only" if not problems else f"{len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
