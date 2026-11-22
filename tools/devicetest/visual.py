#!/usr/bin/env python3
# SPDX-License-Identifier: 0BSD
"""Visual baseline of the settings screen on the top display, as raw frames.

  python3 tools/devicetest/visual.py capture --run NAME     capture every state into the private store
  python3 tools/devicetest/visual.py compare RUN_A RUN_B    exact comparison outside the status bar

Frames are screenshots: capture and inspection need the owner's permission (AGENTS.md). They are kept
privately under refactoring/baseline/S5/frames (git-excluded), never committed. Capturing runs inside
the recovery transaction, so the owner's settings and system values are restored and verified.
The status bar belongs to System UI (clock, signal, battery) and is the only masked area; it is read
from the device's status-bar inset, never assumed.
"""

import argparse
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dt.adb import REPO  # noqa: E402
from dt.device import Device, PKG  # noqa: E402
from dt.frames import FrameError, capture, differences, parse  # noqa: E402

STORE = os.path.join(REPO, "refactoring", "baseline", "S5", "frames")
ACTIVITY = f"{PKG}/.SettingsActivity"
NODE = re.compile(r"<node [^>]*>")
ASSIGNED = [
    ("back_double_package", "@recents"), ("l1_package", "@navigation_flick_left"),
    ("r1_package", "@navigation_flick_right"), ("l2_package", "@volume_down"), ("r2_package", "@volume_up"),
    ("dpad_up_package", "@brightness_up"), ("dpad_down_package", "@brightness_down"),
]


def attr(node, name):
    m = re.search(rf'{name}="([^"]*)"', node)
    return m.group(1) if m else ""


def physical_top(device):
    ids = re.findall(r'uniqueId="local:(\d+)"', device.sh("dumpsys display"))
    if not ids:
        raise FrameError("no physical display id found")
    return ids[0]  # the built-in top screen is listed first; verified against display 0 below


def status_bar_height(device):
    m = re.search(r"ITYPE_STATUS_BAR.*?mInsetsHint=Insets\{left=0, top=(\d+)", device.sh("dumpsys window"))
    if not m:
        raise FrameError("status-bar inset not found")
    return int(m.group(1))


def nodes(device):
    device.sh("uiautomator dump --display 0 /sdcard/bbh-ui.xml")
    xml = device.sh("cat /sdcard/bbh-ui.xml")
    device.sh("rm -f /sdcard/bbh-ui.xml")
    return [n for n in NODE.findall(xml) if attr(n, "package") == PKG]


def tap(device, node):
    l, t, r, b = map(int, re.findall(r"\d+", attr(node, "bounds")))
    device.sh(f"input -d 0 tap {(l + r) // 2} {(t + b) // 2}")


def no_toast(device, timeout=30.0):
    """Toasts are transient and never part of a captured state (in the debug build LeakCanary posts
    one while it dumps the heap), so a capture waits until no toast window is shown."""
    end = time.time() + timeout
    while "Toast" in device.sh("dumpsys window windows | grep -E 'Window\\{.*Toast'"):
        if time.time() > end:
            raise FrameError("a toast stayed visible")
        time.sleep(0.5)


def stable_frame(device, serial, physical, masks, tries=8, interval=0.5):
    """Two consecutive identical frames (outside the masks), or an error naming the instability."""
    previous = None
    no_toast(device)
    for _ in range(tries):
        data = capture(serial, physical)
        frame = parse(data)
        if previous is not None and differences(previous[1], frame, masks)[0] == 0:
            return data, frame
        previous = (data, frame)
        time.sleep(interval)
    raise FrameError("no two consecutive identical frames")


def launch(device):
    device.sh(f"am start -W --display 0 --activity-clear-task -n {ACTIVITY}")
    time.sleep(1.5)


def rail(device):
    """The navigation rail's entries, top to bottom: clickable described views at the left edge."""
    entries = [n for n in nodes(device) if attr(n, "clickable") == "true" and attr(n, "content-desc")]
    lefts = [int(re.findall(r"\d+", attr(n, "bounds"))[0]) for n in entries]
    edge = min(lefts) if lefts else 0
    column = [n for n, l in zip(entries, lefts) if l == edge]
    return sorted(column, key=lambda n: int(re.findall(r"\d+", attr(n, "bounds"))[1]))


# Variants change only the app's own language and two display settings; each is saved before the run
# and put back and verified afterwards (absent settings stay absent).
VARIANTS = [
    ("light", {"night": "no", "locales": "", "font_scale": None}),
    ("dark", {"night": "yes", "locales": "", "font_scale": None}),
    ("de", {"night": "no", "locales": "de-DE", "font_scale": None}),
    ("font130", {"night": "no", "locales": "", "font_scale": "1.3"}),
]


def system_view(device):
    night = device.sh("cmd uimode night").replace("Night mode:", "").strip()
    locales = re.search(r"are \[(.*)\]", device.sh(f"cmd locale get-app-locales {PKG}"))
    font = device.sh("settings get system font_scale")
    return {"night": night, "locales": locales.group(1) if locales else "",
            "font_scale": None if font in ("null", "") else font}


def apply_view(device, view):
    device.sh(f"cmd uimode night {view['night']}")
    device.sh(f"cmd locale set-app-locales {PKG} --locales {view['locales']}" if view["locales"]
              else f"cmd locale set-app-locales {PKG} --locales ''")
    if view["font_scale"] is None:
        device.sh("settings delete system font_scale")
    else:
        device.sh(f"settings put system font_scale {view['font_scale']}")
    time.sleep(2.0)
    if system_view(device) != view:
        raise FrameError(f"display settings not applied: {system_view(device)} != {view}")


