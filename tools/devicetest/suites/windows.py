"""Window actions: launching apps, Home, Back, both Recents, swapping and the controller route.

Two stock apps stand in for games: Settings and Gallery. Neither holds anything of the owner's
that a force-stop could lose."""

import re
import time

from dt import pad
from dt.device import Fail
from dt.pad import Seq
from dt.runner import test
from .common import expect_closed

SETTINGS = "com.android.settings/.Settings"
GALLERY = "com.android.gallery3d/.app.GalleryActivity"
LAUNCHERS = ("com.android.launcher3", "com.odin")

KEY = "face_y"


def _press(d):
    d.press(Seq().combo(d.face("y")))


def _clean(d):
    for comp in (SETTINGS, GALLERY):
        d.stop_app(comp.split("/")[0])


def _front(d, display, pkg, timeout=6):
    d.eventually(lambda: d.resumed().get(display, "").startswith(pkg), timeout,
                 f"expected {pkg} in front on display {display}, saw {d.resumed().get(display)}")


@test("windows", "Back + button launches an app on the top or the bottom screen")
def launch(d):
    _clean(d)
    try:
        for where, display in (("top", d.top), ("bottom", d.bottom)):
            d.bind(KEY, "com.android.settings", display=where)
            _press(d)
            try:
                _front(d, display, "com.android.settings")
            except Fail as e:
                raise Fail(f"launch on {where}: {e}")
            _clean(d)
            time.sleep(0.8)
    finally:
        _clean(d)


@test("windows", "Home on the top and on the bottom screen")
def home(d):
    _clean(d)
    try:
        for where, display in (("top", d.top), ("bottom", d.bottom)):
            d.start_app(SETTINGS, display)
            d.bind(KEY, "@home", display=where)
            _press(d)
            try:
                d.eventually(lambda: d.resumed().get(display, "").startswith(LAUNCHERS), 5,
                             f"Home on {where} left {d.resumed().get(display)} in front")
            finally:
                _clean(d)
    finally:
        _clean(d)


@test("windows", "Back as a hotkey reaches the app with controller focus")
def back(d):
    d.bind(KEY, "@back")
    d.probe_start()
    try:
        d.clear_log()
        _press(d)
        d.eventually(lambda: ("KEY", "DOWN", "KEYCODE_BACK") in d.probe_events(), 3,
                     f"the app in front got no Back: {d.probe_events()}")
    finally:
        d.probe_stop()


@test("windows", "The bottom switcher opens on the bottom screen and Back closes it")
def switcher(d):
    d.bind(KEY, "@switcher")
    _press(d)
    try:
        d.eventually(lambda: d.own_windows(d.bottom), 4, "the switcher did not open on the bottom screen")
        # The top screen only shows the focus pictogram, which tells where the controller now acts.
        if len(d.own_windows(d.top)) > 1:
            raise Fail(f"the switcher drew more than the pictogram on the top screen: {d.own_windows(d.top)}")
    finally:
        d.press(Seq().tap(pad.BACK))
    d.eventually(lambda: not d.own_windows(d.bottom) and not d.own_windows(d.top), 4,
                 f"Back left the switcher open: {d.own_windows(d.bottom)} / {d.own_windows(d.top)}")


@test("windows", "Top Recents opens over the top screen's app")
def recents(d):
    _clean(d)
    try:
        d.start_app(SETTINGS, d.top)
        d.bind(KEY, "@recents")
        _press(d)
        d.eventually(lambda: d.resumed().get(d.top, "").startswith(LAUNCHERS), 5,
                     f"Recents did not open on top, front is {d.resumed().get(d.top)}")
        d.keyevent("KEYCODE_HOME", d.top)
    finally:
        _clean(d)


@test("windows", "Swap apps trades the apps of both screens")
def swap(d):
    _clean(d)
    try:
        d.start_app(SETTINGS, d.top)
        d.start_app(GALLERY, d.bottom)
        d.bind(KEY, "@swap_apps")
        _press(d)
        _front(d, d.top, "com.android.gallery3d", 8)
        _front(d, d.bottom, "com.android.settings", 8)
        _press(d)
        _front(d, d.top, "com.android.settings", 8)
        _front(d, d.bottom, "com.android.gallery3d", 8)
    finally:
        _clean(d)


