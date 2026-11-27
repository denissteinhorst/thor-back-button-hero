"""Shared set-up for the suites."""

import re
import shlex
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


def helper_settled(d, timeout=12):
    """Waits until the helper neither starts nor stops: while it swaps the pad, presses get lost.
    Unlike [ensure_helper] this assigns nothing, so it also fits areas that run without the helper."""
    def settled():
        first = d.helper_pid()
        time.sleep(1.0)
        return first == d.helper_pid()
    d.eventually(settled, timeout, "the root input helper keeps starting or stopping")


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


def expect_closed(d, pkg, previous_pid, timeout=6):
    """Proves termination and task removal even when Android preloads a new background process."""
    previous = set(previous_pid.split())
    if not previous:
        raise Fail(f"{pkg} had no process before the close action")

    def closed():
        return (not previous.intersection(d.pid(pkg).split()) and not d.task_ids(pkg)
                and not any(front.startswith(pkg + "/") for front in d.resumed().values()))

    try:
        d.eventually(closed, timeout, "")
    except Fail:
        kept = [name for name, held in (
            ("process", previous.intersection(d.pid(pkg).split())),
            ("task", d.task_ids(pkg)),
            ("foreground activity", [f for f in d.resumed().values() if f.startswith(pkg + "/")]))
            if held]
        raise Fail(f"{pkg} kept its original {', '.join(kept) or 'state only briefly'} "
                   f"(pids {previous} -> {d.pid(pkg) or 'none'}, tasks {d.task_ids(pkg)}, front {d.resumed()})")


# The frontend whose bottom panel the frontends area uses as its fixture.
NEO = "com.neogamelab.neostation"


def frontend_panel(d):
    """NeoStation's bottom Presentation window: its view visibility, or None when there is none."""
    for block in re.split(r"\n  Window #", d.sh("dumpsys window windows")):
        if block.split("\n", 1)[0].endswith(NEO + "}:") and "ty=PRESENTATION" in block:
            m = re.search(r"mViewVisibility=(\S+)", block)
            return m.group(1) if m else None
    return None


def end_frontend_panel(d):
    """Ends a NeoStation panel left on the bottom screen. `am force-stop` cannot end NeoStation while its
    accessibility service is enabled, and its panel then stays: the bottom switcher lists it as a card of its
    own and confirm restores it instead of opening an app. A root kill ends the panel; Android restarts the
    accessibility service on its own, so the owner's setting is untouched."""
    if frontend_panel(d) is None:
        return
    enabled = d.sh("settings get secure enabled_accessibility_services")
    bound = _neo_service_bound(d)
    pid = d.pid(NEO)
    if pid:
        d.root(f"kill -9 {pid}")
    d.eventually(lambda: frontend_panel(d) is None, 6, f"NeoStation's bottom panel outlived its process ({pid})")
    # A force-stop while Android waits to restart the service drops it from the owner's enabled services, so
    # nothing may stop NeoStation before the service is bound again. A first kill rebinds at once; repeated
    # kills meet Android's growing restart back-off.
    if bound:
        try:
            d.eventually(lambda: _neo_service_bound(d), 20, "")
        except Fail:
            d.warnings.append("NeoStation's accessibility service was not bound again within 20 s after its panel ended")
    if d.sh("settings get secure enabled_accessibility_services") != enabled:
        d.sh(f"settings put secure enabled_accessibility_services {shlex.quote(enabled)}")
        d.warnings.append("NeoStation's kill changed the enabled accessibility services; put back as found")


def _neo_service_bound(d):
    bound = d.sh("dumpsys accessibility").partition("Bound services:")[2].partition("Enabled services:")[0]
    return "Service[label=NeoStation," in bound
