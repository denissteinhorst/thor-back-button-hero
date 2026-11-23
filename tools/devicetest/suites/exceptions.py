"""Per-app exceptions, Home as Back, and the service surviving a restart."""

import re
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


@test("service", "When the app process dies, the pad is handed back and everything comes back with the service")
def process_death(d):
    """A crash or a low-memory kill: the helper sees its socket close and returns the pad to Android;
    Android restarts the service, which starts a new helper and a new clone."""
    d.bind("face_x", "@media_next")
    ensure_helper(d)
    media_session(d)
    app_pid, helper_pid, held = d.pid(PKG), d.helper_pid(), _pad_nodes(d)
    d.root(f"kill -9 {app_pid}")
    d.eventually(lambda: d.helper_pid() != helper_pid, 10, "the helper outlived the app process")
    d.eventually(lambda: d.pid(PKG) and d.pid(PKG) != app_pid, 20, "the app process did not come back")
    d.reconnect()
    ensure_helper(d)
    if _pad_nodes(d) != held:
        raise Fail(f"{held} pads before the kill, {_pad_nodes(d)} after the restart")
    media_session(d)
    time.sleep(1.0)
    expect_media(d, lambda: d.press(Seq().combo(d.face("x"))), ["MEDIA_NEXT"])


def _pad_nodes(d):
    """Input devices named like AYN's pad: the real one, plus the helper's clone while it holds the pad."""
    return d.sh("grep -c '^N: Name=\"Xbox Wireless Controller\"' /proc/bus/input/devices") or "0"


@test("service", "Home + Back held 5 s hands the pad back; Back + D-pad then works through the overlay")
def emergency_release(d):
    """The inactive-helper fallback (docs/HELPER.md), reached the way a user reaches it. It is not the
    no-pservice path: root stays available, only the helper has stepped aside until the restart."""
    d.put("home_as_back", False)
    d.bind("dpad_down", "@media_next")
    ensure_helper(d)
    media_session(d)
    expect_media(d, lambda: d.press(Seq().with_back(lambda s: s.hat("down"))), ["MEDIA_NEXT"])
    held = _pad_nodes(d)
    try:
        # The helper judges the 5 s only when a pad event arrives; a real pad's sticks deliver them
        # constantly, injected keys do not. Stick noise of one unit, far below any deadzone, stands in.
        hold = Seq().key(pad.BACK, True).wait(100).key(pad.HOME, True)
        for i in range(28):
            hold.wait(200).abs(pad.ABS_X, 0 if i % 2 else 1)
        d.press(hold.key(pad.HOME, False).key(pad.BACK, False).wait(500))
        d.eventually(lambda: not d.helper_pid(), 6, "the helper kept running after Home + Back for 5 s")
        _release_keys(d)
        d.eventually(lambda: int(_pad_nodes(d)) == int(held) - 1, 6,
                     f"the clone did not go away: {held} pads before, {_pad_nodes(d)} now")
        time.sleep(1.0)
        if d.helper_pid():
            raise Fail("the helper was started again before the service restarted")
        # The native pad reaches the game directly again. Its own key layout names the face buttons,
        # which need not match the clone's, so any face button press counts as arrived.
        d.probe_start()
        try:
            d.clear_log()
            d.press(Seq().tap(d.face("a"), after=400))
            face = {("KEY", "DOWN", f"KEYCODE_BUTTON_{x}") for x in "ABXY"}
            d.eventually(lambda: face & set(d.probe_events()), 3,
                         f"no face button reached the game from the native pad: {d.probe_events()}")
        finally:
            d.probe_stop()
        time.sleep(0.5)
        expect_media(d, lambda: d.press(Seq().with_back(lambda s: s.hat("down"), lead=400)), ["MEDIA_NEXT"])
    finally:
        _release_keys(d)
        d.restart_service()
    d.eventually(d.helper_pid, 12, "the helper did not come back after the service restarted")


def _release_keys(d):
    """The helper exits mid-hold, so the hold's own key-ups went to its closed fd. Without them the
    kernel keeps Back and Home down on the native pad and drops the next press of either."""
    if not d.helper_pid():
        d.press(Seq().key(pad.HOME, False).key(pad.BACK, False).wait(200))


HOLD_BIT = 1 << 3
GALLERY_PKG = "com.android.gallery3d"


def _task_displays(d, pkg):
    """The displays holding a task of [pkg]."""
    displays, current = set(), None
    for line in d.sh("dumpsys activity activities").splitlines():
        m = re.match(r"Display #(\d+)", line)
        if m:
            current = int(m.group(1))
        elif current is not None and re.search(rf"\* Task\{{.*A=\d+:{re.escape(pkg)}\b", line):
            displays.add(current)
    return displays


@test("exceptions", "Hold-to-swap leaves an app whose Hold is unchecked in place and moves only the other one")
def swap_exception(d):
    from .windows import GALLERY
    d.bind("back_hold", "@swap_apps")
    d.put("hold_ms", 600)
    hold = Seq().key(pad.BACK, True).wait(900).key(pad.BACK, False)
    try:
        for pkg in (SETTINGS_PKG, GALLERY_PKG):
            d.stop_app(pkg)
        d.start_app(SETTINGS, d.top)
        d.start_app(GALLERY, d.bottom)
        # The focused app's exception would hand Hold to it natively; the bottom app has none.
        d.focus(d.bottom)
        d.put("app_exceptions", f"{SETTINGS_PKG}\t{ALL & ~HOLD_BIT}\t1\tSettings")
        time.sleep(0.5)
        d.press(hold)
        d.eventually(lambda: d.resumed().get(d.top, "").startswith(GALLERY_PKG + "/"), 8,
                     f"the other app did not move up: {d.resumed()}")
        if _task_displays(d, SETTINGS_PKG) != {d.top}:
            raise Fail(f"the excepted app moved: its tasks are on {_task_displays(d, SETTINGS_PKG)}")
        if _task_displays(d, GALLERY_PKG) != {d.top}:
            raise Fail(f"the moved app left a task behind: {_task_displays(d, GALLERY_PKG)}")
    finally:
        d.put("app_exceptions", "")
        for pkg in (SETTINGS_PKG, GALLERY_PKG):
            d.stop_app(pkg)
        # Whatever the move revealed below goes back behind the launchers.
        d.keyevent("KEYCODE_HOME", d.top)
        d.sh(f"am start --display {d.bottom} -a android.intent.action.MAIN -c android.intent.category.SECONDARY_HOME")