@test("windows", "Hold Back swaps the apps of both screens")
def hold_swap(d):
    _clean(d)
    try:
        d.start_app(SETTINGS, d.top)
        d.start_app(GALLERY, d.bottom)
        d.bind("back_hold", "@swap_apps")
        d.put("hold_ms", 600)
        d.press(Seq().key(pad.BACK, True).wait(900).key(pad.BACK, False))
        _front(d, d.top, "com.android.gallery3d", 8)
        _front(d, d.bottom, "com.android.settings", 8)
    finally:
        _clean(d)


@test("windows", "Controller focus switches the route between the screens and shows its pictogram")
def controller_focus(d):
    d.probe_start()
    try:
        d.bind(KEY, "@controller_focus")
        start = d.setting("screen_focus_lock")
        _press(d)
        d.eventually(lambda: d.setting("screen_focus_lock") != start, 4,
                     f"the route stayed at {start}")
        flipped = d.setting("screen_focus_lock")
        d.wait_toasts_gone(2)
        _press(d)
        d.eventually(lambda: d.setting("screen_focus_lock") == start, 4,
                     f"the second press did not route back (still {flipped})")
    finally:
        d.probe_stop()


@test("windows", "The focus pictogram appears on the top screen while the button is down")
def focus_indicator(d):
    d.probe_start()
    try:
        d.bind("l1", "@controller_focus")
        bottom = []
        seen = d.press_observing(Seq().key(pad.BACK, True).wait(250).key(pad.L1, True).wait(1200)
                                 .key(pad.L1, False).wait(100).key(pad.BACK, False),
                                 lambda: bottom.extend(d.own_windows(d.bottom)) or d.own_windows(d.top))
        if not seen:
            raise Fail("no pictogram appeared on the top screen")
        if bottom:
            raise Fail(f"the pictogram appeared on the bottom screen: {bottom}")
    finally:
        d.probe_stop()
        d.focus(d.top)


def _routes(d):
    """Every way the controller can drive a screen: AYN's fixed route to it, and Auto-lock on it."""
    return [("fixed", d.top), ("fixed", d.bottom), ("auto", d.top), ("auto", d.bottom)]


def _where(d, mode, display):
    return f"{mode} {'top' if display == d.top else 'bottom'}"


def _app_on(d, display):
    """The stand-in app _apps_on_both puts on [display]."""
    return SETTINGS if display == d.top else GALLERY


def _apps_on_both(d):
    _clean(d)
    d.start_app(SETTINGS, d.top)
    d.start_app(GALLERY, d.bottom)


def _drive(d, mode, display, component):
    """Lets the controller drive [display]: AYN's fixed route there, or Auto-lock with Android's input
    focus on it, which bringing [component] to the front there gives. Auto-lock keeps no screen in its
    setting; the app reads the driven screen from Android's input focus, so the test proves that first."""
    if mode == "fixed":
        d.focus(display)
        return
    d.auto_route()
    d.start_app(component, display)
    d.eventually(lambda: d.input_display() == display, 4,
                 f"Auto-lock: Android's input focus stayed on display {d.input_display()}, not {display}")
    # The service learns about apps from window events; give it the moment it needs.
    time.sleep(0.8)


@test("windows", "Controller focus Auto hands the controller to AYN's Auto-lock")
def controller_focus_auto(d):
    d.probe_start()
    try:
        d.bind(KEY, "@controller_focus", focus_target="auto")
        _press(d)
        d.eventually(lambda: d.setting("screen_focus_lock") == "0" and d.sh("getprop persist.sys.input.dispaly") == "0", 4,
                     f"the route is {d.setting('screen_focus_lock')} / {d.sh('getprop persist.sys.input.dispaly')}, not Auto-lock (0)")
    finally:
        d.probe_stop()
        d.focus(d.top)


@test("windows", "Toggle out of Auto-lock locks the screen the controller does not drive")
def controller_focus_toggle_from_auto(d):
    try:
        d.bind(KEY, "@controller_focus", focus_target="toggle")
        for display, other in ((d.top, d.bottom), (d.bottom, d.top)):
            _apps_on_both(d)
            _drive(d, "auto", display, _app_on(d, display))
            d.wait_toasts_gone(2)
            _press(d)
            d.eventually(lambda: d.route() == other, 5,
                         f"toggle from Auto-lock on display {display}: the route is {d.setting('screen_focus_lock')}, not display {other}")
            time.sleep(0.8)
    finally:
        _clean(d)
        d.focus(d.top)


