"""Active App Control and closing apps: minimize, force-close, restart, the prompts, close all/background."""

import time

from dt import pad
from dt.device import Fail
from dt.pad import Seq
from dt.runner import test
from .common import expect_closed, helper_settled

from .windows import GALLERY, LAUNCHERS, SETTINGS, _clean, _front, _press

KEY = "face_y"
SETTINGS_PKG = "com.android.settings"
GALLERY_PKG = "com.android.gallery3d"


def _app_control(d, mode, screen="top", confirm=False):
    d.bind(KEY, "@app_control", app_mode=mode, app_screen=screen, app_confirm=confirm)
    # A helper restarting after the previous area would swallow the combo; then Back alone sends the
    # app away, which looks like a minimize and hides the lost press.
    helper_settled(d)


def _settings_on_top(d):
    _clean(d)
    d.start_app(SETTINGS, d.top)
    d.focus(d.top)
    # The service learns about apps from window events; give it the moment it needs.
    time.sleep(1.0)
    pid = d.pid(SETTINGS_PKG)
    if not pid:
        raise Fail("Settings has no process after starting")
    return pid


def _prompt(d, timeout=4):
    return d.eventually(lambda: d.own_windows(d.route() or d.top), timeout, "no prompt appeared")


def _choose(d, moves_down=0):
    """Picks a prompt entry the way a player does: D-pad down [moves_down] times, then confirm.
    The confirm button is the bottom one in both styles, printed B on the Thor (the app asks Android
    for BUTTON_A in Xbox Style and BUTTON_B otherwise, and the style swaps the codes to match)."""
    seq = Seq()
    for _ in range(moves_down):
        seq.hat("down")
    d.press(seq.tap(d.face("b")))


@test("appcontrol", "Minimize sends the top app to the background and keeps it running")
def minimize(d):
    try:
        pid = _settings_on_top(d)
        _app_control(d, "minimize")
        _press(d)
        d.eventually(lambda: d.resumed().get(d.top, "").startswith(LAUNCHERS), 5,
                     f"Settings stayed in front: {d.resumed().get(d.top)}")
        if d.pid(SETTINGS_PKG) != pid:
            raise Fail("minimize ended or restarted Settings")
    finally:
        _clean(d)


@test("appcontrol", "Force-close ends the top app")
def force_close(d):
    try:
        pid = _settings_on_top(d)
        _app_control(d, "force_close")
        _press(d)
        expect_closed(d, SETTINGS_PKG, pid)
    finally:
        _clean(d)


@test("appcontrol", "Restart ends the top app and opens it again on the same screen")
def restart(d):
    try:
        pid = _settings_on_top(d)
        _app_control(d, "restart")
        _press(d)
        d.eventually(lambda: d.pid(SETTINGS_PKG) not in ("", pid), 8, "Settings was not started again")
        _front(d, d.top, SETTINGS_PKG, 6)
        if d.resumed().get(d.bottom, "").startswith(SETTINGS_PKG):
            raise Fail("Settings came back on the bottom screen")
    finally:
        _clean(d)


@test("appcontrol", "The confirmation prompt restarts on Yes and leaves the app alone on Cancel")
def confirm(d):
    try:
        pid = _settings_on_top(d)
        _app_control(d, "restart", confirm=True)
        _press(d)
        _prompt(d)
        _choose(d, moves_down=1)
        d.eventually(lambda: not d.own_windows(d.top), 3, "Cancel left the prompt open")
        time.sleep(1.0)
        if d.pid(SETTINGS_PKG) != pid:
            raise Fail("Cancel still restarted Settings")
        _press(d)
        _prompt(d)
        _choose(d)
        d.eventually(lambda: d.pid(SETTINGS_PKG) not in ("", pid), 8, "Yes did not restart Settings")
    finally:
        _clean(d)


@test("appcontrol", "Asking for the screen offers top, bottom and both, and acts on the choice")
def ask_screen(d):
    try:
        pid = _settings_on_top(d)
        d.start_app(GALLERY, d.bottom)
        d.focus(d.top)
        time.sleep(1.0)
        gallery = d.pid(GALLERY_PKG)
        _app_control(d, "force_close", screen="ask")
        _press(d)
        _prompt(d)
        _choose(d)
        expect_closed(d, SETTINGS_PKG, pid)
        if d.pid(GALLERY_PKG) != gallery:
            raise Fail("choosing the top screen also closed the bottom app")
    finally:
        _clean(d)


@test("appcontrol", "Close all apps ends every app on both screens")
def close_all(d):
    try:
        pid = _settings_on_top(d)
        d.start_app(GALLERY, d.bottom)
        time.sleep(1.0)
        gallery = d.pid(GALLERY_PKG)
        d.bind(KEY, "@close_apps", target="both", close_confirm=False)
        _press(d)
        expect_closed(d, SETTINGS_PKG, pid)
        expect_closed(d, GALLERY_PKG, gallery)
    finally:
        _clean(d)


@test("appcontrol", "Close background apps spares the app in front")
def close_background(d):
    try:
        pid = _settings_on_top(d)
        d.start_app(GALLERY, d.top)
        time.sleep(1.0)
        gallery = d.pid(GALLERY_PKG)
        d.bind(KEY, "@close_background_apps", target="top", close_confirm=False)
        _press(d)
        expect_closed(d, SETTINGS_PKG, pid)
        if d.pid(GALLERY_PKG) != gallery:
            raise Fail("the app in front was closed too")
    finally:
        _clean(d)


@test("appcontrol", "Close apps asks first by default and Cancel keeps everything")
def close_prompt(d):
    try:
        pid = _settings_on_top(d)
        d.bind(KEY, "@close_apps", target="top", close_confirm=True)
        _press(d)
        _prompt(d)
        _choose(d, moves_down=1)
        time.sleep(1.0)
        if d.pid(SETTINGS_PKG) != pid:
            raise Fail("Cancel still closed Settings")
        _press(d)
        _prompt(d)
        _choose(d)
        expect_closed(d, SETTINGS_PKG, pid)
    finally:
        _clean(d)
        if d.own_windows(d.top):
            d.press(Seq().tap(pad.BACK))
