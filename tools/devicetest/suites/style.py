"""The checks that disturb the device most, run last: AYN's choice prompt, switching a screen off
and on, and the controller style, which recreates the pad under every app."""

import time

from dt import pad
from dt.device import Fail
from dt.pad import Seq
from dt.runner import test

from .common import combo, ensure_helper, expect_media, media_session

HANDLES = {
    # option: flip_button_layout, temp_abxy_layout_mode, no_create_gamepad_button_layout, gamepad type
    "standard": ("0", "1", "0", "0"),
    "xbox": ("1", "0", "0", "1"),
    "ban": ("0", "2", "1", "2"),
}


def _cover(d, display, before):
    """AYN darkens a screen with its own window over it (power and backlight stay on): what is new there."""
    return {t for t in set(d.windows(display)) - before if "backbuttonhero" not in t}


def _pads_settled(d):
    """AYN's new pad (hidden behind the helper's unlinked node) and the helper's clone both exist."""
    kind = d.sh("getprop persist.sys.gamepad.type").strip()
    name = {"0": "Odin Controller", "1": "Xbox Wireless Controller"}.get(kind)
    return bool(name) and d.sh("cat /proc/bus/input/devices").count(f'N: Name="{name}"') == 2


def _handle(d):
    return (d.setting("flip_button_layout"), d.setting("temp_abxy_layout_mode"),
            d.setting("no_create_gamepad_button_layout"), d.sh("getprop persist.sys.gamepad.type").strip())


@test("ayn", "AYN's choice prompt applies the option picked with the controller")
def modal(d):
    ensure_helper(d)
    d.root("settings put system performance_mode 0")
    d.bind("l1", "@performance", ayn_option="modal")
    combo(d, pad.L1)
    d.eventually(lambda: d.own_windows(d.top) or d.own_windows(d.bottom), 4, "the choice prompt did not appear")
    # High, Medium, Standard: one down is Medium.
    d.press(Seq().hat("down").tap(d.confirm_key()))
    d.eventually(lambda: d.setting("performance_mode") == "1", 4,
                 f"the prompt did not apply Medium: {d.setting('performance_mode')}")


@test("ayn", "Screen switch turns the bottom and the top screen off and on again")
def screens(d):
    ensure_helper(d)
    if d.setting("dual_screen_display_mode") not in ("0", "null"):
        raise Fail(f"needs both screens on to start, mode is {d.setting('dual_screen_display_mode')}")
    try:
        for option, dark, mode in (("bottom", d.bottom, "1"), ("top", d.top, "2")):
            before = set(d.windows(dark))
            d.bind("l1", "@display", ayn_option=option)
            combo(d, pad.L1)
            d.eventually(lambda: d.setting("dual_screen_display_mode") == mode, 5,
                         f"{option} off: mode stayed {d.setting('dual_screen_display_mode')}")
            cover = d.eventually(lambda: _cover(d, dark, before), 5, f"{option} off: AYN did not cover the screen")
            time.sleep(1.0)
            combo(d, pad.L1)
            d.eventually(lambda: d.setting("dual_screen_display_mode") == "0", 5, f"{option} did not come back on")
            d.eventually(lambda: not (cover & set(d.windows(dark))), 5,
                         f"{option} on: AYN's cover {cover} stayed")
            time.sleep(1.0)
    finally:
        if d.setting("dual_screen_display_mode") != "0":
            d.root("settings put system dual_screen_display_mode 0")


@test("ayn", "Controller style switches Standard, Xbox and Ban, and hotkeys keep working")
def handle(d):
    ensure_helper(d)
    media_session(d)
    original = next((k for k, v in HANDLES.items() if v == _handle(d)), None)
    d.bind("face_x", "@media_next")
    try:
        for option in ("standard", "xbox", "ban"):
            d.bind("l1", "@handle", ayn_option=option)
            combo(d, pad.L1)
            d.eventually(lambda: _handle(d) == HANDLES[option], 5,
                         f"{option}: expected {HANDLES[option]}, got {_handle(d)}")
            if option != "ban":
                # The helper follows the new pad; printed X must still be X.
                d.eventually(d.helper_pid, 10, "the helper did not survive the style switch")
                # AYN destroys and recreates its pad; a press before its pad and the helper's clone are
                # both back can reach apps twice.
                d.eventually(lambda: _pads_settled(d), 10, "the pad and the helper's clone did not come back")
                time.sleep(2.0)
                expect_media(d, lambda: d.press(Seq().combo(d.face("x"))), ["MEDIA_NEXT"])
    finally:
        if original:
            d.bind("l1", "@handle", ayn_option=original)
            banned = _handle(d) == HANDLES["ban"]
            try:
                if banned:
                    # Ban on Use exposes no pad at all, so there is nothing to press the hotkey on.
                    raise Fail("banned")
                combo(d, pad.L1)
                d.eventually(lambda: _handle(d) == HANDLES[original], 5, "")
            except Fail:
                flip, layout, no_layout, kind = HANDLES[original]
                d.root(f"settings put system flip_button_layout {flip} && settings put system temp_abxy_layout_mode {layout} "
                       f"&& settings put system no_create_gamepad_button_layout {no_layout} && setprop persist.sys.gamepad.type {kind}")
                if not banned:
                    d.warnings.append(f"the hotkey could not switch back to {original}; restored as root")
            time.sleep(2.0)
