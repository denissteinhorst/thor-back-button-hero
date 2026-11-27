"""A "Press button" hotkey holds a pad button through the helper's clone for as long as its trigger.

The clone is watched with getevent: the pressed button must go down after the trigger and come up only
after the trigger was released. The stand-in game shows that the trigger itself never reaches it, and the
media session that a pressed button with its own binding does not run that binding."""

from dt import pad
from dt.device import Fail
from dt.pad import Seq
from dt.runner import test

from .common import ensure_helper, expect_media, media_session

EV_KEY, EV_ABS = 1, 3

# Android key names of the face buttons' evdev codes.
ANDROID_FACE = {304: "A", 305: "B", 307: "X", 308: "Y"}

# How long each trigger is held: well above a tap, so a press that is only tapped is told apart.
HOLD_MS = 700


def held_inside(events, trigger, target, min_hold=0.4):
    """Checks a timed clone capture [(seconds, type, code, value)]: [target] (type, code) goes down after
    [trigger] went down (when the trigger reaches the clone at all), stays down at least [min_hold] seconds
    and comes up after the trigger did. Returns a problem, or None."""
    def first(kind, code, pressed, after=-1.0):
        return next((t for t, ty, c, v in events if ty == kind and c == code and (v != 0) == pressed and t >= after), None)

    target_down = first(*target, True)
    if target_down is None:
        return f"the clone never pressed {target}"
    target_up = first(*target, False, target_down)
    if target_up is None:
        return f"the clone never released {target}: it would stay pressed"
    if target_up - target_down < min_hold:
        return f"{target} was held {target_up - target_down:.2f} s, not as long as the trigger"
    if trigger is not None:
        trigger_down = first(*trigger, True)
        trigger_up = first(*trigger, False, trigger_down or 0.0)
        if trigger_down is None or trigger_up is None:
            return f"the trigger {trigger} did not reach the clone"
        if not trigger_down <= target_down <= trigger_up <= target_up:
            return (f"{target} was not held inside the trigger: trigger {trigger_down:.3f}-{trigger_up:.3f}, "
                    f"pressed {target_down:.3f}-{target_up:.3f}")
    return None


def _expect_held(d, seq, trigger, target):
    events = d.press_watching(seq, settle=0.8, timed=True)
    problem = held_inside(events, trigger, target)
    if problem:
        raise Fail(f"{problem}; clone saw {[e[1:] for e in events]}")
    return events


@test("press", "Back + L1 holds printed A while L1 is held; L1 never reaches the game")
def shoulder_holds_face(d):
    ensure_helper(d)
    d.bind("l1", "@pad_a")
    a = d.face("a")
    key_a = f"KEYCODE_BUTTON_{ANDROID_FACE[a]}"
    d.probe_start()
    try:
        d.clear_log()
        _expect_held(d, Seq().with_back(lambda s: s.key(pad.L1, True).wait(HOLD_MS).key(pad.L1, False), trail=300),
                     (EV_KEY, pad.L1), (EV_KEY, a))
        got = d.probe_events()
        if ("KEY", "DOWN", key_a) not in got or ("KEY", "UP", key_a) not in got:
            raise Fail(f"the pressed {key_a} did not reach the game: {got}")
        if any(e[2] == "KEYCODE_BUTTON_L1" for e in got):
            raise Fail(f"the hotkey's own L1 leaked to the game: {got}")
    finally:
        d.probe_stop()
        d.unbind("l1")


@test("press", "Back + D-pad holds R1 while the D-pad is held; the D-pad never reaches the game")
def dpad_holds_shoulder(d):
    ensure_helper(d)
    d.bind("dpad_up", "@pad_r1")
    d.probe_start()
    try:
        d.clear_log()
        events = _expect_held(d, Seq().with_back(lambda s: s.hat("up", ms=HOLD_MS), trail=300), None, (EV_KEY, pad.R1))
        if any(ty == EV_ABS and c == pad.HAT_Y and v != 0 for _, ty, c, v in events):
            raise Fail(f"the helper let the hotkey's D-pad press through to the clone: {[e[1:] for e in events]}")
        got = d.probe_events()
        if ("KEY", "DOWN", "KEYCODE_BUTTON_R1") not in got:
            raise Fail(f"the pressed R1 did not reach the game: {got}")
        if any(e[:2] == ("AXIS", "AXIS_HAT_Y") and e[2] != "0" for e in got):
            raise Fail(f"the hotkey's D-pad leaked to the game: {got}")
    finally:
        d.probe_stop()
        d.unbind("dpad_up")


@test("press", "Back + L3 holds Start while L3 is held")
def stick_click_holds_start(d):
    ensure_helper(d)
    # Without a double press or tap-and-hold on the stick the press needs no gesture timing.
    d.unbind("lstick_double", "lstick_tap_hold")
    d.bind("lstick_press", "@pad_start")
    try:
        _expect_held(d, Seq().with_back(lambda s: s.key(pad.L3, True).wait(HOLD_MS).key(pad.L3, False), trail=300),
                     (EV_KEY, pad.L3), (EV_KEY, pad.START))
    finally:
        d.unbind("lstick_press")


@test("press", "A pressed button with its own Back binding reaches the game instead of running that binding")
def pressed_button_is_no_combo(d):
    ensure_helper(d)
    media_session(d)
    d.bind("l1", "@pad_a")
    d.bind("face_a", "@media_play_pause")
    a = d.face("a")
    key_a = f"KEYCODE_BUTTON_{ANDROID_FACE[a]}"
    d.probe_start()
    try:
        d.clear_log()
        expect_media(d, lambda: d.press(Seq().with_back(lambda s: s.key(pad.L1, True).wait(HOLD_MS).key(pad.L1, False),
                                                        trail=300)), [])
        got = d.probe_events()
        if ("KEY", "DOWN", key_a) not in got:
            raise Fail(f"the pressed {key_a} did not reach the game: {got}")
        # The binding still works for a real press.
        expect_media(d, lambda: d.press(Seq().combo(a)), ["MEDIA_PLAY_PAUSE"])
    finally:
        d.probe_stop()
        d.unbind("l1", "face_a")
