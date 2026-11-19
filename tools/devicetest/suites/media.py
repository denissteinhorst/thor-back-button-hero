"""Media keys and the status overlay."""

import re

from dt.device import Fail
from dt.pad import L1, R1
from dt.runner import test

from .common import combo, ensure_helper, expect_media


@test("media", "Media Control sends Play/Pause, Next and Previous to the playing session")
def media_keys(d):
    ensure_helper(d)
    from .common import media_session
    media_session(d)
    d.bind("l1", "@media_play_pause")
    d.bind("r1", "@media_next")
    d.bind("face_x", "@media_previous")
    expect_media(d, lambda: [combo(d, L1), combo(d, R1), combo(d, d.face("x"))], ["MEDIA_PLAY_PAUSE", "MEDIA_NEXT", "MEDIA_PREVIOUS"])


@test("media", "Status Overlay shows battery, remaining time and temperature")
def status(d):
    ensure_helper(d)
    d.bind("l1", "@status_overlay")
    d.wait_toasts_gone()
    combo(d, L1)
    battery = d.sh("dumpsys battery")
    level = int(re.search(r"level: (\d+)", battery).group(1))
    tenths = int(re.search(r"temperature: (\d+)", battery).group(1))
    text = d.eventually(lambda: next(iter(d.toasts()), None), 3, "no status toast")
    parts = text.split(" · ")
    if parts[0] != d.text("status_battery", level):
        raise Fail(f"battery part {parts[0]!r}, expected {d.text('status_battery', level)!r}")
    if parts[-1] != d.text("status_temperature", tenths / 10):
        raise Fail(f"temperature part {parts[-1]!r}, expected {d.text('status_temperature', tenths / 10)!r}")
