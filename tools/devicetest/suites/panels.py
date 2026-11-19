"""System panels and pages: quick settings, notifications, the ten settings shortcuts, the power
menu and standby."""

import time

from dt import pad
from dt.device import Fail
from dt.pad import Seq
from dt.runner import test

from .common import ensure_helper

KEY = "face_y"
PAGES = ["@internet", "@bluetooth", "@night_light", "@extra_dim", "@display_settings", "@sound_settings",
         "@location_settings", "@cast_settings", "@dnd_settings", "@battery_saver"]


def _press(d):
    d.press(Seq().combo(d.face("y")))


def _shade_open(d):
    return "NotificationShade" in d.sh("dumpsys window | grep mCurrentFocus")


def _page_open(d):
    """A settings page in front, or the system's own panel dialog (Android 14 shows Internet as one)."""
    page = next((name for name in d.resumed().values() if name.startswith("com.android.settings/")), None)
    if page:
        return page
    return "SystemUI dialog" if "Dialog}" in d.sh("dumpsys window | grep mCurrentFocus") else None


@test("panels", "Quick settings and notifications open the shade")
def shade(d):
    for action in ("@quick_settings", "@notifications"):
        d.bind(KEY, action)
        try:
            _press(d)
            d.eventually(lambda: _shade_open(d), 4, f"{action} did not open the shade")
        finally:
            d.sh("cmd statusbar collapse")
            time.sleep(0.8)


@test("panels", "Every settings shortcut opens its settings page")
def pages(d):
    d.stop_app("com.android.settings")
    opened = {}
    try:
        for action in PAGES:
            d.bind(KEY, action)
            _press(d)
            try:
                opened[action] = d.eventually(lambda: _page_open(d), 5, f"{action} opened no settings page")
            finally:
                if opened.get(action) == "SystemUI dialog":
                    d.keyevent("KEYCODE_BACK")
                d.stop_app("com.android.settings")
                time.sleep(0.8)
    finally:
        d.stop_app("com.android.settings")
    # Each shortcut must reach a page of its own, not the same fallback for all of them.
    if len(set(opened.values())) < 3:
        raise Fail(f"the shortcuts all opened the same page: {opened}")


@test("panels", "The power menu opens on the top screen and Back closes it")
def power_menu(d):
    d.bind(KEY, "@power_menu")
    before = set(d.windows(d.top))
    _press(d)
    try:
        d.eventually(lambda: set(d.windows(d.top)) - before, 4, "no power menu window appeared")
    finally:
        d.keyevent("KEYCODE_BACK", d.top)
    d.eventually(lambda: not (set(d.windows(d.top)) - before), 4,
                 f"Back left the power menu open: {set(d.windows(d.top)) - before}")


@test("panels", "Standby switches the screens off; with the prompt it asks first")
def standby(d):
    d.bind(KEY, "@standby", standby_confirm=False)
    try:
        _press(d)
        d.eventually(lambda: not d.awake(), 5, "standby did not switch the screens off")
    finally:
        _wake(d)
    d.bind(KEY, "@standby", standby_confirm=True)
    ensure_helper(d)
    time.sleep(1.0)
    _press(d)
    # The first press after waking uses the same button that sent the device to standby: it must count.
    d.eventually(lambda: d.own_windows(d.top) or d.own_windows(d.bottom), 4,
                 "the first Back + Y after waking from standby was lost (the button that locked is still counted as held)")
    # Cancel is the second entry.
    d.press(Seq().hat("down").tap(d.face("b")))
    time.sleep(1.0)
    if not d.awake():
        _wake(d)
        raise Fail("Cancel still switched the screens off")


def _wake(d):
    d.keyevent("KEYCODE_WAKEUP")
    d.eventually(d.awake, 5, "the device did not wake up")
    d.sh("wm dismiss-keyguard")
    time.sleep(1.0)
    d.reconnect()
