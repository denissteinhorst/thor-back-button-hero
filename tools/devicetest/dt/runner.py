"""Test registry, area selection and the report."""

import json
import os
import time
import traceback

from .adb import WORK
from .device import Fail

TESTS = []
# Dual mode's two binding layers; a layered test runs once per selected layer (docs/HOME-DUAL-MODE.md).
LAYERS = ("back", "home")


class Skip(Exception):
    pass


def test(area, title, layered=False):
    """Registers a test; tests run in the order their modules import them. A [layered] test presses
    the layer's modifier and binds through Device.bind, so it also proves dual mode's Home layer."""
    def register(fn):
        fn.layered = layered
        TESTS.append((area, title, fn))
        return fn
    return register


def _in_layer(fn, layer):
    """Runs [fn] in [layer]: the Home layer switches dual mode on and presses Home instead of Back."""
    from . import pad

    def run(device):
        if layer == "back":
            return fn(device)
        device.layer = "home"
        pad.modifier = pad.HOME
        device.put("dual_mode", True)
        try:
            return fn(device)
        finally:
            device.layer = "back"
            pad.modifier = pad.BACK
            device.put("dual_mode", False)
    return run


# Which areas a changed source file can affect: used by --changed.
FILE_AREAS = {
    "AynControls.kt": ["ayn", "popups"],
    "LevelControl.kt": ["levels"], "LevelRepeater.kt": ["levels"], "LevelSteps.kt": ["levels"], "BrightnessControl.kt": ["levels"],
    "StatusSummary.kt": ["media"],
    "BackGestureMachine.kt": ["gestures", "toggle", "dual"], "DoubleTapDetector.kt": ["gestures"],
    "StickClickMachine.kt": ["sources"], "StickButton.kt": ["sources"], "ShoulderButton.kt": ["sources", "gestures", "dual", "confirm"],
    "PadHelper.kt": ["sources", "toggle", "macro", "press", "gyro", "dual"],
    "padhelper.c": ["sources", "toggle", "macro", "press", "gyro", "dual"], "PadPress.kt": ["press"],
    "DpadOverlay.kt": ["sources"], "PhysicalKeyTracker.kt": ["sources", "gestures"],
    "ServiceToggleGesture.kt": ["toggle"], "KeyInterceptionGate.kt": ["toggle"],
    "HomeAsBack.kt": ["gestures", "sources", "dual"], "AppExceptions.kt": ["exceptions"], "ExceptionGroups.kt": ["exceptions"],
    "Macro.kt": ["macro", "press"], "Gyro.kt": ["gyro"], "GyroControl.kt": ["gyro"], "GyroFusion.kt": ["gyro"],
    "ControllerFocus.kt": ["windows"], "AutoControllerFocus.kt": ["windows"], "FocusIndicatorOverlay.kt": ["windows"],
    "FocusedScreen.kt": ["windows", "appcontrol", "capture", "levels"],
    "AppControl.kt": ["appcontrol", "confirm"], "PromptKeys.kt": ["appcontrol", "windows", "confirm"],
    "ButtonHints.kt": ["appcontrol", "windows", "confirm"], "SwitcherOverlay.kt": ["confirm"], "CloseApps.kt": ["appcontrol"], "TaskRemover.kt": ["appcontrol"],
    "ScreenRecorder.kt": ["capture"], "ScreenRecording.kt": ["capture"], "SystemScreenshot.kt": ["capture"],
    "ScreenshotSaver.kt": ["capture"], "PushToTalk.kt": ["capture"],
    "PowerMenu.kt": ["panels"], "LastApp.kt": ["windows"], "NoticeOverlay.kt": ["ayn", "toggle", "media", "popups"], "CardTouch.kt": ["windows"], "ControllerFocusTouch.kt": ["windows"],
    "AccessibilityAppSource.kt": ["windows", "appcontrol", "exceptions"], "RunningApp.kt": ["windows", "appcontrol"],
    "RecordingIndicatorOverlay.kt": ["capture"], "EchoTrim.kt": ["capture"], "VoiceBand.kt": ["capture"],
    "VoiceLeveler.kt": ["capture"], "VoicePolish.kt": ["capture"],
    "SettingsActivity.kt": ["settings"], "SettingsNavigation.kt": ["settings"], "BindingCard.kt": ["settings"],
    "UpdateFlow.kt": ["settings"], "UpdateChecker.kt": ["settings"],
    "HidePopups.kt": ["popups"], "PopupGuardLink.kt": ["popups"], "PopupGuard.kt": ["popups"],
}
# Changes here can break anything the service does.
EVERYTHING = {"SwitcherService.kt", "Prefs.kt", "PService.kt", "AndroidManifest.xml", "accessibility_config.xml", "Shell.kt"}


WINDOW_PREFIXES = ("Recents", "TopRecents", "Switcher", "Panel", "BottomPanels", "TaskMove", "Frontend", "Parking", "TaskParker")
# Third-party bottom panels and parked frontend activities, exercised with real frontends.
FRONTEND_PREFIXES = ("Panel", "BottomPanels", "Frontend", "Parking", "TaskParker")