@test("windows", "Home on the focused screen goes Home where the controller is: fixed top, fixed bottom and Auto-lock")
def home_focused(d):
    try:
        d.bind(KEY, "@home", display="focused")
        for mode, display in _routes(d):
            where = _where(d, mode, display)
            other = d.bottom if display == d.top else d.top
            _apps_on_both(d)
            _drive(d, mode, display, _app_on(d, display))
            _press(d)
            d.eventually(lambda: d.resumed().get(display, "").startswith(LAUNCHERS), 5,
                         f"{where}: Home left {d.resumed().get(display)} in front")
            kept = _app_on(d, other).split("/")[0]
            if not d.resumed().get(other, "").startswith(kept):
                raise Fail(f"{where}: Home also changed the other screen: {d.resumed().get(other)}")
    finally:
        _clean(d)
        d.focus(d.top)


@test("windows", "Back + button launches an app on the focused screen: fixed top, fixed bottom and Auto-lock")
def launch_focused(d):
    try:
        d.bind(KEY, "com.android.settings", display="focused")
        for mode, display in _routes(d):
            _clean(d)
            _drive(d, mode, display, GALLERY)
            _press(d)
            try:
                _front(d, display, "com.android.settings")
            except Fail as e:
                raise Fail(f"{_where(d, mode, display)}: {e}")
            time.sleep(0.8)
    finally:
        _clean(d)
        d.focus(d.top)


@test("windows", "App Switcher on the focused screen opens top Recents or the bottom switcher: fixed top, fixed bottom and Auto-lock")
def recents_focused(d):
    try:
        d.bind(KEY, "@recents_focused")
        for mode, display in _routes(d):
            where = _where(d, mode, display)
            _apps_on_both(d)
            _drive(d, mode, display, _app_on(d, display))
            d.wait_toasts_gone(2)
            _press(d)
            if display == d.top:
                d.eventually(lambda: d.resumed().get(d.top, "").startswith(LAUNCHERS), 5,
                             f"{where}: top Recents did not open, front is {d.resumed().get(d.top)}")
                if d.own_windows(d.bottom):
                    raise Fail(f"{where}: the bottom switcher opened as well: {d.own_windows(d.bottom)}")
                d.keyevent("KEYCODE_HOME", d.top)
            else:
                d.eventually(lambda: d.own_windows(d.bottom), 4, f"{where}: the bottom switcher did not open")
                d.press(Seq().tap(pad.BACK))
                d.eventually(lambda: not d.own_windows(d.bottom), 4, f"{where}: Back left the switcher open")
            time.sleep(1.0)
    finally:
        d.keyevent("KEYCODE_HOME", d.top)
        _clean(d)
        d.focus(d.top)


@test("windows", "Switch active app moves the controller to the other screen's app")
def switch_app(d):
    _clean(d)
    try:
        d.start_app(SETTINGS, d.top)
        d.start_app(GALLERY, d.bottom)
        d.focus(d.top)
        d.bind(KEY, "@switch_active_app")
        _press(d)
        d.eventually(lambda: d.route() == d.bottom, 5, "the controller did not move to the bottom app")
        d.wait_toasts_gone(2)
        time.sleep(1.0)
        _press(d)
        d.eventually(lambda: d.route() == d.top, 5, "the controller did not move back to the top app")
    finally:
        _clean(d)


@test("windows", "Navigation flicks go back and, while an app is right of the centre, forward again")
def flick(d):
    """Quickstep commits its order about a second after a switch; only until then does the app you
    came from sit right of the centre. So the pairs are flicked inside one Back hold."""
    files = "com.android.documentsui/.LauncherActivity"
    _clean(d)
    d.stop_app("com.android.documentsui")
    try:
        for comp in (SETTINGS, GALLERY, files):
            d.start_app(comp, d.top)
            time.sleep(0.8)
        d.bind("l1", "@navigation_flick_left")
        d.bind("r1", "@navigation_flick_right")
        time.sleep(0.5)
        d.press(Seq().combo(pad.L1))
        _front(d, d.top, "com.android.gallery3d", 5)
        time.sleep(2.0)
        d.press(Seq().key(pad.BACK, True).wait(250).tap(pad.L1, after=150).tap(pad.R1, after=0).wait(200).key(pad.BACK, False))
        time.sleep(1.5)
        _front(d, d.top, "com.android.gallery3d", 3)
        d.press(Seq().key(pad.BACK, True).wait(250).tap(pad.L1, after=150).tap(pad.L1, after=0).wait(200).key(pad.BACK, False))
        _front(d, d.top, "com.android.settings", 5)
    finally:
        _clean(d)
        d.stop_app("com.android.documentsui")


