"""The settings screen on the top display, driven through its own views: a binding card opens the
chooser of its own binding, and the manual update check returns the row to idle with an answer.

The views are found in uiautomator's dump of the top display by their visible texts, which follow
the device's language through the app's own string resources."""

import re
import time
import xml.etree.ElementTree as ET

from dt.device import Fail
from dt.runner import test

ACTIVITY = "dev.steinhorst.backbuttonhero/.SettingsActivity"
DUMP = "/sdcard/bbh-settings-ui.xml"
_BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def _nodes(d):
    xml = d.sh(f"uiautomator dump {DUMP} >/dev/null 2>&1; cat {DUMP}; rm -f {DUMP}")
    start = xml.find("<?xml")
    if start < 0:
        return []
    return list(ET.fromstring(xml[start:]).iter("node"))


def _labels(d):
    return [n.get("text") or n.get("content-desc") or "" for n in _nodes(d)]


def _find(d, label):
    for node in _nodes(d):
        if label in (node.get("text"), node.get("content-desc")):
            return node
    return None


def _tap(d, label, timeout=5):
    node = None

    def found():
        nonlocal node
        node = _find(d, label)
        return node is not None
    d.eventually(found, timeout, f"'{label}' is not on the settings screen: {_labels(d)}")
    x1, y1, x2, y2 = map(int, _BOUNDS.match(node.get("bounds")).groups())
    d.sh(f"input -d {d.top} tap {(x1 + x2) // 2} {(y1 + y2) // 2}")


def _scroll_to(d, label, attempts=8):
    """Pages are scroll views; swipes up on the right half until [label] is visible."""
    for _ in range(attempts):
        if _find(d, label) is not None:
            return
        d.sh(f"input -d {d.top} swipe 1400 800 1400 300 250")
        time.sleep(0.4)
    raise Fail(f"'{label}' never scrolled into view: {_labels(d)}")


def _shown(d, label, timeout=5):
    d.eventually(lambda: _find(d, label) is not None, timeout, f"'{label}' did not appear: {_labels(d)}")


def _open(d, section):
    # The run starts from fresh settings, so the welcome sheet and its start-setup choice would come
    # first; picking one would rewrite the bindings. Marking it seen skips both.
    d.put("welcome_v2_seen", True)
    d.sh(f"am start --display {d.top} -S -n {ACTIVITY}")
    _shown(d, d.text("nav_overview"), 8)
    _tap(d, d.text(section))
    time.sleep(0.8)


def _close(d):
    d.keyevent("KEYCODE_BACK", d.top)
    time.sleep(0.3)
    d.keyevent("KEYCODE_HOME", d.top)


@test("settings", "A shoulder card opens the action chooser of its own button, and Back changes nothing")
def binding_card(d):
    try:
        _open(d, "nav_shoulders")
        before = d.prefs_xml()
        chooser = d.text("shoulder_choose_action_title", d.text("shoulder_l1"))
        # A rail entry lays the full panel over the page, and the first touch only closes it.
        for _ in range(2):
            _tap(d, d.text("shoulder_l1"))
            time.sleep(1.0)
            if _find(d, chooser) is not None:
                break
        _shown(d, chooser)
        d.keyevent("KEYCODE_BACK", d.top)
        d.eventually(lambda: _find(d, chooser) is None, 4,
                     "Back left the chooser open")
        if d.prefs_xml() != before:
            raise Fail("cancelling the chooser changed the stored settings")
    finally:
        _close(d)


@test("settings", "The manual update check answers in a dialog and the row returns to idle")
def update_check(d):
    answers = (d.text("no_update_message"), d.text("update_check_failed_message"), d.text("update_rate_limited_message"))
    found = d.text("update_found_message", "\u0000").split("\u0000")[0]
    try:
        _open(d, "nav_help")
        _scroll_to(d, d.text("check_for_updates"))
        _tap(d, d.text("check_for_updates"))

        def answered():
            labels = _labels(d)
            return any(a in labels for a in answers) or any(found and l.startswith(found) for l in labels) \
                or any(l.startswith("GitHub") for l in labels)
        d.eventually(answered, 12, f"the update check showed no answer: {_labels(d)}")
        d.keyevent("KEYCODE_BACK", d.top)
        _shown(d, d.text("check_for_updates"))
        if _find(d, d.text("checking_for_updates")) is not None:
            raise Fail("the row still says it is checking")
    finally:
        _close(d)
