"""The gyro hotkey: its notices, and a gyro deflection reaching the game through the pad clone.

A test cannot turn the device, so the debug receiver replaces the gyroscope's readings with fixed
rates; everything after the sensor (mapping, the helper's mixing, the clone) is the real path."""

import time

from dt import pad
from dt.device import Fail
from dt.pad import Seq
from dt.runner import test

from .common import ensure_helper

# The right stick: Android calls it Z/RZ on the Thor's pad.
RIGHT = ("AXIS_Z", "AXIS_RZ")
# 1 rad/s around every axis: a full deflection at 300 % sensitivity, whatever the screen's rotation.
TURN = "1,1,1"
L2_KEYCODE = 104
# Following turns: the stick rests as soon as the readings do.
RATE = "rate=1,sens=300"


def deflected(d):
    return [e for e in d.probe_events() if e[0] == "AXIS" and e[1] in RIGHT and e[2] != "0"]


def centred(d):
    """Every right-stick axis the game saw last is back at rest."""
    last = {}
    for kind, axis, value in d.probe_events():
        if kind == "AXIS" and axis in RIGHT:
            last[axis] = value
    return last and all(v == "0" for v in last.values())


@test("gyro", "Gyro on/off shows its notices and adds the gyro to the right stick only while on")
def toggles(d):
    ensure_helper(d)
    d.bind("face_y", "@gyro", gyro=RATE)
    d.probe_start()
    try:
        d.press(Seq().combo(d.face("y")))
        d.expect_toast(d.text("gyro_on"))
        d.clear_log()
        d.app("gyro", value=TURN)
        d.eventually(lambda: deflected(d), 3, f"the gyro did not reach the game's right stick: {d.probe_events()}")
        d.app("gyro", value="0,0,0")
        d.eventually(lambda: centred(d), 3, f"the right stick did not come back to rest: {d.probe_events()}")
        d.press(Seq().combo(d.face("y")))
        d.expect_toast(d.text("gyro_off"))
        d.clear_log()
        d.app("gyro", value=TURN)
        time.sleep(0.8)
        if deflected(d):
            raise Fail(f"the gyro moved the stick while off: {d.probe_events()}")
    finally:
        d.app("gyro", value="off")
        d.probe_stop()
    d.unbind("face_y")


@test("gyro", "With a hold button the gyro steers only while it is held, and the button still reaches the game")
def hold(d):
    ensure_helper(d)
    d.bind("face_y", "@gyro", gyro=f"{RATE},key={L2_KEYCODE}")
    d.probe_start()
    try:
        d.press(Seq().combo(d.face("y")))
        d.expect_toast(d.text("gyro_on"))
        d.app("gyro", value=TURN)
        d.clear_log()
        time.sleep(0.6)
        if deflected(d):
            raise Fail(f"the gyro steered without its button held: {d.probe_events()}")
        d.press(Seq().key(pad.L2, True).wait(900))
        try:
            d.eventually(lambda: deflected(d), 3, f"holding L2 did not steer: {d.probe_events()}")
            if ("KEY", "DOWN", "KEYCODE_BUTTON_L2") not in d.probe_events():
                raise Fail(f"the held button did not reach the game: {d.probe_events()}")
        finally:
            d.press(Seq().key(pad.L2, False).wait(100))
        d.eventually(lambda: centred(d), 3, f"letting go of L2 did not stop the gyro: {d.probe_events()}")
        d.press(Seq().combo(d.face("y")))
        d.expect_toast(d.text("gyro_off"))
    finally:
        d.app("gyro", value="off")
        d.probe_stop()
    d.unbind("face_y")


@test("gyro", "Tilting holds the stick while the handheld stays tilted, and Calibrate gyro makes that posture the middle")
def tilt_and_calibrate(d):
    ensure_helper(d)
    d.bind("face_y", "@gyro", gyro="sens=300")
    d.bind("face_x", "@gyro_calibrate")
    d.probe_start()
    try:
        d.press(Seq().combo(d.face("y")))
        d.expect_toast(d.text("gyro_on"))
        d.clear_log()
        d.app("gyro", value=TURN)
        d.eventually(lambda: deflected(d), 3, f"tilting did not reach the game's right stick: {d.probe_events()}")
        d.app("gyro", value="0,0,0")
        time.sleep(0.8)
        if centred(d):
            raise Fail(f"the stick went back to rest while the handheld stayed tilted: {d.probe_events()}")
        d.press(Seq().combo(d.face("x")))
        d.expect_toast(d.text("gyro_calibration_started"))
        d.expect_toast(d.text("gyro_calibrated"), timeout=22)
        d.eventually(lambda: centred(d), 3, f"calibrating did not bring the stick back to the middle: {d.probe_events()}")
        d.press(Seq().combo(d.face("y")))
        d.expect_toast(d.text("gyro_off"))
    finally:
        d.app("gyro", value="off")
        d.probe_stop()
    d.unbind("face_y")
    d.unbind("face_x")