@test("windows", "The controller opens an app from the bottom switcher")
def switcher_open(d):
    _clean(d)
    try:
        d.start_app(SETTINGS, d.top)
        d.start_app(GALLERY, d.bottom)
        d.keyevent("KEYCODE_HOME", d.bottom)
        d.eventually(lambda: d.resumed().get(d.bottom, "").startswith(LAUNCHERS), 4, "the bottom app stayed in front")
        d.bind(KEY, "@switcher")
        _press(d)
        d.eventually(lambda: d.own_windows(d.bottom), 4, "the switcher did not open")
        time.sleep(0.8)
        d.press(Seq().tap(d.confirm_key()))
        d.eventually(lambda: d.resumed().get(d.bottom, "").startswith(("com.android.gallery3d", "com.android.settings")), 6,
                     f"confirm opened nothing on the bottom screen: {d.resumed().get(d.bottom)}")
        d.eventually(lambda: not d.own_windows(d.bottom), 3, "the switcher stayed open after opening an app")
    finally:
        if d.own_windows(d.bottom):
            d.press(Seq().tap(pad.BACK))
        _clean(d)


@test("windows", "X in the bottom switcher closes the listed apps")
def switcher_close_all(d):
    _clean(d)
    try:
        d.start_app(SETTINGS, d.top)
        d.start_app(GALLERY, d.bottom)
        time.sleep(1.0)
        d.bind(KEY, "@switcher")
        _press(d)
        d.eventually(lambda: d.own_windows(d.bottom), 4, "the switcher did not open")
        time.sleep(0.8)
        gallery = d.pid("com.android.gallery3d")
        # The switcher listens for what Android calls BUTTON_X, evdev 307 in both styles: printed X (top)
        # in Standard Style, printed Y (left) in Xbox Style.
        d.press(Seq().tap(pad.X))
        expect_closed(d, "com.android.gallery3d", gallery)
        d.eventually(lambda: not d.own_windows(d.bottom), 3, "Close all left the switcher open")
    finally:
        if d.own_windows(d.bottom):
            d.press(Seq().tap(pad.BACK))
        _clean(d)


def _dpad(d, direction):
    """While either Recents holds the controller, the D-pad alone navigates; Back would close them."""
    d.press(Seq().hat(direction))


def _quickstep_state(d):
    """Quickstep's launcher state, such as Overview or Normal."""
    m = re.search(r"mState:(\w+)", d.sh("dumpsys activity com.android.launcher3 | grep -m1 mState:"))
    return m.group(1) if m else ""


def _focused_card(d):
    """Index of the task card with input focus in top Recents, 0 for the newest (rightmost), or None."""
    cards = [line for line in d.sh("dumpsys activity com.android.launcher3").splitlines() if "TaskView{" in line]
    return next((i for i, line in enumerate(cards) if " .F" in line), None)


def _select_card(d, index):
    """Moves the focus to card [index] with the D-pad, checking every step. Where Overview starts depends on
    how Quickstep opened it: over an app it may focus that app or already the one before it."""
    d.eventually(lambda: _focused_card(d) is not None, 5, "no card took the focus in top Recents")
    for _ in range(6):
        current = _focused_card(d)
        if current == index:
            return
        _dpad(d, "right" if current is not None and current > index else "left")
        time.sleep(0.5)
    raise Fail(f"the D-pad did not reach card {index}: focus on {_focused_card(d)}")


def _bottom_home(d):
    d.keyevent("KEYCODE_HOME", d.bottom)
    d.eventually(lambda: d.resumed().get(d.bottom, "").startswith(LAUNCHERS), 4, "the bottom app stayed in front")


def _switcher_up(d):
    """Opens the bottom switcher and waits until it takes the controller."""
    _press(d)
    d.eventually(lambda: d.own_windows(d.bottom), 4, "the switcher did not open")
    time.sleep(0.8)