def states(device, save, variant, manifest):
    p = f"{variant}_"
    launch(device)
    save(p + "welcome")
    device.app("put", key="welcome_v2_seen", type="bool", value="true")
    launch(device)
    save(p + "page_00_overview")
    labels = [attr(n, "content-desc") for n in rail(device)]
    manifest["rail"][variant] = labels
    for i, label in enumerate(labels):
        launch(device)
        target = next(n for n in rail(device) if attr(n, "content-desc") == label)
        tap(device, target)
        time.sleep(1.0)
        # The rail opens the full panel over the page; one tap on the page closes it.
        save(p + f"page_{i + 1:02d}_open")
        device.sh("input -d 0 tap 1700 1000")
        time.sleep(1.0)
        save(p + f"page_{i + 1:02d}")
    # Leader-line labels exist only for assigned bindings, so the overview needs a populated fixture.
    for key, action in ASSIGNED:
        device.app("put", key=key, type="string", value=action)
    launch(device)
    save(p + "page_00_overview_assigned")
    # The indicator dialog opens straight from its intent extra. Its preview pictogram lasts at most
    # 1 s, too short for two identical frames, so each selection is captured after the preview ended;
    # the pictogram itself is covered by the JVM golden overlay_focus and manual checks.
    device.app("put", key="r2_package", type="string", value="@controller_focus")
    device.sh(f"am start -W --display 0 --activity-clear-task -n {ACTIVITY} --es indicator_button r2")
    time.sleep(1.5)
    save(p + "focus_dialog")
    cells = [n for n in nodes(device) if attr(n, "clickable") == "true" and not attr(n, "text") and attr(n, "content-desc")
             and attr(n, "content-desc") not in labels]
    if len(cells) != 9:
        raise FrameError(f"expected 9 position cells, found {[attr(n, 'content-desc') for n in cells]}")
    for i, cell in enumerate(sorted(cells, key=lambda n: [int(v) for v in re.findall(r"\d+", attr(n, "bounds"))][1::-1])):
        tap(device, cell)
        time.sleep(1.6)
        save(p + f"focus_select_{i}")


def do_capture(run):
    device = Device()
    device.prepare()
    serial = device.adb.serial
    out = os.path.join(STORE, run)
    if os.path.exists(out):
        raise SystemExit(f"{out} exists; frames are never overwritten")
    os.makedirs(out)
    physical = physical_top(device)
    manifest = {"run": run, "physical_top": physical, "states": []}
    saved = system_view(device)
    manifest["system_before"] = saved
    manifest["rail"] = {}
    device.begin()
    try:
        width = parse(capture(serial, physical)).width
        for variant, settings in VARIANTS:
            apply_view(device, settings)
            bar = status_bar_height(device)
            manifest.setdefault("mask_status_bar_rows", {})[variant] = bar

            def save(name):
                data, frame = stable_frame(device, serial, physical, [(0, 0, width, bar)])
                with open(os.path.join(out, f"{name}.raw"), "wb") as f:
                    f.write(data)
                manifest["states"].append({"name": name, "variant": variant, **frame.meta()})
                device.log(f"captured {name}")

            device.app("reset")
            states(device, save, variant, manifest)
    finally:
        device.sh("am start -W --display 0 -a android.intent.action.MAIN -c android.intent.category.HOME")
        apply_view(device, saved)
        after = system_view(device)
        manifest["system_after"] = after
        restored = device.end()
        manifest["restored"] = restored and after == saved
        with open(os.path.join(out, "manifest.json"), "w") as f:
            json.dump(manifest, f, indent=1)
    print(f"{len(manifest['states'])} states in {out}; restored={manifest['restored']}")


def do_compare(a, b):
    ma = json.load(open(os.path.join(STORE, a, "manifest.json")))
    mb = json.load(open(os.path.join(STORE, b, "manifest.json")))
    bars = {**ma["mask_status_bar_rows"]}
    for k, v in mb["mask_status_bar_rows"].items():
        bars[k] = max(v, bars.get(k, 0))
    variant_of = {st["name"]: st["variant"] for st in ma["states"]}
    names_a = [s["name"] for s in ma["states"]]
    names_b = [s["name"] for s in mb["states"]]
    failed = names_a != names_b or ma.get("rail") != mb.get("rail")
    if failed:
        print(f"STATE LISTS DIFFER: {names_a} vs {names_b}")
    for name in sorted(set(names_a) & set(names_b)):
        fa = parse(open(os.path.join(STORE, a, f"{name}.raw"), "rb").read())
        fb = parse(open(os.path.join(STORE, b, f"{name}.raw"), "rb").read())
        count, box = differences(fa, fb, [(0, 0, fa.width, bars[variant_of[name]])])
        print(f"{'SAME' if count == 0 else 'DIFF'} {name} {count} {box or ''}")
        failed |= count != 0
    return 1 if failed else 0


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("capture")
    c.add_argument("--run", required=True)
    p = sub.add_parser("compare")
    p.add_argument("a")
    p.add_argument("b")
    args = parser.parse_args()
    if args.cmd == "capture":
        do_capture(args.run)
        return 0
    return do_compare(args.a, args.b)


if __name__ == "__main__":
    sys.exit(main())
