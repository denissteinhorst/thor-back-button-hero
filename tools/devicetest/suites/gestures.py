"""Back's own gestures: single, double, tap-and-hold and hold, with their timing."""

import time

from dt.device import Fail
from dt.pad import BACK, Seq
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


@test("gestures", "Single, double and tap-and-hold run their own bindings")
def gestures(d):
    ensure_helper(d)
    media_session(d)
    _gestures(d)
    expect_media(d, lambda: d.press(Seq().tap(BACK, after=600)), ["MEDIA_PLAY_PAUSE"])
    expect_media(d, lambda: d.press(Seq().tap(BACK, after=80).tap(BACK, after=500)), ["MEDIA_NEXT"])
    expect_media(d, lambda: d.press(Seq().tap(BACK, after=80).key(BACK, True).wait(900).key(BACK, False).wait(300)), ["MEDIA_PREVIOUS"])


@test("gestures", "Hold runs its binding and adds no tap")
def hold(d):
    ensure_helper(d)
    media_session(d)
    _gestures(d)
    d.wait_toasts_gone()
    expect_media(d, lambda: d.press(Seq().key(BACK, True).wait(1200).key(BACK, False).wait(500)), [])
    if not d.toasts():
        raise Fail("holding Back did not show the status overlay")


@test("gestures", "Without a double binding a single tap fires at once")
def no_double(d):
    ensure_helper(d)
    media_session(d)
    _gestures(d, double="")
    d.clear_log()
    t0 = time.time()
    d.press(Seq().tap(BACK, after=0))
    d.eventually(lambda: d.media_keys() == ["MEDIA_PLAY_PAUSE"], 3, f"no single tap: {d.media_keys()}")
    # The press itself takes about a second over adb; waiting out a double window would add more.
    if time.time() - t0 > 2.5:
        raise Fail("the single tap waited for a double tap that cannot come")


@test("gestures", "Unassigned gestures do nothing, Back + button still works")
def unassigned(d):
    ensure_helper(d)
    media_session(d)
    _gestures(d, single="", double="", tap_hold="", hold="")
    d.bind("l1", "@media_next")
    expect_media(d, lambda: d.press(Seq().tap(BACK, after=600).tap(BACK, after=80).tap(BACK, after=500)), [])
    from dt.pad import L1
    expect_media(d, lambda: d.press(Seq().combo(L1)), ["MEDIA_NEXT"])