@test("windows", "The D-pad moves the bottom switcher's selection and Up closes only the selected app")
def switcher_select_close(d):
    """The selection starts on the last card; the two test apps are the two most recent on the bottom
    screen, so one step left reaches the other one. Closing a card keeps the selection on the survivor."""
    apps = ("com.android.settings", "com.android.gallery3d")
    _clean(d)
    try:
        d.start_app(SETTINGS, d.bottom)
        time.sleep(0.8)
        d.start_app(GALLERY, d.bottom)
        time.sleep(0.8)
        d.focus(d.top)
        d.bind(KEY, "@switcher")
        opened = []
        for steps in (0, 1):
            _bottom_home(d)
            _switcher_up(d)
            for _ in range(steps):
                _dpad(d, "left")
            d.press(Seq().tap(d.confirm_key()))
            d.eventually(lambda: d.resumed().get(d.bottom, "").startswith(apps), 6,
                         f"confirm opened nothing on the bottom screen: {d.resumed().get(d.bottom)}")
            opened.append(next(a for a in apps if d.resumed().get(d.bottom, "").startswith(a)))
            d.eventually(lambda: not d.own_windows(d.bottom), 3, "the switcher stayed open after opening an app")
        if opened[0] == opened[1]:
            raise Fail(f"Left did not move the selection: both confirms opened {opened[0]}")
        # The app opened last is now the most recent card, where the selection starts.
        selected, other = opened[1], opened[0]
        _bottom_home(d)
        _switcher_up(d)
        selected_pid, other_pid = d.pid(selected), d.pid(other)
        _dpad(d, "up")
        expect_closed(d, selected, selected_pid)
        if d.pid(other) != other_pid:
            raise Fail(f"Up also closed {other}")
        if not d.own_windows(d.bottom):
            raise Fail("closing one card closed the switcher")
        d.press(Seq().tap(d.confirm_key()))
        d.eventually(lambda: d.resumed().get(d.bottom, "").startswith(other), 6,
                     f"after the close, confirm did not open {other}: {d.resumed().get(d.bottom)}")
    finally:
        if d.own_windows(d.bottom):
            d.press(Seq().tap(pad.BACK))
        _clean(d)


@test("windows", "The D-pad moves through top Recents, Up removes one task and Back closes Overview")
def recents_navigate(d):
    """Settings is the newest card and Gallery the one left of it; the D-pad walks there from wherever Overview starts."""
    _clean(d)
    try:
        d.start_app(GALLERY, d.top)
        time.sleep(0.8)
        d.start_app(SETTINGS, d.top)
        d.focus(d.top)
        d.bind(KEY, "@recents")
        _press(d)
        d.eventually(lambda: d.resumed().get(d.top, "").startswith(LAUNCHERS), 5, "Recents did not open")
        time.sleep(1.5)
        # Settings is the newest card, Gallery the one left of it.
        _select_card(d, 0)
        _dpad(d, "left")
        d.eventually(lambda: _focused_card(d) == 1, 3, f"Left did not move to the previous card: {_focused_card(d)}")
        d.press(Seq().tap(d.confirm_key()))
        _front(d, d.top, "com.android.gallery3d", 6)

        _press(d)
        d.eventually(lambda: d.resumed().get(d.top, "").startswith(LAUNCHERS), 5, "Recents did not reopen")
        time.sleep(1.5)
        if not d.task_ids("com.android.gallery3d"):
            raise Fail("Gallery had no task before the dismissal")
        _select_card(d, 0)
        _dpad(d, "up")
        d.eventually(lambda: not d.task_ids("com.android.gallery3d"), 5,
                     f"Up left Gallery's task: {d.task_ids('com.android.gallery3d')}")
        # Settings' task affinity is its package plus ".root".
        if not d.task_ids("com.android.settings.root"):
            raise Fail("Up also removed Settings' task")
        d.press(Seq().tap(pad.BACK))
        # Back closes Overview itself, not only the controller session (with the app it was opened over gone,
        # Quickstep goes Home).
        d.eventually(lambda: _quickstep_state(d) not in ("Overview", ""), 6,
                     f"Back left Overview open: {_quickstep_state(d)}")
        d.eventually(lambda: not d.own_windows(d.top), 5, f"Back left the Recents session open: {d.own_windows(d.top)}")
        if d.route() != d.top:
            raise Fail(f"the controller left the top screen: {d.route()}")
    finally:
        d.keyevent("KEYCODE_HOME", d.top)
        _clean(d)


