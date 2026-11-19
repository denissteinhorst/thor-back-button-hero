"""Volume and brightness per screen, including the repeat while a stick stays deflected."""

import time

from dt.device import Fail
from dt.pad import Seq
from dt.runner import test

from .common import ensure_helper


def _start_volume(d, top=7, bottom=7):
    d.sh(f"cmd media_session volume --stream 3 --set {top}")
    d.root(f"settings put system secondary_screen_volume_level {bottom}")
    d.eventually(lambda: d.volume() == (top, bottom), 3, "could not set the starting volume")


def _dpad(d, direction, action, target):
    d.bind(f"dpad_{direction}", action, target=target)
    d.press(Seq().with_back(lambda s: s.hat(direction)))


@test("levels", "Volume up/down on top, bottom and both screens")
def volume(d):
    ensure_helper(d)
    for action, target, delta in [
        ("@volume_up", "top", (1, 0)), ("@volume_up", "bottom", (0, 1)), ("@volume_down", "both", (-1, -1)),
    ]:
        _start_volume(d)
        _dpad(d, "right", action, target)
        want = (7 + delta[0], 7 + delta[1])
        d.eventually(lambda: d.volume() == want, 3, f"{action} {target}: expected {want}, got {d.volume()}")


@test("levels", "Toggle mute silences and restores both screens")
def mute(d):
    ensure_helper(d)
    _start_volume(d, 6, 5)
    _dpad(d, "left", "@volume_mute", "both")
    d.eventually(lambda: d.volume() == (0, 0), 3, f"mute: expected (0, 0), got {d.volume()}")
    d.press(Seq().with_back(lambda s: s.hat("left")))
    d.eventually(lambda: d.volume() == (6, 5), 3, f"unmute: expected (6, 5), got {d.volume()}")


@test("levels", "Brightness up/down on top, bottom and both screens")
def brightness(d):
    ensure_helper(d)
    for action, target in [("@brightness_up", "top"), ("@brightness_up", "bottom"), ("@brightness_down", "both")]:
        for display in (d.top, d.bottom):
            d.adb.probe("set", str(display), "0.3")
        time.sleep(0.3)
        before = d.brightness()
        _dpad(d, "up", action, target)
        up = action.endswith("up")

        def moved():
            now = d.brightness()
            changed = [abs(n - b) > 1e-4 for n, b in zip(now, before)]
            direction = all((n > b) == up for n, b, c in zip(now, before, changed) if c)
            return changed == [target != "bottom", target != "top"] and direction
        d.eventually(moved, 3, f"{action} {target}: {before} -> {d.brightness()}")


@test("levels", "A held stick repeats the volume step")
def repeat(d):
    ensure_helper(d)
    _start_volume(d, 3, 7)
    d.bind("lstick_up", "@volume_up", target="top")
    d.press(Seq().with_back(lambda s: s.stick("left", "up", ms=1400)))
    time.sleep(0.4)
    top = d.volume()[0]
    if top < 3 + 3:
        raise Fail(f"holding the stick 1.4 s stepped only {top - 3} times")
    d.unbind("lstick_up")
