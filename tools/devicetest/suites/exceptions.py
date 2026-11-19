"""Per-app exceptions, Home as Back, and the service surviving a restart."""

import time

from dt import pad
from dt.device import PKG, Fail
from dt.pad import Seq
from dt.runner import test

from .common import ensure_helper, expect_media, media_session
from .windows import SETTINGS

ALL = (1 << 33) - 1
X_BIT = 1 << 31
BACK_BITS = 0b1111


SETTINGS_PKG = "com.android.settings"


def _exception(d, allowed):
    d.put("app_exceptions", f"{SETTINGS_PKG}\t{allowed}\t1\tSettings")


def _settings_focused(d):
    """Settings stands in for the game: the app's own windows never count as the focused app."""
    d.stop_app(SETTINGS_PKG)
    d.start_app(SETTINGS, d.top)
    d.focus(d.top)
    time.sleep(0.8)


@test("exceptions", "An app's exception blocks just the excluded combo")
def blocks_combo(d):
    ensure_helper(d)
    media_session(d)
    d.bind("face_x", "@media_next")
    d.bind("face_y", "@media_previous")
    try:
        _settings_focused(d)
        _exception(d, ALL & ~X_BIT)
        time.sleep(0.5)
        expect_media(d, lambda: d.press(Seq().combo(d.face("x"))), [])
        expect_media(d, lambda: d.press(Seq().combo(d.face("y"))), ["MEDIA_PREVIOUS"])
        d.put("app_exceptions", f"{SETTINGS_PKG}\t{ALL & ~X_BIT}\t0\tSettings")
        time.sleep(0.5)
        expect_media(d, lambda: d.press(Seq().combo(d.face("x"))), ["MEDIA_NEXT"])
    finally:
        d.put("app_exceptions", "")
        d.stop_app(SETTINGS_PKG)


@test("exceptions", "With every Back gesture excluded, Back reaches the app untouched")
def native_back(d):
    media_session(d)
    d.bind("back_single", "@media_play_pause")
    try:
        _settings_focused(d)
        _exception(d, ALL & ~BACK_BITS)
        time.sleep(0.5)
        expect_media(d, lambda: d.press(Seq().tap(pad.BACK).wait(600)), [])
        # Back on Settings' main page closes it: that is Back arriving natively.
        d.eventually(lambda: not d.resumed().get(d.top, "").startswith(SETTINGS_PKG), 4,
                     "Back did not reach Settings")
        d.put("app_exceptions", "")
        _settings_focused(d)
        expect_media(d, lambda: d.press(Seq().tap(pad.BACK).wait(600)), ["MEDIA_PLAY_PAUSE"])
        if not d.resumed().get(d.top, "").startswith(SETTINGS_PKG):
            raise Fail("without the exception Back still closed Settings")
    finally:
        d.put("app_exceptions", "")
        d.stop_app(SETTINGS_PKG)


@test("gestures", "Home as Back: Home runs Back's gestures and combos; off, Home is Home again")
def home_as_back(d):
    ensure_helper(d)
    media_session(d)
    d.bind("back_single", "@media_play_pause")
    d.bind("face_x", "@media_next")
    d.put("home_as_back", True)
    time.sleep(0.5)
    expect_media(d, lambda: d.press(Seq().tap(pad.HOME).wait(600)), ["MEDIA_PLAY_PAUSE"])
    expect_media(d, lambda: d.press(Seq().key(pad.HOME, True).wait(250).tap(d.face("x"), after=0)
                                    .wait(200).key(pad.HOME, False)), ["MEDIA_NEXT"])
    d.put("home_as_back", False)
    time.sleep(0.5)
    d.probe_start()
    try:
        expect_media(d, lambda: d.press(Seq().tap(pad.HOME).wait(600)), [])
    finally:
        d.probe_stop()


@test("service", "After the service restarts, the helper and every hotkey come back")
def restart(d):
    ensure_helper(d)
    media_session(d)
    d.bind("face_x", "@media_next")
    before = d.helper_pid()
    enabled = d.setting("enabled_accessibility_services", "secure")
    others = ":".join(c for c in enabled.split(":") if c and PKG not in c)
    d.sh(f"settings put secure enabled_accessibility_services {others or 'null'}")
    try:
        d.eventually(lambda: not d.helper_pid(), 8, "the helper kept running with the service off")
    finally:
        d.reconnect()
    d.eventually(d.helper_pid, 12, "the helper did not start again")
    if d.helper_pid() == before:
        raise Fail("the helper was never restarted")
    time.sleep(1.5)
    expect_media(d, lambda: d.press(Seq().combo(d.face("x"))), ["MEDIA_NEXT"])