@test("windows", "The controller opens an app from top Recents on the top screen")
def recents_open(d):
    _clean(d)
    try:
        d.start_app(GALLERY, d.top)
        d.start_app(SETTINGS, d.top)
        d.focus(d.top)
        d.bind(KEY, "@recents")
        _press(d)
        d.eventually(lambda: d.resumed().get(d.top, "").startswith(LAUNCHERS), 5, "Recents did not open")
        time.sleep(1.5)
        d.press(Seq().tap(d.confirm_key()))
        d.eventually(lambda: d.resumed().get(d.top, "").startswith(("com.android.gallery3d", "com.android.settings")), 6,
                     f"confirm opened nothing on top: {d.resumed().get(d.top)}")
        if d.resumed().get(d.bottom, "").startswith(("com.android.gallery3d", "com.android.settings")):
            raise Fail("the app opened on the bottom screen instead")
    finally:
        _clean(d)


@test("windows", "Switch to last app returns to the previous app on the active screen and flips back and forth")
def last_app(d):
    _clean(d)
    try:
        d.start_app(SETTINGS, d.top)
        d.start_app(GALLERY, d.top)
        d.focus(d.top)
        time.sleep(1.0)
        d.bind(KEY, "@last_app")
        for want in ("com.android.settings", "com.android.gallery3d", "com.android.settings"):
            _press(d)
            _front(d, d.top, want, 6)
            time.sleep(1.0)
        # Gallery, now the last app, is closed and its task removed: the hotkey must say so instead of
        # starting it anew.
        tasks = d.task_ids("com.android.gallery3d")
        d.stop_app("com.android.gallery3d")
        for task in tasks:
            d.sh(f"am stack remove {task}")
        time.sleep(1.0)
        d.mark()
        _press(d)
        # Gallery's label follows the device language; match the message around it.
        before, after = d.text("last_app_gone", "\0").split("\0")
        d.eventually(lambda: any(n.startswith(before) and n.endswith(after) for n in d.notices()) and d.notice_visible(),
                     4, f"no 'no longer running' notice, saw {d.notices()}")
        if d.task_ids("com.android.gallery3d") or any(front.startswith("com.android.gallery3d/") for front in d.resumed().values()):
            raise Fail("the closed last app acquired a task or foreground activity again")
    finally:
        _clean(d)


@test("windows", "Switch to last app keeps each screen's pair and never pulls an app from the other screen")
def last_app_per_screen(d):
    _clean(d)
    calendar, clock = "com.android.calendar/.AllInOneActivity", "com.android.deskclock/.DeskClock"
    try:
        d.start_app(SETTINGS, d.top)
        d.start_app(GALLERY, d.top)
        d.start_app(calendar, d.bottom)
        d.start_app(clock, d.bottom)
        # Clock leaves the bottom screen: it now belongs to the top pair (clock, gallery), and the
        # bottom screen keeps only calendar.
        d.start_app(clock, d.top)
        _front(d, d.bottom, "com.android.calendar")
        d.bind(KEY, "@last_app")
        for display, want, other, other_app in ((d.top, "com.android.gallery3d", d.bottom, "com.android.calendar"),
                                                (d.top, "com.android.deskclock", d.bottom, "com.android.calendar")):
            d.focus(display)
            _press(d)
            _front(d, display, want, 6)
            if not d.resumed().get(other, "").startswith(other_app):
                raise Fail(f"the other screen changed to {d.resumed().get(other)}")
            time.sleep(1.0)
        d.focus(d.bottom)
        d.mark()
        _press(d)
        d.eventually(lambda: d.text("last_app_none") in d.notices(), 4, f"no 'no previous app' notice, saw {d.notices()}")
        time.sleep(0.5)
        if not d.resumed().get(d.top, "").startswith("com.android.deskclock"):
            raise Fail(f"the bottom hotkey pulled {d.resumed().get(d.bottom)} away from the top screen")
    finally:
        for comp in (calendar, clock):
            d.stop_app(comp.split("/")[0])
        _clean(d)


@test("windows", "Switch to last app in Auto-lock works on the screen Android's input focus is on")
def last_app_auto(d):
    _clean(d)
    calendar, clock = "com.android.calendar/.AllInOneActivity", "com.android.deskclock/.DeskClock"
    try:
        d.start_app(calendar, d.top)
        d.start_app(SETTINGS, d.top)
        d.start_app(clock, d.bottom)
        d.start_app(GALLERY, d.bottom)
        d.bind(KEY, "@last_app")
        for display, front, want, other in ((d.top, SETTINGS, "com.android.calendar", d.bottom),
                                            (d.bottom, GALLERY, "com.android.deskclock", d.top)):
            _drive(d, "auto", display, front)
            kept = d.resumed().get(other, "")
            _press(d)
            try:
                _front(d, display, want, 6)
            except Fail as e:
                raise Fail(f"{_where(d, 'auto', display)}: {e}")
            if d.resumed().get(other, "") != kept:
                raise Fail(f"{_where(d, 'auto', display)}: the other screen changed to {d.resumed().get(other)}")
            time.sleep(1.0)
    finally:
        for comp in (calendar, clock):
            d.stop_app(comp.split("/")[0])
        _clean(d)
        d.focus(d.top)


