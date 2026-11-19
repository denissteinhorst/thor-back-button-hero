"""The activation shortcut pauses and resumes every hotkey."""

import time

from dt import pad
from dt.device import Fail
from dt.pad import Seq
from dt.runner import test

from .common import ensure_helper, expect_media, media_session

PAIRS = [
    ("l2_r2", "L2 + R2", (pad.L2, pad.R2)), ("l1_r1", "L1 + R1", (pad.L1, pad.R1)),
    ("both", "L3 + R3", (pad.L3, pad.R3)), ("start_select", "Start + Select", (pad.START, pad.SELECT)),
    ("back_home", "Back + Home", (pad.BACK, pad.HOME)),
]


def _hold(codes, ms):
    seq = Seq()
    for c in codes:
        seq.key(c, True)
    seq.wait(ms)
    for c in codes:
        seq.key(c, False)
    return seq.wait(300)


@test("toggle", "Every on/off pair pauses and resumes the hotkeys")
def pairs(d):
    ensure_helper(d)
    media_session(d)
    d.bind("face_x", "@media_next")
    d.put("toggle_hold_ms", 300)
    for value, label, codes in PAIRS:
        d.put("toggle_buttons", value)
        d.wait_toasts_gone()
        d.press(_hold(codes, 600))
        try:
            d.expect_toast(d.text("service_toggle_inactive", label))
        except Fail as e:
            raise Fail(f"{label}: {e}")
        expect_media(d, lambda: d.press(Seq().combo(d.face("x"))), [])
        d.press(_hold(codes, 600))
        d.expect_toast(d.text("service_toggle_active", label))
        expect_media(d, lambda: d.press(Seq().combo(d.face("x"))), ["MEDIA_NEXT"])


@test("toggle", "A pair held shorter than the hold time, or one button alone, does nothing")
def too_short(d):
    ensure_helper(d)
    media_session(d)
    d.bind("face_x", "@media_next")
    d.put("toggle_buttons", "l1_r1")
    d.put("toggle_hold_ms", 800)
    d.wait_toasts_gone()
    d.press(_hold((pad.L1, pad.R1), 300))
    d.press(_hold((pad.L1,), 1200))
    time.sleep(0.5)
    if d.toasts():
        raise Fail(f"toggled anyway: {d.toasts()}")
    expect_media(d, lambda: d.press(Seq().combo(d.face("x"))), ["MEDIA_NEXT"])


@test("toggle", "While paused, Back + button and Back + D-pad reach the game untouched")
def paused_passthrough(d):
    ensure_helper(d)
    media_session(d)
    d.bind("face_x", "@media_next")
    d.bind("dpad_left", "@media_next")
    d.put("toggle_buttons", "l1_r1")
    d.put("toggle_hold_ms", 300)
    d.probe_start()
    try:
        d.wait_toasts_gone()
        d.press(_hold((pad.L1, pad.R1), 600))
        d.expect_toast(d.text("service_toggle_inactive", "L1 + R1"))
        d.clear_log()
        expect_media(d, lambda: d.press(Seq().with_back(lambda s: s.tap(d.face("x")).hat("left"))), [])
        got = d.probe_events()
        if ("AXIS", "AXIS_HAT_X", "-1") not in got:
            raise Fail(f"paused, Back + D-pad left did not reach the game: {got}")
        if not any(e[:2] == ("KEY", "DOWN") and e[2].startswith("KEYCODE_BUTTON_") for e in got):
            raise Fail(f"paused, Back + X did not reach the game: {got}")
    finally:
        d.press(_hold((pad.L1, pad.R1), 600))
        d.probe_stop()
    d.expect_toast(d.text("service_toggle_active", "L1 + R1"))
