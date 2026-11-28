"""A third-party frontend's full-screen bottom panel: NeoStation draws a Presentation there. It is
an owner-installed app; these checks start it and stop it again, and bring the launchers back to
the front. Cocoon has a swap of its own on X: its check proves that Hold presses it and leaves Cocoon's
tasks alone. Whether Cocoon opens its bottom activity at all depends on its own task history, so that
check skips when it does not.

IISU, also owner-installed, lays out both screens itself: its checks prove that Hold hands the swap to
IISU's own screen swap and that a swap IISU takes back is refused from then on. A test that toggles IISU's
swap toggles it back, so its home screen ends where it was."""

import re
import time

from dt import pad
from dt.device import Fail
from dt.pad import Seq
from dt.runner import Skip, test

from .common import IISU, NEO, end_frontend_panel, frontend_panel, helper_settled, only_frontend

KEY = "face_y"
HIDDEN, SHOWN = "0x8", "0x0"


def _launchers(d):
    d.keyevent("KEYCODE_HOME", d.top)
    d.sh(f"am start --display {d.bottom} -a android.intent.action.MAIN -c android.intent.category.SECONDARY_HOME")
    time.sleep(1.0)


_panel = frontend_panel


def _expect_panel(d, state, timeout, what):
    d.eventually(lambda: _panel(d) == state, timeout, f"{what}: the panel's visibility is {_panel(d)}, expected {state}")


def _start_neo(d):
    only_frontend(d, NEO)
    d.stop_app(NEO)
    d.sh(f"monkey -p {NEO} -c android.intent.category.LAUNCHER 1")
    d.eventually(lambda: d.resumed().get(d.top, "").startswith(NEO + "/"), 10, "NeoStation did not open on top")
    _expect_panel(d, SHOWN, 10, "NeoStation started")


def _hide_by_home(d):
    d.bind(KEY, "@home", display="bottom")
    # After a service restart, a pad change or a fresh start of the frontend the helper may still swap the
    # pad, and a press made meanwhile is lost.
    helper_settled(d)
    d.press(Seq().combo(d.face("y")))
    d.eventually(lambda: d.resumed().get(d.bottom, "").startswith("com.android.launcher3"), 6,
                 f"Home did not open on the bottom screen: {d.resumed().get(d.bottom)}")
    _expect_panel(d, HIDDEN, 6, "Home on the bottom screen")


def _open_switcher(d):
    d.bind(KEY, "@switcher")
    helper_settled(d)
    d.press(Seq().combo(d.face("y")))
    d.eventually(lambda: d.own_windows(d.bottom), 4, "the switcher did not open")
    time.sleep(0.8)


def _end_neo(d, last=False):
    if d.own_windows(d.bottom):
        d.press(Seq().tap(pad.BACK))
    _launchers(d)
    d.stop_app(NEO)
    # The force-stop leaves the panel while NeoStation's accessibility service is enabled; later areas'
    # bottom switchers would list it. Only the area's last test ends it: every kill lengthens Android's
    # restart back-off of that service, and the next test's force-stop restarts NeoStation anyway.
    if last:
        end_frontend_panel(d)


@test("frontends", "A bottom panel hides for a bottom launch and comes back from its own switcher card")
def panel_card(d):
    try:
        _start_neo(d)
        _hide_by_home(d)
        if not d.resumed().get(d.top, "").startswith(NEO + "/"):
            raise Fail(f"the frontend left the top screen: {d.resumed().get(d.top)}")
        _open_switcher(d)
        if _panel(d) != HIDDEN:
            raise Fail("opening the switcher showed the panel again")
        d.press(Seq().tap(d.confirm_key()))
        _expect_panel(d, SHOWN, 6, "confirming the panel card")
        d.eventually(lambda: not d.own_windows(d.bottom), 3, "the switcher stayed open")
        if not d.resumed().get(d.top, "").startswith(NEO + "/"):
            raise Fail(f"restoring the panel moved the frontend: {d.resumed().get(d.top)}")
    finally:
        _end_neo(d)


@test("frontends", "Cancelling the switcher never hides a visible panel")
def panel_cancel(d):
    try:
        _start_neo(d)
        _open_switcher(d)
        d.press(Seq().tap(pad.BACK))
        d.eventually(lambda: not d.own_windows(d.bottom), 3, "Back left the switcher open")
        _expect_panel(d, SHOWN, 3, "cancelling the switcher")
    finally:
        _end_neo(d)


@test("frontends", "A hidden panel comes back when the service restarts or its root host dies")
def panel_restored(d):
    try:
        _start_neo(d)
        _hide_by_home(d)
        d.restart_service()
        _expect_panel(d, SHOWN, 8, "service restart")
        _hide_by_home(d)
        host = d.root("ps -A -o PID,ARGS | grep PanelWindowHost | grep -v grep").split()
        if not host:
            raise Fail("no root panel host is running while a panel is hidden")
        d.root(f"kill -9 {host[0]}")
        _expect_panel(d, SHOWN, 8, "root panel host killed")
    finally:
        _end_neo(d)


