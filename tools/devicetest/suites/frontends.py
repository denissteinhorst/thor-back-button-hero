"""A third-party frontend's full-screen bottom panel: NeoStation draws a Presentation there. It is
an owner-installed app; these checks start it and stop it again, and bring the launchers back to
the front. Parked frontend activities (Cocoon) stay a manual check: whether Cocoon opens its bottom
activity at all depends on its own task history, so it cannot serve as a repeatable fixture."""

import re
import time

from dt import pad
from dt.device import Fail
from dt.pad import Seq
from dt.runner import test

NEO = "com.neogamelab.neostation"
KEY = "face_y"
HIDDEN, SHOWN = "0x8", "0x0"


def _launchers(d):
    d.keyevent("KEYCODE_HOME", d.top)
    d.sh(f"am start --display {d.bottom} -a android.intent.action.MAIN -c android.intent.category.SECONDARY_HOME")
    time.sleep(1.0)


def _panel(d):
    """NeoStation's bottom Presentation window: its view visibility, or None when there is none."""
    for block in re.split(r"\n  Window #", d.sh("dumpsys window windows")):
        if block.split("\n", 1)[0].endswith(NEO + "}:") and "ty=PRESENTATION" in block:
            m = re.search(r"mViewVisibility=(\S+)", block)
            return m.group(1) if m else None
    return None


def _expect_panel(d, state, timeout, what):
    d.eventually(lambda: _panel(d) == state, timeout, f"{what}: the panel's visibility is {_panel(d)}, expected {state}")


def _start_neo(d):
    d.stop_app(NEO)
    d.sh(f"monkey -p {NEO} -c android.intent.category.LAUNCHER 1")
    d.eventually(lambda: d.resumed().get(d.top, "").startswith(NEO + "/"), 10, "NeoStation did not open on top")
    _expect_panel(d, SHOWN, 10, "NeoStation started")


def _hide_by_home(d):
    d.bind(KEY, "@home", display="bottom")
    d.press(Seq().combo(d.face("y")))
    d.eventually(lambda: d.resumed().get(d.bottom, "").startswith("com.android.launcher3"), 6,
                 f"Home did not open on the bottom screen: {d.resumed().get(d.bottom)}")
    _expect_panel(d, HIDDEN, 6, "Home on the bottom screen")


def _open_switcher(d):
    d.bind(KEY, "@switcher")
    d.press(Seq().combo(d.face("y")))
    d.eventually(lambda: d.own_windows(d.bottom), 4, "the switcher did not open")
    time.sleep(0.8)


def _end_neo(d):
    if d.own_windows(d.bottom):
        d.press(Seq().tap(pad.BACK))
    _launchers(d)
    d.stop_app(NEO)


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
        _end_neo(d)
