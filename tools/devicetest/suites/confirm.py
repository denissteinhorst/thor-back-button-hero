"""Confirm and cancel in both controller styles. Android's convention decides: the button reporting
BUTTON_A confirms and BUTTON_B cancels, in the app's prompts, the bottom switcher and top Recents alike.
Each test runs in Standard and in Xbox Style. A style switch recreates the pad under every app, so this
area runs with the disturbing checks at the end and returns to the style it found."""

import time

from dt import pad
from dt.device import Fail
from dt.pad import Seq
from dt.runner import test

from .common import combo, ensure_helper, expect_closed
from .style import HANDLES, _handle, _pads_settled
from .windows import GALLERY, LAUNCHERS, SETTINGS, KEY, _clean, _focused_card, _front, _press, _select_card

STYLE_KEY = "l1"
SETTINGS_PKG = "com.android.settings"
GALLERY_PKG = "com.android.gallery3d"


def _switch_style(d, option):
    d.bind(STYLE_KEY, "@handle", ayn_option=option)
    # A binding change can restart the helper; a combo pressed meanwhile is lost. One retry covers a
    # press that still slipped through while AYN's pad settled.
    for attempt in (1, 2):
        ensure_helper(d)
        combo(d, pad.L1)
        try:
            d.eventually(lambda: _handle(d) == HANDLES[option], 5, "")
            break
        except Fail:
            if attempt == 2:
                raise Fail(f"{option}: expected {HANDLES[option]}, got {_handle(d)}")
    if option != "ban":
        d.eventually(d.helper_pid, 10, "the helper did not survive the style switch")
        # AYN destroys and recreates its pad; a press before its pad and the helper's clone are both
        # back can reach apps twice.
        d.eventually(lambda: _pads_settled(d), 10, "the pad and the helper's clone did not come back")
        ensure_helper(d)
        time.sleep(2.0)


def _in_both_styles(d, check):
    """Runs [check] in Standard and in Xbox Style, then switches back to the style the run found."""
    ensure_helper(d)
    original = next((k for k, v in HANDLES.items() if v == _handle(d)), None)
    if original not in ("standard", "xbox"):
        raise Fail(f"needs Standard or Xbox Style to start, found {_handle(d)}")
    try:
        for option in (original, "xbox" if original == "standard" else "standard"):
            if _handle(d) != HANDLES[option]:
                _switch_style(d, option)
            try:
                check()
            except Fail as e:
                raise Fail(f"{option} style: {e}")
    finally:
        if _handle(d) != HANDLES[original]:
            try:
                _switch_style(d, original)
            except Fail:
                flip, layout, no_layout, kind = HANDLES[original]
                d.root(f"settings put system flip_button_layout {flip} && settings put system temp_abxy_layout_mode {layout} "
                       f"&& settings put system no_create_gamepad_button_layout {no_layout} && setprop persist.sys.gamepad.type {kind}")
                d.warnings.append(f"the hotkey could not switch back to {original}; restored as root")
                time.sleep(2.0)
        d.unbind(STYLE_KEY)


def _prompt(d, timeout=4):
    return d.eventually(lambda: d.own_windows(d.route() or d.top), timeout, "no prompt appeared")


def _prompt_gone(d, what):
    d.eventually(lambda: not d.own_windows(d.route() or d.top), 3, f"{what} left the prompt open")


@test("confirm", "Favorites launch with the confirm button; the cancel button closes the list and launches nothing")
def favorites(d):
    def check():
        _clean(d)
        d.start_app(GALLERY, d.top)
        d.focus(d.top)
        time.sleep(1.0)
        _press(d)
        _prompt(d)
        d.press(Seq().tap(d.cancel_key()))
        _prompt_gone(d, "cancel")
        time.sleep(0.8)
        if not d.resumed().get(d.top, "").startswith(GALLERY_PKG):
            raise Fail(f"cancel launched something: {d.resumed().get(d.top)}")
        _press(d)
        _prompt(d)
        d.press(Seq().tap(d.confirm_key()))
        _front(d, d.top, SETTINGS_PKG, 6)
        _prompt_gone(d, "confirm")

    d.put("favorite_apps", {SETTINGS_PKG})
    d.bind(KEY, "@app_favorites", display="top")
    try:
        _in_both_styles(d, check)
    finally:
        d.put("favorite_apps", set())
        _clean(d)


