#!/usr/bin/env python3
# SPDX-License-Identifier: 0BSD
"""Resources, lifecycle and leaks on the attached Thor, inside the recovery transaction (S7, F1/F3/F4).

  python3 tools/devicetest/resources.py [--serial SERIAL] [--apk APK] [--runs N] [--only measure|latency|lifecycle]
  python3 tools/devicetest/resources.py --compare BASE.json CANDIDATE.json

measure (F1): N runs of one fixed workload, each from a freshly restarted service in a warm process:
three apps (Settings and Clock on top, Gallery on the bottom), then ten openings and closings of the
bottom switcher with a real Back combo on the pad clone. Every opening must complete, so fewer
completed cycles can never pass for lower cost. After each run: the app's PSS split (debug build,
LeakCanary included), its threads grouped by role, its descriptors by kind, and the pad helper.

latency (F2): from the trigger to the first frame. The press script reads the kernel's monotonic clock
right before and after it writes the switcher button's DOWN (the action runs on DOWN); the end is the
FrameCompleted of the first frame the app's process began after that stamp, from gfxinfo framestats on
the same clock. Five cold openings (first after a service start) and fifteen warm ones; median, MAD,
p90 and openings without any frame (counted as missing, never as fast). The first frame is the app's
first finished frame, not a measured photon: display composition adds up to one more vsync.

lifecycle (F4 + F3): the service is really disabled and enabled through Android, five times, then the
app process is killed. Each state is observed: the helper gone while disabled and exactly one back
afterwards, no thread role or descriptor kind left over or growing across cycles. LeakCanary must be
seen watching every destroyed service, and its heap analysis must complete; its retained paths are
recorded, so a later build can be compared against them.

Without --apk a fresh debug build of the working tree is measured. Raw results go to
app/build/devicetest/resources-<run>.json. --compare judges a candidate's memory, own threads and
descriptors against a baseline file with the budgets fixed in BUDGET_FLOORS.
"""

import argparse
import json
import os
import re
import statistics
import subprocess
import sys
import time

sys.dont_write_bytecode = True

from dt import pad  # noqa: E402
from dt.adb import JAVA_HOME, REPO  # noqa: E402
from dt.device import PKG, SERVICE, Device, Fail  # noqa: E402
from dt.pad import Seq  # noqa: E402

