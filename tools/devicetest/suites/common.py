"""Shared set-up for the suites."""

import time

from dt.device import Fail
from dt.pad import Seq


def ensure_helper(d, timeout=12):
    """The root input helper runs while any D-pad or stick action is assigned; give it one."""
    if not d.helper_pid():
        d.bind("dpad_down", "@status_overlay")
    d.eventually(d.helper_pid, timeout, "the root input helper did not start")
    # A helper that is just (re)starting, after a wake-up or a settings change, still swaps the pad:
    # presses made then can get lost. Wait until the same helper has run for a moment.
    def settled():
        first = d.helper_pid()
        time.sleep(1.0)
        return first and first == d.helper_pid()
    d.eventually(settled, timeout, "the root input helper keeps restarting")


def combo(d, code, **kw):
    d.press(Seq().combo(code, **kw))


def media_session(d):
    d.app("media", value="start")
    d.eventually(lambda: "Media button session is dev.steinhorst.backbuttonhero" in d.sh("dumpsys media_session"), 5,
                 "the test media session did not become the media button session")


def expect_media(d, action, expected, timeout=3.0):
    """Runs [action] and expects exactly the media keys [expected] to reach the test session."""
    d.clear_log()
    action()
    try:
        d.eventually(lambda: len(d.media_keys()) >= len(expected), timeout, "")
    except Fail:
        pass
    time.sleep(0.3)
    got = d.media_keys()
    if got != expected:
        raise Fail(f"expected media keys {expected}, got {got}")


def on_off(d, on):
    return d.text("ayn_on" if on else "ayn_off")
