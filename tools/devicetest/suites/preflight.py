import time

from dt.device import Fail
from dt.pad import BACK, Seq
from dt.runner import test

from .common import ensure_helper, media_session


@test("preflight", "service bound, root reachable, input helper running")
def preflight(d):
    d.eventually(d.service_bound, 10, "the accessibility service is not bound")
    if "uid=0" not in d.root("id"):
        raise Fail("pservice root is unavailable")
    ensure_helper(d)
    _warm_up(d)
    d.log(f"    display top={d.top} bottom={d.bottom}, locale {d.locale}, helper pid {d.helper_pid()}")


def _warm_up(d):
    """An install recreates the pad clone, and its first press can get lost: the first test would fail
    for no reason of its own. Press Back until one arrives, then put the settings back to factory."""
    media_session(d)
    d.bind("back_single", "@media_play_pause")
    d.unbind("back_double")
    try:
        for _ in range(5):
            d.clear_log()
            d.press(Seq().tap(BACK, after=300))
            time.sleep(1.0)
            if d.media_keys():
                return
        raise Fail("no Back press reached the app")
    finally:
        d.app("media", value="stop")
        d.app("reset")
        ensure_helper(d)