APP_APK = os.path.join(REPO, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
OUT_DIR = os.path.join(REPO, "app", "build", "devicetest")
WORKLOAD = [("com.android.settings/.Settings", "top"), ("com.android.deskclock/.DeskClock", "top"),
            ("com.android.gallery3d/.app.GalleryActivity", "bottom")]
CYCLES = 10
KEY = "face_y"


# --- parsing (host-tested) ---------------------------------------------------------------------

def thread_role(name):
    """A thread's role without the numbers Android and pools put in it: binder:123_4 -> binder,
    pool-2-thread-1 -> pool-thread, RenderThread stays RenderThread."""
    name = name.strip()
    if name.startswith("binder:") or name.startswith("HwBinder:"):
        return name.split(":")[0]
    # The kernel keeps 15 characters of a name, which cuts pool-32-thread-1 to pool-32-thread-.
    if re.fullmatch(r"pool-\d+-thread-?\d*", name):
        return "pool-thread"
    return re.sub(r"[-_ #:]*\d+$", "", name) or name


def roles(comm_lines):
    counts = {}
    for line in comm_lines:
        if line.strip():
            role = thread_role(line)
            counts[role] = counts.get(role, 0) + 1
    return dict(sorted(counts.items()))


def fd_kind(target):
    for prefix, kind in (("socket:", "socket"), ("pipe:", "pipe"), ("anon_inode:", "anon_inode"),
                         ("/dev/input/", "input"), ("/dev/uinput", "uinput"), ("/dev/", "device")):
        if target.startswith(prefix):
            return kind + (":" + target.split(":", 1)[1].strip("[]") if kind == "anon_inode" else "")
    return "file"


def fds(ls_lines):
    """Descriptor counts by kind from `ls -l /proc/<pid>/fd` output."""
    counts = {}
    for line in ls_lines:
        if " -> " not in line:
            continue
        kind = fd_kind(line.split(" -> ", 1)[1].strip())
        counts[kind] = counts.get(kind, 0) + 1
    return dict(sorted(counts.items()))


def meminfo(text):
    """KB values of the app summary in `dumpsys meminfo <pkg>`."""
    out = {}
    for label, key in (("Java Heap:", "java_kb"), ("Native Heap:", "native_kb"), ("Graphics:", "graphics_kb"),
                       ("TOTAL PSS:", "pss_kb")):
        m = re.search(re.escape(label) + r"\s+(\d+)", text)
        if not m:
            raise ValueError(f"meminfo has no {label!r}")
        out[key] = int(m.group(1))
    return out


def leak_analysis(log):
    """LeakCanary's verdict from logcat: watched services, whether an analysis completed, its leak counts
    and the retained classes of each leak trace (the path, not instance addresses)."""
    watched = len(re.findall(r"Watching instance of dev\.steinhorst\.backbuttonhero\.SwitcherService", log))
    result = {"watched_services": watched, "analysis_complete": "HEAP ANALYSIS RESULT" in log}
    for label, key in (("APPLICATION LEAKS", "application_leaks"), ("LIBRARY LEAKS", "library_leaks")):
        m = re.search(r"(\d+) " + label, log)
        result[key] = int(m.group(1)) if m else None
    paths = []
    for block in re.split(r"\n(?=\S*\s*LeakCanary: ┬───)|(?<=\n)┬───", log):
        names = [m.group(1) or m.group(2) for m in
                 re.finditer(r"[├╰]─ ([\w.$]+) (?:instance|class)|╰→ ([\w.$]+)", block)]
        if names:
            paths.append(names)
    result["paths"] = paths
    result["signatures"] = sorted(set(re.findall(r"Signature: ([0-9a-f]{40})", log)))
    return result


def summarize(values):
    """Median, median absolute deviation and range of one metric across runs."""
    med = statistics.median(values)
    return {"median": med, "mad": statistics.median(abs(v - med) for v in values), "min": min(values),
            "max": max(values), "n": len(values)}


# Fixed before any candidate was measured (S7 §1): a candidate's median may differ from the baseline's
# by at most max(3 x baseline MAD, floor). Floors in the metric's own unit.
BUDGET_FLOORS = {"pss_kb": 3072, "java_kb": 1024, "native_kb": 3072, "graphics_kb": 1024, "threads": 2, "fds": 6}
# Framework-owned pools that grow on demand; their size says nothing about the app's own workers.
FRAMEWORK_ROLES = {"binder", "HwBinder", "hwuiTask", "Runtime worker"}


# Latency budgets in ms, fixed likewise: median within max(3 x MAD, 20), p90 within max(3 x MAD, 30).
LATENCY_FLOORS = {"median": 20, "p90": 30}


def latency_compare(base, cand):
    failures = []
    for kind in ("cold", "warm"):
        b, c = base["summary"][kind], cand["summary"][kind]
        if c.get("missing"):
            failures.append(f"latency {kind}: {c['missing']} openings without a frame")
        for key, floor in LATENCY_FLOORS.items():
            budget = max(3 * b["mad"], floor)
            if c[key] - b[key] > budget:
                failures.append(f"latency {kind} {key}: {c[key]} ms exceeds {b[key]} ms by more than {budget} ms")
    return failures


def own_threads(sample):
    return sum(n for role, n in sample["threads"].items() if role not in FRAMEWORK_ROLES)


def renormalized(sample):
    threads = {}
    for role, n in sample["threads"].items():
        threads[thread_role(role)] = threads.get(thread_role(role), 0) + n
    return dict(sample, threads=dict(sorted(threads.items())))


def with_metrics(samples):
    samples = [renormalized(s) for s in samples]
    metrics = {key: summarize([s["memory"][key] for s in samples]) for key in ("pss_kb", "java_kb", "native_kb", "graphics_kb")}
    metrics["threads"] = summarize([own_threads(s) for s in samples])
    metrics["fds"] = summarize([sum(s["fds"].values()) for s in samples])
    return {"samples": samples, "metrics": metrics}


def compare(base, cand):
    """Budget verdicts of candidate metrics against baseline metrics, plus thread roles new in the
    candidate; an empty list of failures means it stayed within budget."""
    # From the raw samples, so files written under an older metric definition compare alike.
    base, cand = with_metrics(base["samples"]), with_metrics(cand["samples"])
    failures, rows = [], []
    for key, floor in BUDGET_FLOORS.items():
        b, c = base["metrics"][key], cand["metrics"][key]
        budget = max(3 * b["mad"], floor)
        delta = c["median"] - b["median"]
        rows.append({"metric": key, "base": b["median"], "candidate": c["median"], "delta": delta, "budget": budget})
        if delta > budget:
            failures.append(f"{key}: median {c['median']} exceeds {b['median']} by {delta} (budget {budget})")
    if not all(s["cycles"] == CYCLES for s in cand["samples"]):
        failures.append("the candidate did not complete every workload cycle")
    base_roles = set().union(*(set(s["threads"]) for s in base["samples"]))
    new = sorted(set().union(*(set(s["threads"]) for s in cand["samples"])) - base_roles)
    if new:
        failures.append(f"thread roles new in the candidate: {new}")
    return failures, rows


def timer_stamps(out):
    """The two kernel monotonic stamps (ns) the press script reads around the trigger's DOWN."""
    t0 = re.search(r"T0 now at (\d+) nsecs", out)
    t1 = re.search(r"T1 now at (\d+) nsecs", out)
    if not (t0 and t1):
        raise ValueError("the press script did not report its stamps")
    return int(t0.group(1)), int(t1.group(1))


def frames(framestats):
    """(IntendedVsync, FrameCompleted) of every frame in `dumpsys gfxinfo <pkg> framestats`, all windows."""
    out, columns = [], None
    for line in framestats.splitlines():
        cells = [c.strip() for c in line.strip().rstrip(",").split(",")]
        if "IntendedVsync" in cells and "FrameCompleted" in cells:
            columns = (cells.index("IntendedVsync"), cells.index("FrameCompleted"))
            continue
        if columns and len(cells) > max(columns) and cells[columns[0]].isdigit() and cells[columns[1]].isdigit():
            out.append((int(cells[columns[0]]), int(cells[columns[1]])))
    return out


def first_frame_latency(stamps, frame_rows):
    """Milliseconds from the trigger (the stamp before its DOWN) to the completion of the first frame
    begun after it, or None when no frame followed."""
    t0, _ = stamps
    after = [done for vsync, done in frame_rows if vsync >= t0 and done > t0]
    return round((min(after) - t0) / 1e6, 2) if after else None


# --- device ------------------------------------------------------------------------------------

def snapshot(d):
    pid = d.sh(f"pidof {PKG}")
    if not pid:
        raise Fail("the app is not running")
    helper = d.helper_pid()
    body = f"for t in /proc/{pid}/task/*; do cat $t/comm; done\necho ===\nls -l /proc/{pid}/fd\necho ===\n"
    if helper:
        body += f"ls -l /proc/{helper}/fd\n"
    body += "echo ===\nps -A -o NAME | grep -c libpadhelper\necho END\n"
    out = d.adb.root_script(body)
    if not out.rstrip().endswith("END"):
        raise Fail("the root read did not finish")
    parts = out.split("===")
    return {"pid": pid, "memory": meminfo(d.sh(f"dumpsys meminfo {PKG}")),
            "threads": roles(parts[0].splitlines()), "fds": fds(parts[1].splitlines()),
            "helper": {"pid": helper, "fds": fds(parts[2].splitlines()) if helper else {}},
            "helpers_running": int(parts[3].split()[0])}


def wake(d):
    """The device sleeps after its own timeout between runs; waking it changes no setting."""
    if not d.awake():
        d.sh("input keyevent KEYCODE_WAKEUP")
        d.eventually(d.awake, 5, "the device did not wake up")
        time.sleep(1)


def workload(d):
    wake(d)
    for component, _ in WORKLOAD:
        d.stop_app(component.split("/")[0])
    for component, screen in WORKLOAD:
        display = d.top if screen == "top" else d.bottom
        # A cold start right after an install can take longer than start_app's own wait.
        d.sh(f"am start -W --display {display} -n {component}", timeout=30)
        pkg = component.split("/")[0]
        d.eventually(lambda: d.resumed().get(display, "").startswith(pkg + "/"), 15,
                     f"{component} did not come to the front on display {display}")
    done = 0
    for _ in range(CYCLES):
        d.press(Seq().combo(d.face("y")))
        d.eventually(lambda: d.own_windows(d.bottom), 4, "the switcher did not open on the bottom screen")
        d.press(Seq().tap(pad.BACK))
        d.eventually(lambda: not d.own_windows(d.bottom), 4, "Back did not close the switcher")
        done += 1
    return done


def measure(d, runs):
    d.bind(KEY, "@switcher")
    samples = []
    for i in range(runs):
        d.restart_service()
        d.bind(KEY, "@switcher")
        start = time.monotonic()
        cycles = workload(d)
        time.sleep(3)
        s = snapshot(d)
        s.update(run=i, cycles=cycles, seconds=round(time.monotonic() - start, 1))
        print(f"  run {i}: {s['memory']['pss_kb']} KB PSS, {sum(s['threads'].values())} threads, "
              f"{sum(s['fds'].values())} fds, {cycles}/{CYCLES} cycles")
        samples.append(s)
    for component, _ in WORKLOAD:
        d.stop_app(component.split("/")[0])
    return with_metrics(samples)


def timed_press(d):
    """Back held, the switcher's face button tapped; the kernel's monotonic clock is read right before
    and after the button's DOWN is written, the clock gfxinfo's frame timestamps use."""
    y = d.face("y")
    stamp = "head -3 /proc/timer_list | grep -m1 'now at'"
    seq = (Seq().key(pad.BACK, True).wait(250).raw(f"t0=$({stamp})").key(y, True).raw(f"t1=$({stamp})")
           .wait(90).key(y, False).wait(200).key(pad.BACK, False).raw('echo "T0 $t0"; echo "T1 $t1"'))
    return timer_stamps(d.press(seq))


def latency(d, warm=15, cold=5):
    """F2: cold (first opening after a service start) and warm openings of the bottom switcher."""
    d.bind(KEY, "@switcher")
    samples = {"cold": [], "warm": []}

    def one(kind):
        wake(d)
        d.sh(f"dumpsys gfxinfo {PKG} reset")
        stamps = timed_press(d)
        d.eventually(lambda: d.own_windows(d.bottom), 4, "the switcher did not open on the bottom screen")
        time.sleep(0.6)
        rows = frames(d.sh(f"dumpsys gfxinfo {PKG} framestats"))
        d.press(Seq().tap(pad.BACK))
        d.eventually(lambda: not d.own_windows(d.bottom), 4, "Back did not close the switcher")
        ms = first_frame_latency(stamps, rows)
        samples[kind].append({"ms": ms, "stamp_spread_ms": round((stamps[1] - stamps[0]) / 1e6, 2),
                              "frames": len(rows)})
        time.sleep(0.5)

    for _ in range(cold):
        d.restart_service()
        d.bind(KEY, "@switcher")
        time.sleep(2)
        one("cold")
    for _ in range(warm):
        one("warm")
    summary = {}
    for kind, rows in samples.items():
        values = [r["ms"] for r in rows if r["ms"] is not None]
        summary[kind] = dict(summarize(values) if values else {}, missing=len(rows) - len(values),
                             p90=sorted(values)[int(0.9 * (len(values) - 1))] if values else None)
    return {"samples": samples, "summary": summary}


def disable(d):
    enabled = [s for s in d.sh("settings get secure enabled_accessibility_services").split(":") if s not in ("", "null")]
    d.sh("settings put secure enabled_accessibility_services " + (":".join(s for s in enabled if s != SERVICE) or "null"))
    d.eventually(lambda: not d.service_bound(), 15, "the service did not unbind")


def lifecycle(d, cycles=5):
    wake(d)
    d.sh("logcat -c")
    states = {"bound": snapshot(d), "disabled": [], "enabled": []}
    for _ in range(cycles):
        disable(d)
        d.eventually(lambda: not d.helper_pid(), 10, "the pad helper outlived the disabled service")
        time.sleep(1)
        states["disabled"].append(snapshot(d))
        d.reconnect()
        d.eventually(lambda: d.helper_pid(), 10, "the pad helper did not come back with the service")
        time.sleep(2)
        states["enabled"].append(snapshot(d))
    deadline = time.monotonic() + 180
    log = ""
    while time.monotonic() < deadline:
        log = d.sh("logcat -d -s LeakCanary:V", timeout=60)
        if "HEAP ANALYSIS RESULT" in log and re.search(r"\d+ LIBRARY LEAKS", log):
            break
        time.sleep(3)
    states["leaks"] = leak_analysis(log)
    with open(os.path.join(OUT_DIR, "leakcanary-log.txt"), "w") as f:
        f.write(log)
    pid = d.sh(f"pidof {PKG}")
    d.root(f"kill -9 {pid}")
    d.eventually(lambda: d.sh(f"pidof {PKG}") not in ("", pid), 20, "the app did not come back after its death")
    d.reconnect()
    d.eventually(lambda: d.helper_pid(), 10, "the pad helper did not come back after the app's death")
    time.sleep(2)
    states["after_death"] = snapshot(d)
    return states


def lifecycle_findings(states, cycles=5):
    """What F4/F3 require of the observed states; an empty list means they held."""
    problems = []
    for s in states["disabled"]:
        if s["helper"]["pid"] or s["helpers_running"]:
            problems.append("a pad helper ran while the service was disabled")
        if "PadHelper" in s["threads"]:
            problems.append("the PadHelper thread survived the disabled service")
    for s in states["enabled"] + [states["after_death"]]:
        if s["helpers_running"] != 1:
            problems.append(f"{s['helpers_running']} pad helpers ran with the service bound")
    base = states["bound"]
    for s in states["enabled"]:
        # Android's process-wide QueuedWork looper starts on the first SharedPreferences.apply()
        # and then lives as one thread; whether that apply precedes the bound snapshot depends on
        # stored state (a pending migration), not on the lifecycle. More than one is still new.
        lazy = {"queued-work-loo"} if s["threads"].get("queued-work-loo", 0) == 1 else set()
        extra = sorted(set(s["threads"]) - set(base["threads"]) - {"LeakCanary-Heap", "HeapAnalyzer"} - lazy)
        if extra:
            problems.append(f"new thread roles after re-enabling: {extra}")
    owned = [own_threads(s) for s in states["enabled"]]
    if len(owned) >= 3 and all(b > a for a, b in zip(owned, owned[1:])):
        problems.append(f"the app's own threads grew on every re-enable: {owned}")
    totals = [sum(s["fds"].values()) for s in states["enabled"]]
    if len(totals) >= 3 and all(b > a for a, b in zip(totals, totals[1:])):
        problems.append(f"descriptors grew on every re-enable: {totals}")
    leaks = states["leaks"]
    if leaks["watched_services"] < cycles:
        problems.append(f"LeakCanary watched {leaks['watched_services']} of {cycles} destroyed services")
    if not leaks["analysis_complete"]:
        problems.append("no LeakCanary heap analysis completed")
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial")
    parser.add_argument("--apk")
    parser.add_argument("--runs", type=int, default=5)
    parser.add_argument("--only", choices=["measure", "lifecycle", "latency"])
    parser.add_argument("--compare", nargs=2, metavar=("BASE.json", "CANDIDATE.json"),
                        help="judge two saved measurements against the budgets; touches no device")
    args = parser.parse_args()
    if args.compare:
        files = [json.load(open(p)) for p in args.compare]
        failures, rows = compare(files[0]["measure"], files[1]["measure"]) if all("measure" in f for f in files) else ([], [])
        if all("latency" in f for f in files):
            failures += latency_compare(files[0]["latency"], files[1]["latency"])
        for r in rows:
            print(f"  {r['metric']}: {r['base']} -> {r['candidate']} (delta {r['delta']}, budget {r['budget']})")
        for f in failures:
            print("  ✗ " + f)
        sys.exit(1 if failures else 0)
    apk = args.apk or APP_APK
    if not args.apk:
        subprocess.run(["./gradlew", "assembleDebug", "--console=plain", "-q"], cwd=REPO,
                       env=dict(os.environ, JAVA_HOME=JAVA_HOME), check=True)
    d = Device(args.serial)
    d.prepare()
    d.begin()
    result = {"apk": apk, "started": time.strftime("%Y-%m-%d %H:%M:%S"),
              "conditions": {"battery": d.sh("dumpsys battery | grep -E 'level|temperature|AC powered'"),
                             "thermal": d.sh("dumpsys thermalservice | grep -m1 'Thermal Status'")}}
    try:
        d.install(apk)
        if args.only in (None, "measure"):
            print("-- measure")
            result["measure"] = measure(d, args.runs)
        if args.only in (None, "latency"):
            print("-- latency")
            result["latency"] = latency(d)
        if args.only in (None, "lifecycle"):
            print("-- lifecycle")
            states = lifecycle(d)
            result["lifecycle"] = states
            result["lifecycle_findings"] = lifecycle_findings(states)
    finally:
        restored = d.end()
    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, f"resources-{time.strftime('%Y%m%d-%H%M%S')}.json")
    with open(path, "w") as f:
        json.dump(result, f, indent=2, sort_keys=True)
    for key, m in result.get("measure", {}).get("metrics", {}).items():
        print(f"  {key}: median {m['median']} MAD {m['mad']} range {m['min']}–{m['max']} (n={m['n']})")
    for kind, m in result.get("latency", {}).get("summary", {}).items():
        print(f"  latency {kind}: {m}")
    for line in result.get("lifecycle_findings", []):
        print("  ✗ " + line)
    if "lifecycle" in result:
        lk = result["lifecycle"]["leaks"]
        print(f"  leaks: watched {lk['watched_services']}, analysis {lk['analysis_complete']}, "
              f"application {lk['application_leaks']}, library {lk['library_leaks']}")
    print(f"raw: {path}; restored: {restored}")
    sys.exit(0 if restored and not result.get("lifecycle_findings") else 1)


if __name__ == "__main__":
    main()
