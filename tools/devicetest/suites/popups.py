"""Do Not Disturb's "Try to hide all pop-ups" option: while Do Not Disturb is on, SystemUI's toast app-op is
ignored, heads-up notifications are off and the app's own notices stay quiet; Do Not Disturb off, a pause, the
process's death and the next start each put the values back. Without the option Do Not Disturb leaves them."""

import re
import shlex
import time

from dt.device import HEADS_UP, PKG, TOAST_OP, Fail, toast_mode
from dt import pad
from dt.pad import L1, R1, Seq
from dt.runner import test

from .common import combo, ensure_helper

GUARD = "PopupGuard"


def _toast(d):
    """VISIBLE or HIDDEN: whether a text toast posted by the shell appears."""
    return d.sh(f"CLASSPATH={d.adb.dex} app_process /system/bin ToastProbe {shlex.quote('Back Button Hero probe')}")


def _guard_pids(d):
    return re.findall(r"^\s*(\d+)", d.root(f"ps -A -o PID,ARGS | grep {GUARD} | grep -v grep | head -1"), re.M)


DND = "@do_not_disturb"


def _dnd_notice(d, state):
    return d.text("ayn_notice_value", d.text("ayn_do_not_disturb"), d.text(f"ayn_{state}"))


def _on(d, before):
    ensure_helper(d)
    if d.setting("zen_mode", "global") != "0":
        d.sh("cmd notification set_dnd off")
    d.bind("l1", DND, dnd_hide_popups=True)
    combo(d, L1)
    # Do Not Disturb's own notice is the confirmation and shows before the mute.
    d.expect_toast(_dnd_notice(d, "on"))
    d.eventually(lambda: d.popups() == {"heads_up": "0", "toast": "ignore"}, 6,
                 f"pop-ups not hidden: {d.popups()} (before {before})")


@test("popups", "Do Not Disturb without its pop-up option leaves toasts and heads-up alone")
def dnd_only(d):
    before = d.popups()
    ensure_helper(d)
    if d.setting("zen_mode", "global") != "0":
        d.sh("cmd notification set_dnd off")
    d.bind("l1", DND, dnd_hide_popups=False)
    combo(d, L1)
    d.expect_toast(_dnd_notice(d, "on"))
    d.eventually(lambda: d.setting("zen_mode", "global") != "0", 4, "Do Not Disturb did not turn on")
    time.sleep(1.0)
    if d.popups() != before:
        raise Fail(f"pop-ups changed without the option: {d.popups()}, before {before}")
    combo(d, L1)
    d.eventually(lambda: d.setting("zen_mode", "global") == "0", 4, "Do Not Disturb did not turn off")


@test("popups", "Do Not Disturb with its pop-up option hides toasts and heads-up, mutes notices, and restores both when off")
def toggle(d):
    before = d.popups()
    if _toast(d) != "VISIBLE":
        raise Fail("the probe toast is not visible before the test; the probe cannot judge")
    _on(d, before)
    if not _guard_pids(d):
        raise Fail("no root restore guard runs while pop-ups are hidden")
    if _toast(d) != "HIDDEN":
        raise Fail("a text toast is still visible while pop-ups are hidden")
    # The app's own notices stay quiet meanwhile.
    d.bind("r1", "@vibration")
    combo(d, R1)
    time.sleep(1.5)
    if d.notices() or d.notice_visible():
        raise Fail(f"a notice appeared while pop-ups are hidden: {d.notices()}")
    combo(d, R1)
    combo(d, L1)
    d.expect_toast(_dnd_notice(d, "off"))
    d.eventually(lambda: d.popups() == before, 6, f"pop-ups not restored: {d.popups()}, before {before}")
    d.eventually(lambda: not _guard_pids(d), 5, "the restore guard outlived the toggle")
    if _toast(d) != "VISIBLE":
        raise Fail("a text toast stays hidden after pop-ups were shown again")


@test("popups", "Pausing the app shows pop-ups again")
def pause(d):
    before = d.popups()
    _on(d, before)
    d.put("toggle_buttons", "l1_r1")
    d.put("toggle_hold_ms", 300)
    pair = Seq().key(pad.L1, True).key(pad.R1, True).wait(600).key(pad.L1, False).key(pad.R1, False).wait(300)
    d.press(pair)
    try:
        d.eventually(lambda: d.popups() == before, 6, f"the pause did not restore: {d.popups()}, before {before}")
        d.eventually(lambda: not _guard_pids(d), 5, "the restore guard outlived the pause")
    finally:
        d.press(pair)


@test("popups", "Killing the app while pop-ups are hidden restores them through the root guard")
def guard(d):
    before = d.popups()
    _on(d, before)
    # `am force-stop` cannot end the process while its service is enabled, so a root kill stands in for a crash.
    # Android restarts the service seconds later and that start restores too; the device samples right after
    # the kill, so a restore it sees while no app process runs can only be the guard's.
    pid = d.pid(PKG)
    samples = d.adb.root_script(f"""kill -9 {pid}
i=0
while [ $i -lt 40 ]; do
  echo "$(settings get global {HEADS_UP})|$(appops get {TOAST_OP})|$(pidof {PKG})"
  i=$((i+1))
done
""")
    try:
        lines = [line.split("|") for line in samples.splitlines() if line.count("|") == 2]
        restored = next(((hu, app) for hu, op, app in lines
                         if hu == before["heads_up"] and toast_mode(op) == before["toast"]), None)
        if restored is None:
            raise Fail(f"the guard did not restore: {d.popups()}, before {before}")
        if restored[1]:
            raise Fail("the app came back before the restore; the guard cannot be told apart from a new start")
        d.eventually(lambda: not _guard_pids(d), 5, "the guard outlived its restore")
    finally:
        d.reconnect()


@test("popups", "A service start puts back pop-ups an earlier session left hidden")
def leftover(d):
    before = d.popups()
    # What a crash would leave: the system values changed and the found ones saved.
    d.put("hide_popups_heads_up", before["heads_up"])
    d.put("hide_popups_toast", before["toast"])
    d.root(f"settings put global {HEADS_UP} 0; appops set {TOAST_OP} ignore")
    d.eventually(lambda: d.popups() == {"heads_up": "0", "toast": "ignore"}, 4, "the leftover state could not be set")
    d.restart_service()
    d.eventually(lambda: d.popups() == before, 8, f"the start did not restore: {d.popups()}, before {before}")
    if "hide_popups_toast" in d.prefs_xml():
        raise Fail("the saved pop-up values stayed after their restore")
