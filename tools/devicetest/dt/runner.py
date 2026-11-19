"""Test registry, area selection and the report."""

import json
import os
import time
import traceback

from .adb import WORK
from .device import Fail

TESTS = []


class Skip(Exception):
    pass


def test(area, title):
    """Registers a test; tests run in the order their modules import them."""
    def register(fn):
        TESTS.append((area, title, fn))
        return fn
    return register


# Which areas a changed source file can affect: used by --changed.
FILE_AREAS = {
    "AynControls.kt": ["ayn"],
    "LevelControl.kt": ["levels"], "LevelRepeater.kt": ["levels"], "LevelSteps.kt": ["levels"], "BrightnessControl.kt": ["levels"],
    "StatusSummary.kt": ["media"],
    "BackGestureMachine.kt": ["gestures", "toggle"], "DoubleTapDetector.kt": ["gestures"],
    "StickClickMachine.kt": ["sources"], "StickButton.kt": ["sources"], "ShoulderButton.kt": ["sources", "gestures"],
    "PadHelper.kt": ["sources", "toggle", "macro"], "padhelper.c": ["sources", "toggle", "macro"],
    "DpadOverlay.kt": ["sources"], "PhysicalKeyTracker.kt": ["sources", "gestures"],
    "ServiceToggleGesture.kt": ["toggle"], "KeyInterceptionGate.kt": ["toggle"],
    "HomeAsBack.kt": ["gestures"], "AppExceptions.kt": ["exceptions"], "ExceptionGroups.kt": ["exceptions"],
    "Macro.kt": ["macro"],
    "ControllerFocus.kt": ["windows"], "AutoControllerFocus.kt": ["windows"], "FocusIndicatorOverlay.kt": ["windows"],
    "AppControl.kt": ["appcontrol"], "CloseApps.kt": ["appcontrol"], "TaskRemover.kt": ["appcontrol"],
    "ScreenRecorder.kt": ["capture"], "ScreenRecording.kt": ["capture"], "SystemScreenshot.kt": ["capture"],
    "ScreenshotSaver.kt": ["capture"], "PushToTalk.kt": ["capture"],
    "PowerMenu.kt": ["panels"], "LastApp.kt": ["windows"], "NoticeOverlay.kt": ["ayn", "toggle", "media"], "CardTouch.kt": ["windows"], "ControllerFocusTouch.kt": ["windows"],
    "AccessibilityAppSource.kt": ["windows", "appcontrol", "exceptions"], "RunningApp.kt": ["windows", "appcontrol"],
    "RecordingIndicatorOverlay.kt": ["capture"], "EchoTrim.kt": ["capture"], "VoiceBand.kt": ["capture"],
    "VoiceLeveler.kt": ["capture"], "VoicePolish.kt": ["capture"],
}
# Changes here can break anything the service does.
EVERYTHING = {"SwitcherService.kt", "Prefs.kt", "PService.kt", "AndroidManifest.xml", "accessibility_config.xml", "Shell.kt"}


def areas_for(files):
    areas = set()
    for path in files:
        name = os.path.basename(path)
        if name in EVERYTHING:
            return None
        areas.update(FILE_AREAS.get(name, []))
        if name.startswith(("Recents", "TopRecents", "Switcher", "Panel", "BottomPanels", "TaskMove", "Frontend", "Parking", "TaskParker")):
            areas.add("windows")
    return areas


def run(device, selected, report_name):
    results = []
    started = time.time()
    for area, title, fn in TESTS:
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
        json.dump({"seconds": round(time.time() - started), "results": results}, f, indent=2)
    return results, path
