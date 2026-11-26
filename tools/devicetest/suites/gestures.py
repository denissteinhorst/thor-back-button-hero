"""Back's own gestures: single, double, tap-and-hold and hold, with their timing. Layered tests press
d.modifier, so they also prove dual mode's Home layer; the dual tests at the end check how the two
layers live side by side (docs/HOME-DUAL-MODE.md)."""

import time

from dt.device import Fail
from dt.pad import BACK, HOME, L1, Seq
from dt.runner import test

from .common import ensure_helper, expect_media, media_session


def _gestures(d, single="@media_play_pause", double="@media_next", tap_hold="@media_previous", hold="@status_overlay"):
    d.bind("back_single", single)
    d.bind("back_double", double)
    d.bind("back_tap_hold", tap_hold)
    d.bind("back_hold", hold)
    d.put("double_ms", 300)
    d.put("tap_hold_ms", 600)
    d.put("hold_ms", 800)


@test("gestures", "Single, double and tap-and-hold run their own bindings", layered=True)
def gestures(d):
    m = d.modifier
    ensure_helper(d)
    media_session(d)
    _gestures(d)
    expect_media(d, lambda: d.press(Seq().tap(m, after=600)), ["MEDIA_PLAY_PAUSE"])
    expect_media(d, lambda: d.press(Seq().tap(m, after=80).tap(m, after=500)), ["MEDIA_NEXT"])
    expect_media(d, lambda: d.press(Seq().tap(m, after=80).key(m, True).wait(900).key(m, False).wait(300)), ["MEDIA_PREVIOUS"])


@test("gestures", "Hold runs its binding and adds no tap", layered=True)
def hold(d):
    m = d.modifier
    ensure_helper(d)
    media_session(d)
    _gestures(d)
    d.wait_toasts_gone()
    expect_media(d, lambda: d.press(Seq().key(m, True).wait(1200).key(m, False).wait(500)), [])
    if not d.toasts():
        raise Fail("holding the modifier did not show the status overlay")


@test("gestures", "Without a double binding a single tap fires at once", layered=True)
def no_double(d):
    m = d.modifier
    ensure_helper(d)
    media_session(d)
    _gestures(d, double="")
    d.clear_log()
    t0 = time.time()
    d.press(Seq().tap(m, after=0))
    d.eventually(lambda: d.media_keys() == ["MEDIA_PLAY_PAUSE"], 3, f"no single tap: {d.media_keys()}")
    # The press itself takes about a second over adb; waiting out a double window would add more.
    if time.time() - t0 > 2.5:
        raise Fail("the single tap waited for a double tap that cannot come")


@test("gestures", "Unassigned gestures do nothing, Back + button still works", layered=True)
def unassigned(d):
    m = d.modifier
    ensure_helper(d)
    media_session(d)
    _gestures(d, single="", double="", tap_hold="", hold="")
    d.bind("l1", "@media_next")
    expect_media(d, lambda: d.press(Seq().tap(m, after=600).tap(m, after=80).tap(m, after=500)), [])
    expect_media(d, lambda: d.press(Seq().combo(L1)), ["MEDIA_NEXT"])


def _dual(d):
    """Dual mode on, both layers seen through media keys: L1 differs per layer."""
    ensure_helper(d)
    media_session(d)
    d.put("dual_mode", True)
    d.bind("l1", "@media_next")
    d.put("dual_l1_package", "@media_previous")


@test("dual", "Back + L1 and Home + L1 run their own layer's binding")
def dual_layers(d):
    _dual(d)
    try:
        expect_media(d, lambda: d.press(Seq().key(BACK, True).wait(250).tap(L1, after=0).wait(200).key(BACK, False)), ["MEDIA_NEXT"])
        expect_media(d, lambda: d.press(Seq().key(HOME, True).wait(250).tap(L1, after=0).wait(200).key(HOME, False)), ["MEDIA_PREVIOUS"])
    finally:
        d.put("dual_mode", False)


@test("dual", "The first modifier owns the press, the second is ignored")
def dual_first_owns(d):
    _dual(d)
    try:
        expect_media(d, lambda: d.press(Seq().key(HOME, True).wait(200).key(BACK, True).wait(200).tap(L1, after=0)
                                        .wait(200).key(BACK, False).wait(100).key(HOME, False)), ["MEDIA_PREVIOUS"])
        expect_media(d, lambda: d.press(Seq().key(BACK, True).wait(200).key(HOME, True).wait(200).tap(L1, after=0)
                                        .wait(200).key(HOME, False).wait(100).key(BACK, False)), ["MEDIA_NEXT"])
    finally:
        d.put("dual_mode", False)


@test("dual", "Home's gestures run the Home layer, Back's the Back layer")
def dual_gestures(d):
    _dual(d)
    try:
        d.bind("back_single", "@media_play_pause")
        d.unbind("back_double")
        d.put("dual_back_single_package", "@media_next")
        d.put("dual_back_double_package", "")
        expect_media(d, lambda: d.press(Seq().tap(HOME, after=600)), ["MEDIA_NEXT"])
        expect_media(d, lambda: d.press(Seq().tap(BACK, after=600)), ["MEDIA_PLAY_PAUSE"])
    finally:
        d.put("dual_mode", False)


@test("dual", "With dual mode off the Home layer rests")
def dual_off(d):
    _dual(d)
    d.put("dual_back_single_package", "@media_next")
    d.put("dual_mode", False)
    time.sleep(0.5)
    # Home + L1 is not a shortcut then, and Home itself is Android's again.
    expect_media(d, lambda: d.press(Seq().key(HOME, True).wait(250).tap(L1, after=0).wait(200).key(HOME, False).wait(400)), [])