@test("windows", "Switch to last app flips fast between each screen's last two apps, also after a swap")
def last_app_fast(d):
    calendar, clock = "com.android.calendar/.AllInOneActivity", "com.android.deskclock/.DeskClock"
    music = "com.android.music/.MusicBrowserActivity"
    extra = [c.split("/")[0] for c in (calendar, clock, music)]

    def clean():
        _clean(d)
        for pkg in extra:
            d.stop_app(pkg)

    def burst(display, presses):
        """Presses back to back in one script; every press must pick the other app of the pair."""
        start = d.resumed().get(display, "").split("/")[0]
        since = d.mark()
        seq = Seq()
        for _ in range(presses):
            seq.combo(d.face("y")).wait(100)
        d.press(seq)
        time.sleep(2.0)
        log = d.sh(f"logcat -d -v brief -T '{since}' BackButtonHero:D *:S")
        targets = re.findall(r"Last app (\S+) on \d+: OK task=\d+ from=(-?\d+) to=(\d+)", log)
        picked = [t[0] for t in targets]
        if len(picked) != presses or any(a == b for a, b in zip(picked, picked[1:])):
            raise Fail(f"{presses} fast presses on display {display} did not flip, launched {picked}")
        if any(src != dst for _, src, dst in targets):
            raise Fail(f"a fast press on display {display} pulled an app from the other screen: {targets}")
        _front(d, display, start, 4)

    clean()
    try:
        d.bind(KEY, "@last_app")
        # Four apps per screen: only the last two of each take part.
        for display in (d.top, d.bottom):
            for comp in (SETTINGS, GALLERY, calendar, clock):
                d.start_app(comp, display)
            d.focus(display)
            time.sleep(1.0)
            burst(display, 4)
            # A new app takes over the pair at once.
            d.start_app(music, display)
            time.sleep(0.5)
            burst(display, 2)
            clean()
            time.sleep(0.8)
        # After a swap each screen flips with its own apps; nothing travels between the screens.
        d.start_app(SETTINGS, d.top)
        d.start_app(GALLERY, d.top)
        d.start_app(calendar, d.bottom)
        d.start_app(clock, d.bottom)
        d.bind("face_x", "@swap_apps")
        d.focus(d.top)
        d.press(Seq().combo(d.face("x")))
        _front(d, d.top, "com.android.deskclock")
        _front(d, d.bottom, "com.android.gallery3d")
        time.sleep(1.0)
        for display in (d.top, d.bottom, d.top, d.bottom):
            d.focus(display)
            time.sleep(0.5)
            burst(display, 2)
    finally:
        d.unbind("face_x")
        clean()


@test("windows", "Launch app (ask) opens the app list and Cancel leaves everything; favorites without stars only explain")
def choose_app(d):
    _clean(d)
    try:
        d.start_app(SETTINGS, d.top)
        d.focus(d.top)
        time.sleep(1.0)
        before = d.resumed()
        d.bind(KEY, "@app_favorites", display="top")
        _press(d)
        d.eventually(lambda: d.text("app_favorites_none") in d.notices(), 4,
                     f"no 'no favorites' notice, saw {d.notices()}")
        if d.own_windows(d.route() or d.top):
            raise Fail("favorites without stars opened a list")
        d.bind(KEY, "@app_ask", display="top")
        _press(d)
        d.eventually(lambda: d.own_windows(d.route() or d.top), 4, "no app list appeared")
        # The prompt's cancel is whichever button reports BUTTON_B.
        d.press(Seq().tap(d.cancel_key()))
        d.eventually(lambda: not d.own_windows(d.route() or d.top), 3, "Cancel left the app list open")
        time.sleep(0.8)
        if d.resumed() != before:
            raise Fail(f"cancelling the app list changed the screens: {before} -> {d.resumed()}")
    finally:
        _clean(d)