@test("frontends", "Pause and standby keep a hidden panel hidden and its card still restores it")
def panel_pause_standby(d):
    try:
        _start_neo(d)
        _hide_by_home(d)
        d.put("toggle_buttons", "l1_r1")
        d.put("toggle_hold_ms", 300)
        pair = Seq().key(pad.L1, True).key(pad.R1, True).wait(600).key(pad.L1, False).key(pad.R1, False).wait(300)
        d.press(pair)
        time.sleep(2.0)
        if _panel(d) != HIDDEN:
            raise Fail(f"pausing changed the hidden panel: {_panel(d)}")
        d.press(pair)
        time.sleep(1.0)
        d.sh("input keyevent KEYCODE_SLEEP")
        d.eventually(lambda: not d.awake(), 5, "the device did not go to standby")
        d.keyevent("KEYCODE_WAKEUP")
        d.eventually(d.awake, 5, "the device did not wake up")
        d.sh("wm dismiss-keyguard")
        time.sleep(2.0)
        d.reconnect()
        if _panel(d) != HIDDEN:
            raise Fail(f"standby changed the hidden panel: {_panel(d)}")
        _open_switcher(d)
        d.press(Seq().tap(d.confirm_key()))
        _expect_panel(d, SHOWN, 6, "confirming the panel card after pause and standby")
    finally:
        _end_neo(d)


IISU_MAIN = IISU + "/.launcher.MainActivity"
IISU_SETTINGS = f"/data/data/{IISU}/files/datastore/launcher_settings.preferences_pb"


def _iisu_location(d):
    """Which screen IISU's home is on, from its own settings; its screen swap flips it."""
    return d.root(f"tr -cs 'A-Za-z_' '\\n' < {IISU_SETTINGS} | grep -x -A1 dual_display_home_location | tail -1").strip()


def _iisu_tasks(d):
    return sorted(set(re.findall(rf"#(\d+) type=standard A=\d+:{re.escape(IISU)}", d.sh("dumpsys activity activities"))))


def _start_iisu(d):
    """A cold start, alone: IISU lays out both screens itself only when it starts."""
    if not d.sh(f"pm path {IISU}"):
        raise Skip("IISU is not installed")
    only_frontend(d, IISU)
    if not all(d.resumed().get(i, "").startswith(IISU + "/") for i in (d.top, d.bottom)):
        d.stop_app(IISU)
        d.sh(f"am start -n {IISU_MAIN}")
    d.eventually(lambda: all(d.resumed().get(i, "").startswith(IISU + "/") for i in (d.top, d.bottom)), 12,
                 f"IISU did not fill both screens: {d.resumed()}")
    time.sleep(1.0)


def _end_iisu(d):
    _launchers(d)
    d.stop_app(IISU)


def _hold(d):
    d.bind("back_hold", "@swap_apps")
    d.put("hold_ms", 600)
    helper_settled(d)
    d.press(Seq().key(pad.BACK, True).wait(900).key(pad.BACK, False))


@test("frontends", "Hold in IISU presses IISU's own screen swap instead of moving its tasks")
def iisu_swap(d):
    _start_iisu(d)
    try:
        before, tasks = _iisu_location(d), _iisu_tasks(d)
        for turn in (1, 2):
            _hold(d)
            d.eventually(lambda: (_iisu_location(d) != before) == (turn == 1), 6,
                         f"hold {turn}: IISU's home location is {_iisu_location(d)!r}, was {before!r}")
            if _iisu_tasks(d) != tasks:
                raise Fail(f"hold {turn}: IISU's tasks changed from {tasks} to {_iisu_tasks(d)}")
    finally:
        _end_iisu(d)


def _iisu_set_location(d, wanted):
    """Flips IISU's home location with its own screen swap (Hold while IISU fills both screens)."""
    if _iisu_location(d) == wanted:
        return
    _start_iisu(d)
    _hold(d)
    d.eventually(lambda: _iisu_location(d) == wanted, 6, f"IISU's home location stayed {_iisu_location(d)!r}")


@test("frontends", "A swap IISU takes back is refused with a notice instead of being repeated")
def iisu_take_back(d):
    _start_iisu(d)
    before = _iisu_location(d)
    try:
        # Only with its home on the primary screen does IISU rebuild its layout when its top screen moves away;
        # with ThorExternal it accepts the swap.
        _iisu_set_location(d, "PrimaryHome")
        # With a stock app over IISU's bottom screen nothing is delegated, so the tasks really move.
        d.start_app("com.android.settings/.Settings", d.bottom)
        d.eventually(lambda: d.resumed().get(d.bottom, "").startswith("com.android.settings"), 6,
                     f"Settings did not open on the bottom screen: {d.resumed()}")
        d.wait_toasts_gone()
        _hold(d)
        d.expect_toast(d.text("panel_swap_unavailable"), timeout=6)
        if not d.resumed().get(d.top, "").startswith(IISU + "/"):
            raise Fail(f"IISU did not keep its top screen: {d.resumed()}")
    finally:
        d.stop_app("com.android.settings")
        _iisu_set_location(d, before)
        _end_iisu(d)


ESDE, COMPANION, COCOON = "org.es_de.frontend", "com.esde.companion", "rip.moth.cocoonshell"


