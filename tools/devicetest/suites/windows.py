"""Window actions: launching apps, Home, Back, both Recents, swapping and the controller route.

Two stock apps stand in for games: Settings and Gallery. Neither holds anything of the owner's
that a force-stop could lose."""

import re
import time

from dt import pad
from dt.device import Fail
from dt.pad import Seq
from dt.runner import test

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
        d.press(Seq().tap(d.face("b")))
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
        # The switcher listens for what Android calls BUTTON_X: the left face button, evdev 307 in both styles.
        d.press(Seq().tap(pad.X))
        d.eventually(lambda: not d.pid("com.android.gallery3d"), 6, "X left Gallery running")
    finally:
        if d.own_windows(d.bottom):
            d.press(Seq().tap(pad.BACK))
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
        d.press(Seq().tap(d.face("b")))
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
        tasks = re.findall(r"Task\{\S+ #(\d+) [^}]*?A=\d+:com\.android\.gallery3d", d.sh("dumpsys activity recents"))
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
        if d.pid("com.android.gallery3d"):
            raise Fail("the closed last app was started again")
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
