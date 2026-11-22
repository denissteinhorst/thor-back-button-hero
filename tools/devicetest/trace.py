#!/usr/bin/env python3
# SPDX-License-Identifier: 0BSD
"""Gesture trace builds: a debug APK of a pinned revision with DEBUG_GESTURES switched on, so the
service logs every Back decision. Built in a temporary worktree, never in this checkout, and cached
by source tree, trace patch and JDK, not by commit alone; the applied diff and the APK hash are kept
beside the APK. Accepted source keeps DEBUG_GESTURES off (contract A10).

  python3 tools/devicetest/trace.py build [--rev REV]     prints the cached or newly built APK path
"""

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys

sys.dont_write_bytecode = True

from dt.adb import JAVA_HOME, REPO  # noqa: E402

CACHE = os.path.join(REPO, "refactoring", "baseline", "S6", "trace-cache")
SOURCE = "app/src/main/java/dev/steinhorst/backbuttonhero/SwitcherService.kt"
OFF = "const val DEBUG_GESTURES = false"
ON = "const val DEBUG_GESTURES = true"
PATCH_ID = f"{SOURCE}: {OFF} -> {ON}"


def git(*args, cwd=REPO):
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()


def sha256(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def toolchain():
    out = subprocess.run([os.path.join(JAVA_HOME, "bin", "java"), "-version"], capture_output=True, text=True)
    return out.stderr.strip().splitlines()[0]


def apply_patch(text):
    """Exactly one switch, or the patch no longer fits this source and the build must not happen."""
    if text.count(OFF) != 1:
        raise SystemExit(f"trace patch does not apply: expected one '{OFF}' in {SOURCE}")
    return text.replace(OFF, ON)


def cache_key(tree, jdk):
    return hashlib.sha256(f"{tree}\n{PATCH_ID}\n{jdk}".encode()).hexdigest()[:16]


def build(rev):
    commit = git("rev-parse", "--verify", f"{rev}^{{commit}}")
    tree = git("rev-parse", f"{commit}^{{tree}}")
    jdk = toolchain()
    key = cache_key(tree, jdk)
    out_dir = os.path.join(CACHE, key)
    apk = os.path.join(out_dir, "app-debug-trace.apk")
    meta_path = os.path.join(out_dir, "meta.json")
    if os.path.exists(meta_path):
        with open(meta_path) as f:
            meta = json.load(f)
        if os.path.exists(apk) and sha256(apk) == meta["apk_sha256"]:
            return apk
        raise SystemExit(f"cached trace build {out_dir} does not match its recorded hash; inspect it before rebuilding")
    work = os.path.join(CACHE, f"src-{key}")
    if os.path.exists(work):
        git("worktree", "remove", "--force", work)
    os.makedirs(CACHE, exist_ok=True)
    git("worktree", "add", "--detach", work, commit)
    try:
        shutil.copy(os.path.join(REPO, "local.properties"), work)
        path = os.path.join(work, SOURCE)
        with open(path) as f:
            patched = apply_patch(f.read())
        with open(path, "w") as f:
            f.write(patched)
        diff = git("diff", cwd=work)
        env = dict(os.environ, JAVA_HOME=JAVA_HOME)
        subprocess.run(["./gradlew", "assembleDebug", "--console=plain", "-q"], cwd=work, env=env, check=True)
        os.makedirs(out_dir)
        shutil.copy(os.path.join(work, "app/build/outputs/apk/debug/app-debug.apk"), apk)
        with open(os.path.join(out_dir, "trace.diff"), "w") as f:
            f.write(diff + "\n")
        meta = {"commit": commit, "tree": tree, "patch": PATCH_ID, "jdk": jdk, "apk_sha256": sha256(apk)}
        with open(meta_path, "w") as f:
            json.dump(meta, f, indent=2)
    finally:
        git("worktree", "remove", "--force", work)
    return apk


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    b = sub.add_parser("build")
    b.add_argument("--rev", default="HEAD")
    args = parser.parse_args()
    if args.command == "build":
        print(build(args.rev))


if __name__ == "__main__":
    main()
