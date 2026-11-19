"""Every hotkey source fires its own binding, and the game still gets what is not bound.

All sources point at media keys of the test session, which makes each press countable without
touching anything on screen."""

import time

from dt import pad
from dt.device import Fail
from dt.pad import Seq
from dt.runner import test

from .common import ensure_helper, expect_media, media_session

BUTTONS = [
    ("l1", pad.L1), ("r1", pad.R1), ("l2", pad.L2), ("r2", pad.R2),
    ("face_a", "a"), ("face_b", "b"), ("face_x", "x"), ("face_y", "y"),
    ("start", pad.START), ("select", pad.SELECT), ("home", pad.HOME),
]

# Android key names of the face buttons' evdev codes.
ANDROID_FACE = {304: "A", 305: "B", 307: "X", 308: "Y"}


@test("sources", "Back + every button runs its binding")
def buttons(d):
    ensure_helper(d)
    media_session(d)
    for pref, _ in BUTTONS:
        d.bind(pref, "@media_play_pause")
    for pref, code in BUTTONS:
        code = d.face(code) if isinstance(code, str) else code
        try:
            expect_media(d, lambda: d.press(Seq().combo(code)), ["MEDIA_PLAY_PAUSE"])
        except Fail as e:
            raise Fail(f"Back + {pref}: {e}")
    d.unbind(*(p for p, _ in BUTTONS))


@test("sources", "Back + every D-pad direction and stick direction runs its binding")
def directions(d):
    ensure_helper(d)
    media_session(d)
    cases = []
    for direction in ("up", "down", "left", "right"):
        cases.append((f"dpad_{direction}", lambda s, x=direction: s.hat(x)))
        cases.append((f"lstick_{direction}", lambda s, x=direction: s.stick("left", x)))
        cases.append((f"rstick_{direction}", lambda s, x=direction: s.stick("right", x)))
    for pref, _ in cases:
        d.bind(pref, "@media_next")
    for pref, move in cases:
        try:
            expect_media(d, lambda: d.press(Seq().with_back(move)), ["MEDIA_NEXT"])
        except Fail as e:
            raise Fail(f"Back + {pref}: {e}")
    for pref, _ in cases:
        if pref != "dpad_down":
            d.unbind(pref)


@test("sources", "Stick clicks tell press, double press and tap-and-hold apart")
def stick_clicks(d):
    ensure_helper(d)
    media_session(d)
    for side, code in (("lstick", pad.L3), ("rstick", pad.R3)):
        d.bind(f"{side}_press", "@media_play_pause")
        d.bind(f"{side}_double", "@media_next")
        d.bind(f"{side}_tap_hold", "@media_previous")
        d.put(f"{side}_double_ms", 300)
        d.put(f"{side}_tap_hold_ms", 600)
        cases = [
            ("press", lambda s: s.tap(code, after=500), ["MEDIA_PLAY_PAUSE"]),
            ("double", lambda s: s.tap(code, after=80).tap(code, after=400), ["MEDIA_NEXT"]),
            ("tap-hold", lambda s: s.tap(code, after=80).key(code, True).wait(900).key(code, False), ["MEDIA_PREVIOUS"]),
        ]
        for name, inner, want in cases:
            try:
                expect_media(d, lambda: d.press(Seq().with_back(inner, trail=300)), want)
            except Fail as e:
                raise Fail(f"{side} {name}: {e}")
        d.unbind(f"{side}_press", f"{side}_double", f"{side}_tap_hold")


@test("sources", "Bound presses are withheld from the game, unbound ones reach it")
def passthrough(d):
    """A stand-in game on the top screen logs what it receives: unbound presses arrive, while
    Back + a bound button or any D-pad direction does not reach it."""
    ensure_helper(d)
    media_session(d)
    d.bind("face_a", "@media_play_pause")
    d.bind("dpad_left", "@media_play_pause")
    d.unbind("face_b", "dpad_right")
    a, b = d.face("a"), d.face("b")
    # What Android names them depends on the layout setting, not on the print.
    key_a, key_b = (f"KEYCODE_BUTTON_{ANDROID_FACE[c]}" for c in (a, b))
    d.probe_start()
    try:
        d.clear_log()
        d.press(Seq().tap(a).hat("left"))
        d.eventually(lambda: len(d.probe_events()) >= 3, 3, "")
        got = d.probe_events()
        for want in [("KEY", "DOWN", key_a), ("AXIS", "AXIS_HAT_X", "-1")]:
            if want not in got:
                raise Fail(f"unbound {want[2]} alone did not reach the game: {got}")
        expect_media(d, lambda: d.press(Seq().with_back(lambda s: s.tap(a).hat("left"))),
                     ["MEDIA_PLAY_PAUSE", "MEDIA_PLAY_PAUSE"])
        leaked = [e for e in d.probe_events() if e[2] != "0"]
        if leaked:
            raise Fail(f"Back + bound A / D-pad left leaked to the game: {leaked}")
        d.clear_log()
        d.press(Seq().with_back(lambda s: s.tap(b).hat("right")))
        time.sleep(0.8)
        got = d.probe_events()
        # Buttons without a binding pass; every D-pad press under Back is withheld by design (HELPER.md).
        if ("KEY", "DOWN", key_b) not in got:
            raise Fail(f"Back + unbound B should reach the game: {got}")
        if ("AXIS", "AXIS_HAT_X", "1") in got:
            raise Fail(f"Back + D-pad right reached the game, the helper should withhold it: {got}")
    finally:
        d.probe_stop()
    d.unbind("face_a", "dpad_left")