def _pause_pair(d):
    d.put("toggle_buttons", "l1_r1")
    d.put("toggle_hold_ms", 300)
    return Seq().key(pad.L1, True).key(pad.R1, True).wait(600).key(pad.L1, False).key(pad.R1, False).wait(300)


def _resume(d, pair):
    """The second pair must switch the hotkeys back on; a lost resume would leave every later area
    running against a paused service, where Back alone reaches the app and passes for a minimize."""
    d.press(pair)
    d.expect_toast(d.text("service_toggle_active", "L1 + R1"))


def _open_bottom_switcher(d):
    d.start_app(SETTINGS, d.top)
    d.start_app(GALLERY, d.bottom)
    d.focus(d.top)
    d.bind(KEY, "@switcher")
    _press(d)
    d.eventually(lambda: d.own_windows(d.bottom), 4, "the switcher did not open")
    d.eventually(lambda: d.route() == d.bottom, 4, f"the controller did not move to the switcher: route {d.route()}")


@test("windows", "Pausing closes the open bottom switcher and returns the controller to its screen")
def switcher_pause(d):
    _clean(d)
    pair = _pause_pair(d)
    try:
        _open_bottom_switcher(d)
        d.press(pair)
        d.eventually(lambda: not d.own_windows(d.bottom) and not d.own_windows(d.top), 4,
                     f"pausing left windows open: bottom {d.own_windows(d.bottom)}, top {d.own_windows(d.top)}")
        d.eventually(lambda: d.route() == d.top, 4, f"the controller stayed on {d.route()}")
        _resume(d, pair)
    finally:
        if d.own_windows(d.bottom):
            d.press(Seq().tap(pad.BACK))
        _clean(d)


@test("windows", "Standby leaves the open bottom switcher open with the controller on it; Back then closes it")
def switcher_standby(d):
    _clean(d)
    try:
        _open_bottom_switcher(d)
        d.sh("input keyevent KEYCODE_SLEEP")
        d.eventually(lambda: not d.awake(), 5, "the device did not go to standby")
        d.keyevent("KEYCODE_WAKEUP")
        d.eventually(d.awake, 5, "the device did not wake up")
        d.sh("wm dismiss-keyguard")
        time.sleep(1.5)
        d.reconnect()
        if not d.own_windows(d.bottom) or d.route() != d.bottom:
            raise Fail(f"after standby: switcher {d.own_windows(d.bottom)}, route {d.route()}")
        d.press(Seq().tap(pad.BACK))
        d.eventually(lambda: not d.own_windows(d.bottom), 4, "Back did not close the switcher after standby")
        d.eventually(lambda: d.route() == d.top, 4, f"the controller stayed on {d.route()}")
    finally:
        if d.own_windows(d.bottom):
            d.press(Seq().tap(pad.BACK))
        _clean(d)


@test("windows", "A service restart closes the open bottom switcher and the controller is on the top screen")
def switcher_restart(d):
    _clean(d)
    try:
        _open_bottom_switcher(d)
        d.restart_service()
        d.eventually(lambda: not d.own_windows(d.bottom) and not d.own_windows(d.top), 6,
                     f"windows survived the restart: bottom {d.own_windows(d.bottom)}, top {d.own_windows(d.top)}")
        d.eventually(lambda: d.route() == d.top, 4, f"the controller stayed on {d.route()}")
    finally:
        if d.own_windows(d.bottom):
            d.press(Seq().tap(pad.BACK))
        _clean(d)


@test("windows", "Pausing in top Recents ends its controller session; Overview itself stays")
def recents_pause(d):
    _clean(d)
    pair = _pause_pair(d)
    try:
        d.start_app(SETTINGS, d.top)
        d.focus(d.top)
        d.bind(KEY, "@recents")
        _press(d)
        d.eventually(lambda: d.resumed().get(d.top, "").startswith(LAUNCHERS), 5, "Recents did not open")
        d.eventually(lambda: d.own_windows(d.top), 4, "top Recents showed no controller pictogram")
        d.press(pair)
        d.eventually(lambda: not d.own_windows(d.top), 4, f"pausing left {d.own_windows(d.top)} on top")
        if not d.resumed().get(d.top, "").startswith(LAUNCHERS):
            raise Fail(f"pausing closed Overview: {d.resumed().get(d.top)}")
        if d.route() != d.top:
            raise Fail(f"the controller left the top screen: {d.route()}")
        _resume(d, pair)
    finally:
        d.keyevent("KEYCODE_HOME", d.top)
        _clean(d)