def _fronts(d, top, bottom, what, timeout=8):
    d.eventually(lambda: d.resumed().get(d.top, "").startswith(top + "/") and d.resumed().get(d.bottom, "").startswith(bottom + "/"),
                 timeout, f"{what}: expected {top} on top and {bottom} below, saw {d.resumed()}")


def _hold_twice(d, top, bottom):
    """Hold trades the two screens and Hold again trades them back, without a refusal notice."""
    for turn, (up, down) in enumerate(((bottom, top), (top, bottom)), 1):
        d.wait_toasts_gone()
        _hold(d)
        _fronts(d, up, down, f"hold {turn}")
        time.sleep(2.0)  # past the take-back check
        if d.text("panel_swap_unavailable") in d.notices():
            raise Fail(f"hold {turn}: the swap was reported as taken back")


@test("frontends", "Hold swaps ES-DE with its Companion and back")
def esde_swap(d):
    for pkg in (ESDE, COMPANION):
        if not d.sh(f"pm path {pkg}"):
            raise Skip(f"{pkg} is not installed")
    only_frontend(d, ESDE)
    for pkg in (ESDE, COMPANION):
        d.stop_app(pkg)
    try:
        d.sh(f"monkey -p {ESDE} -c android.intent.category.LAUNCHER 1")
        d.eventually(lambda: d.resumed().get(d.top, "").startswith(ESDE + "/"), 12, f"ES-DE did not open on top: {d.resumed()}")
        # ES-DE leaves the bottom screen alone; the owner opens Companion there.
        d.sh(f"am start --display {d.bottom} -n {COMPANION}/.ui.MainActivity")
        _fronts(d, ESDE, COMPANION, "ES-DE with Companion", 10)
        _hold_twice(d, ESDE, COMPANION)
    finally:
        _launchers(d)
        for pkg in (COMPANION, ESDE):
            d.stop_app(pkg)


COCOON_SETTINGS = f"/data/data/{COCOON}/shared_prefs/cocoon_settings.xml"


def _cocoon_swapped(d):
    """Cocoon's own layout setting, which its X swap toggles."""
    return d.root(f"grep -o 'swap_screens\" value=\"[a-z]*' {COCOON_SETTINGS}").strip()


def _cocoon_tasks(d):
    return sorted(set(re.findall(rf"#(\d+) type=standard A=\d+:{re.escape(COCOON)}", d.sh("dumpsys activity activities"))))


@test("frontends", "Hold in Cocoon presses Cocoon's own screen swap instead of moving its tasks")
def cocoon_swap(d):
    if not d.sh(f"pm path {COCOON}"):
        raise Skip("Cocoon is not installed")
    only_frontend(d, COCOON)
    d.stop_app(COCOON)
    try:
        d.sh(f"monkey -p {COCOON} -c android.intent.category.LAUNCHER 1")
        try:
            _fronts(d, COCOON, COCOON, "Cocoon", 12)
        except Fail as e:
            # Whether Cocoon opens its bottom activity depends on its own history (see the module doc).
            raise Skip(f"Cocoon did not open both screens: {e}")
        time.sleep(2.0)
        before, tasks = _cocoon_swapped(d), _cocoon_tasks(d)
        for turn in (1, 2):
            d.wait_toasts_gone()
            _hold(d)
            d.eventually(lambda: (_cocoon_swapped(d) != before) == (turn == 1), 6,
                         f"hold {turn}: Cocoon's swap setting is {_cocoon_swapped(d)!r}, was {before!r}")
            if _cocoon_tasks(d) != tasks:
                raise Fail(f"hold {turn}: Cocoon's tasks changed from {tasks} to {_cocoon_tasks(d)}")
            if d.text("panel_swap_unavailable") in d.notices():
                raise Fail(f"hold {turn}: the swap was refused")
    finally:
        _launchers(d)
        d.stop_app(COCOON)


@test("frontends", "Hold-to-swap is refused while the frontend owns a bottom panel, shown or hidden")
def panel_blocks_swap(d):
    try:
        _start_neo(d)
        d.bind("back_hold", "@swap_apps")
        d.put("hold_ms", 600)
        hold = Seq().key(pad.BACK, True).wait(900).key(pad.BACK, False)
        for state in ("shown", "hidden"):
            if state == "hidden":
                _hide_by_home(d)
            tasks = d.sh(f"dumpsys activity recents | grep -c 'A=[0-9]*:{NEO}'").strip()
            d.wait_toasts_gone()
            d.press(hold)
            try:
                d.expect_toast(d.text("panel_swap_unavailable"), timeout=5)
            except Fail as e:
                raise Fail(f"panel {state}: {e}")
            if not d.resumed().get(d.top, "").startswith(NEO + "/"):
                raise Fail(f"panel {state}: the frontend moved: {d.resumed()}")
            if d.sh(f"dumpsys activity recents | grep -c 'A=[0-9]*:{NEO}'").strip() != tasks:
                raise Fail(f"panel {state}: the frontend's tasks changed")
    finally:
        # The last test of the area (see _end_neo).
        _end_neo(d, last=True)