def unmapped(files):
    """Production files no mapping above reaches. An unknown file could be on any area's path
    (a newly extracted class, say), so [areas_for] selects everything for it instead of nothing."""
    return [path for path in files
            if os.path.basename(path) not in FILE_AREAS and os.path.basename(path) not in EVERYTHING
            and not os.path.basename(path).startswith(WINDOW_PREFIXES)]


def areas_for(files):
    """The areas [files] can affect, or None for all of them."""
    if unmapped(files):
        return None
    areas = set()
    for path in files:
        name = os.path.basename(path)
        if name in EVERYTHING:
            return None
        areas.update(FILE_AREAS.get(name, []))
        if name.startswith(WINDOW_PREFIXES):
            areas.add("windows")
        if name.startswith(FRONTEND_PREFIXES):
            areas.add("frontends")
    return areas


def registered_areas():
    return {area for area, _, _ in TESTS}


def parse_only(text):
    """The areas an --only list names. Fails on an empty list or a name no test registers: a typo
    or a short alias would otherwise silently run preflight alone and look green."""
    names = [n.strip() for n in text.split(",")]
    if not text.strip() or any(not n for n in names):
        raise ValueError("--only needs a comma-separated list of area names")
    unknown = sorted(set(names) - registered_areas())
    if unknown:
        raise ValueError(f"unknown area(s): {', '.join(unknown)}; known: {', '.join(sorted(registered_areas()))}")
    return set(names)


def expected(selected, layers):
    """Every (area, title) a complete run of this selection must report."""
    return [(area, title) for area, title, _ in expand(layers)
            if selected is None or area in selected or area == "preflight"]


def verdict(results, expected_ids):
    """Exit code and summary lines. 0 only when every expected test ran and passed without a skip
    or warning; 1 on a failure, error or a test that never ran; 3 when skips or warnings need a
    recorded gap decision (never an automatic pass)."""
    ran = [(r["area"], r["title"]) for r in results]
    missing = [i for i in expected_ids if i not in ran]
    extra = [i for i in ran if i not in expected_ids]
    counts = {s: sum(r["status"] == s for r in results) for s in ("PASS", "FAIL", "ERROR", "SKIP")}
    warned = sum(bool(r.get("warnings")) for r in results)
    lines = [f"{counts['PASS']} passed, {counts['FAIL']} failed, {counts['ERROR']} errors, {counts['SKIP']} skipped, "
             f"{warned} with warnings — {len(ran)} of {len(expected_ids)} expected tests ran"]
    lines += [f"NOT RUN: [{a}] {t}" for a, t in missing]
    lines += [f"UNEXPECTED: [{a}] {t}" for a, t in extra]
    if counts["FAIL"] or counts["ERROR"] or missing or extra:
        return 1, lines
    if counts["SKIP"] or warned:
        return 3, lines + ["Skips or warnings: review and record them before treating this run as a pass."]
    return 0, lines


def expand(layers):
    """Every test to run, a layered one once per layer in [layers], the Home layer marked in its title."""
    for area, title, fn in TESTS:
        for layer in (layers if getattr(fn, "layered", False) else ("back",)):
            yield area, title + (" [Home layer]" if layer == "home" else ""), _in_layer(fn, layer)


def run(device, selected, report_name, layers=("back",), expected_ids=None):
    results = []
    started = time.time()
    for area, title, fn in expand(layers):
        if selected is not None and area not in selected and area != "preflight":
            continue
        t0 = time.time()
        device.warnings = []
        crashes = len(device.crashes())
        try:
            device.log(f"  … [{area}] {title}")
            fn(device)
            status, detail = "PASS", ""
        except Skip as e:
            status, detail = "SKIP", str(e)
        except Fail as e:
            status, detail = "FAIL", str(e)
        except Exception as e:  # noqa: BLE001 - a broken test is a finding too
            status, detail = "ERROR", f"{e.__class__.__name__}: {e}\n{traceback.format_exc(limit=3)}"
        if status in ("FAIL", "ERROR"):
            # A test that stops halfway can leave the hotkeys paused or a key held; never let that
            # fail the tests after it.
            device.restart_service()
        new = device.crashes()[crashes:]
        if new:
            lines = [l for l in new[0].splitlines() if "Exception" in l or "\tat dev.steinhorst" in l][:4]
            status, detail = "FAIL", "THE APP CRASHED: " + " | ".join(l.split("): ", 1)[-1].strip() for l in lines)
            device.reconnect()
        warnings = sorted(set(device.warnings))
        results.append({"area": area, "title": title, "status": status, "detail": detail, "warnings": warnings,
                        "seconds": round(time.time() - t0, 1)})
        mark = {"PASS": "✓", "SKIP": "–", "FAIL": "✗", "ERROR": "!"}[status]
        device.log(f"{mark} [{area}] {title}" + (f"\n    {detail}" if detail else "")
                   + "".join(f"\n    ⚠ {w}" for w in warnings))
        if area == "preflight" and status != "PASS":
            device.log("Preflight failed, stopping.")
            break
    os.makedirs(WORK, exist_ok=True)
    path = os.path.join(WORK, report_name)
    with open(path, "w") as f:
        report = {"seconds": round(time.time() - started), "layers": list(layers),
                  "selected": None if selected is None else sorted(selected), "results": results}
        if expected_ids is not None:
            report["expected"] = [{"area": a, "title": t} for a, t in expected_ids]
        json.dump(report, f, indent=2)
    return results, path
