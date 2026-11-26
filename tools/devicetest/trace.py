#!/usr/bin/env python3
# SPDX-License-Identifier: 0BSD
"""Gesture trace builds: a debug APK of a pinned revision with DEBUG_GESTURES switched on, so the
service logs every Back decision. Built in a temporary worktree, never in this checkout, and cached
by source tree, trace patch and JDK, not by commit alone; the applied diff and the APK hash are kept
beside the APK. Accepted source keeps DEBUG_GESTURES off (contract A10).

  python3 tools/devicetest/trace.py build [--rev REV] [--probe PATCH]
                                     prints the cached or newly built APK path; --probe applies a
                                     sensitivity-probe diff inside the temporary worktree only
  python3 tools/devicetest/trace.py capture --run NAME [--rev REV] [--probe PATCH]
                                     installs the trace build inside the recovery transaction, records
                                     every scenario's normalized trace and observed effect, ends on the
                                     original build with the owner's settings restored
  python3 tools/devicetest/trace.py compare RUN_A RUN_B
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

CACHE = os.path.join(REPO, ".devicetest", "trace-cache")
RUNS = os.path.join(REPO, ".devicetest", "traces")
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


def cache_key(tree, jdk, probe=""):
    extra = f"\nprobe {hashlib.sha256(probe.encode()).hexdigest()}" if probe else ""
    return hashlib.sha256(f"{tree}\n{PATCH_ID}\n{jdk}{extra}".encode()).hexdigest()[:16]


def build(rev, probe_path=None):
    probe = open(probe_path).read() if probe_path else ""
    commit = git("rev-parse", "--verify", f"{rev}^{{commit}}")
    tree = git("rev-parse", f"{commit}^{{tree}}")
    jdk = toolchain()
    key = cache_key(tree, jdk, probe)
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
        if probe:
            # A probe is never committed: it exists only in this worktree and in the cache's diff.
            subprocess.run(["git", "apply", "-"], input=probe, text=True, cwd=work, check=True)
        diff = git("diff", cwd=work)
        env = dict(os.environ, JAVA_HOME=JAVA_HOME)
        subprocess.run(["./gradlew", "assembleDebug", "--console=plain", "-q"], cwd=work, env=env, check=True)
        os.makedirs(out_dir)
        shutil.copy(os.path.join(work, "app/build/outputs/apk/debug/app-debug.apk"), apk)
        with open(os.path.join(out_dir, "trace.diff"), "w") as f:
            f.write(diff + "\n")
        meta = {"commit": commit, "tree": tree, "patch": PATCH_ID, "jdk": jdk, "apk_sha256": sha256(apk),
                "probe_sha256": hashlib.sha256(probe.encode()).hexdigest() if probe else None}
        with open(meta_path, "w") as f:
            json.dump(meta, f, indent=2)
    finally:
        git("worktree", "remove", "--force", work)
    return apk


def scenarios():
    """Name -> (bindings, sequence) with the gesture suite's safe timing bands: double 300 ms,
    tap-hold 600 ms, hold 800 ms. Effects go to the test media session, so each is observable."""
    from dt.pad import BACK, L1, Seq
    gestures = {"back_single": "@media_play_pause", "back_double": "@media_next",
                "back_tap_hold": "@media_previous", "back_hold": "@status_overlay"}
    return [
        ("single", gestures, Seq().tap(BACK, after=600)),
        ("double", gestures, Seq().tap(BACK, after=80).tap(BACK, after=500)),
        ("tap_hold", gestures, Seq().tap(BACK, after=80).key(BACK, True).wait(900).key(BACK, False).wait(300)),
        ("hold", gestures, Seq().key(BACK, True).wait(1200).key(BACK, False).wait(500)),
        # Released between the thresholds (tap-hold 600, hold 800), 100 ms clear of each: the trace
        # prints configured values, so only these bands show a timer posted with the wrong delay.
        ("tap_hold_band", gestures, Seq().tap(BACK, after=80).key(BACK, True).wait(700).key(BACK, False).wait(500)),
        ("hold_band", gestures, Seq().key(BACK, True).wait(700).key(BACK, False).wait(600)),
        ("single_without_double", dict(gestures, back_double=""), Seq().tap(BACK, after=300)),
        ("combo_l1", dict(gestures, l1="@media_next"), Seq().combo(L1)),
    ]


def capture(run, rev, probe_path=None):
    import time
    from dt.device import Device
    from dt import trace
    from suites.common import ensure_helper, media_session
    out_dir = os.path.join(RUNS, run)
    if os.path.exists(out_dir):
        raise SystemExit(f"run {run} exists; captures are never overwritten")
    apk = build(rev, probe_path)
    d = Device()
    d.prepare()
    d.begin()
    results = {}
    try:
        d.install(apk)
        d.put("double_ms", 300)
        d.put("tap_hold_ms", 600)
        d.put("hold_ms", 800)
        ensure_helper(d)
        # Traces log which display holds the controller; pin it to the bottom screen, as in the
        # reference runs, so a capture does not depend on where the owner last left the focus.
        d.focus(d.bottom)
        media_session(d)
        # The install recreated the pad clone; its first press can get lost. Warm up until one arrives.
        from dt.pad import BACK, Seq
        d.bind("back_single", "@media_play_pause")
        d.unbind("back_double")
        for _ in range(5):
            d.press(Seq().tap(BACK, after=300))
            time.sleep(1.0)
            if d.media_keys():
                break
        else:
            raise SystemExit("the trace build never received a Back press")
        for name, bindings, seq in scenarios():
            for pref, action in bindings.items():
                if action:
                    d.bind(pref, action)
                else:
                    d.unbind(pref)
            ensure_helper(d)
            d.wait_toasts_gone()
            time.sleep(0.5)
            d.sh("logcat -c")
            d.press(seq)
            time.sleep(1.5)
            lines = trace.normalize(trace.messages(d.sh(f"logcat -d -v brief {trace.TAG}:D *:S")))
            results[name] = lines + [f"EFFECT media={d.media_keys()}"]
    finally:
        restored = d.end()
    os.makedirs(out_dir)
    for name, lines in results.items():
        with open(os.path.join(out_dir, f"{name}.txt"), "w") as f:
            f.write("\n".join(lines) + "\n")
    with open(os.path.join(out_dir, "meta.json"), "w") as f:
        json.dump({"apk_sha256": sha256(apk), "rev": git("rev-parse", rev), "probe": probe_path, "restored": restored}, f, indent=2)
    return out_dir


def compare_runs(a, b):
    from dt import trace
    names = sorted(n for n in os.listdir(os.path.join(RUNS, a)) if n.endswith(".txt"))
    other = sorted(n for n in os.listdir(os.path.join(RUNS, b)) if n.endswith(".txt"))
    failures = 0
    if names != other:
        print(f"scenario sets differ: {names} vs {other}")
        failures += 1
    for name in names:
        if name not in other:
            continue
        read = lambda run: open(os.path.join(RUNS, run, name)).read().splitlines()
        diffs = trace.compare(read(a), read(b))
        print(f"{'SAME' if not diffs else 'DIFF'} {name[:-4]}")
        for diff in diffs:
            print(f"  {diff}")
        failures += bool(diffs)
    return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    b = sub.add_parser("build")
    b.add_argument("--rev", default="HEAD")
    b.add_argument("--probe")
    c = sub.add_parser("capture")
    c.add_argument("--run", required=True)
    c.add_argument("--rev", default="HEAD")
    c.add_argument("--probe")
    p = sub.add_parser("compare")
    p.add_argument("a")
    p.add_argument("b")
    args = parser.parse_args()
    if args.command == "build":
        print(build(args.rev, args.probe))
    elif args.command == "capture":
        print(capture(args.run, args.rev, args.probe))
    else:
        sys.exit(1 if compare_runs(args.a, args.b) else 0)


if __name__ == "__main__":
    main()