@test("confirm", "Force-close (ask) closes the chosen app with the confirm button; the cancel button leaves it running")
def force_close_ask(d):
    def check():
        _clean(d)
        d.start_app(SETTINGS, d.top)
        d.start_app(GALLERY, d.bottom)
        d.focus(d.top)
        time.sleep(1.0)
        settings, gallery = d.pid(SETTINGS_PKG), d.pid(GALLERY_PKG)
        if not settings or not gallery:
            raise Fail("Settings and Gallery need a process to start with")
        _press(d)
        _prompt(d)
        d.press(Seq().tap(d.cancel_key()))
        _prompt_gone(d, "cancel")
        time.sleep(1.0)
        if d.pid(SETTINGS_PKG) != settings or d.pid(GALLERY_PKG) != gallery:
            raise Fail("cancel still closed an app")
        _press(d)
        _prompt(d)
        # The first entry, highlighted on open, is the top screen.
        d.press(Seq().tap(d.confirm_key()))
        expect_closed(d, SETTINGS_PKG, settings)
        if d.pid(GALLERY_PKG) != gallery:
            raise Fail("confirming the top screen also closed the bottom app")

    d.bind(KEY, "@app_control", app_mode="force_close", app_screen="ask", app_confirm=False)
    try:
        _in_both_styles(d, check)
    finally:
        _clean(d)


@test("confirm", "The bottom switcher opens the selected app with the confirm button; the cancel button only closes it")
def switcher(d):
    apps = (SETTINGS_PKG, GALLERY_PKG)

    def open_switcher():
        d.keyevent("KEYCODE_HOME", d.bottom)
        d.eventually(lambda: d.resumed().get(d.bottom, "").startswith(LAUNCHERS), 4, "the bottom app stayed in front")
        _press(d)
        d.eventually(lambda: d.own_windows(d.bottom), 4, "the switcher did not open")
        time.sleep(0.8)

    def check():
        _clean(d)
        d.start_app(SETTINGS, d.top)
        d.start_app(GALLERY, d.bottom)
        open_switcher()
        d.press(Seq().tap(d.cancel_key()))
        d.eventually(lambda: not d.own_windows(d.bottom), 3, "cancel left the switcher open")
        time.sleep(0.8)
        if not d.resumed().get(d.bottom, "").startswith(LAUNCHERS):
            raise Fail(f"cancel opened an app: {d.resumed().get(d.bottom)}")
        open_switcher()
        d.press(Seq().tap(d.confirm_key()))
        d.eventually(lambda: d.resumed().get(d.bottom, "").startswith(apps), 6,
                     f"confirm opened nothing on the bottom screen: {d.resumed().get(d.bottom)}")
        d.eventually(lambda: not d.own_windows(d.bottom), 3, "the switcher stayed open after opening an app")

    d.bind(KEY, "@switcher")
    try:
        _in_both_styles(d, check)
    finally:
        if d.own_windows(d.bottom):
            d.press(Seq().tap(pad.BACK))
        _clean(d)


@test("confirm", "Top Recents opens the selected app with the confirm button; the cancel button closes Overview")
def recents(d):
    """Settings is the newest card and Gallery the one left of it; the D-pad walks there from wherever Overview starts."""
    def open_left():
        _press(d)
        d.eventually(lambda: d.resumed().get(d.top, "").startswith(LAUNCHERS), 5, "Recents did not open")
        time.sleep(1.5)
        # Settings is the newest card, Gallery the one left of it.
        _select_card(d, 0)
        d.press(Seq().hat("left"))
        d.eventually(lambda: _focused_card(d) == 1, 3, f"Left did not move to the previous card: {_focused_card(d)}")

    def check():
        _clean(d)
        d.start_app(GALLERY, d.top)
        time.sleep(0.8)
        d.start_app(SETTINGS, d.top)
        d.focus(d.top)
        open_left()
        d.press(Seq().tap(d.cancel_key()))
        _front(d, d.top, SETTINGS_PKG, 6)
        d.eventually(lambda: not d.own_windows(d.top), 5, f"cancel left the Recents session open: {d.own_windows(d.top)}")
        if d.route() != d.top:
            raise Fail(f"the controller left the top screen: {d.route()}")
        open_left()
        d.press(Seq().tap(d.confirm_key()))
        _front(d, d.top, GALLERY_PKG, 6)
        if d.resumed().get(d.bottom, "").startswith(GALLERY_PKG):
            raise Fail("the app opened on the bottom screen instead")

    d.bind(KEY, "@recents")
    try:
        _in_both_styles(d, check)
    finally:
        d.keyevent("KEYCODE_HOME", d.top)
        _clean(d)
