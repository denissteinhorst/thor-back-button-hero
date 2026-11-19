"""Screenshots of each screen and of both, and screen recording. Every file a test creates is
deleted again through the media store, so the gallery keeps no trace of the run."""

import re
import time

from dt.device import Fail
from dt.pad import Seq
from dt.runner import test

SHOTS = "/sdcard/Pictures/Screenshots"
VIDEOS = "/storage/emulated/0/Videos/BBH-Records"
KEY = "face_y"


def _press(d):
    d.press(Seq().combo(d.face("y")))


def _delete(d, directory, names):
    for name in names:
        if not re.fullmatch(r"[\w.-]+", name):
            continue
        d.sh(f"content delete --uri content://media/external/file --where \"_display_name='{name}'\"")
        d.root(f"rm -f {directory}/{name}")


def _new_files(d, directory, before, count, timeout=8):
    def found():
        new = d.files(directory) - before
        return new if len(new) >= count else None
    try:
        return d.eventually(found, timeout, "")
    except Fail:
        raise Fail(f"expected {count} new file(s) in {directory}, got {sorted(d.files(directory) - before)}")


def _png_size(d, path):
    """Width and height from the PNG header."""
    header = d.root(f"od -A n -t x1 -j 16 -N 8 {path} | tr -d ' \\n'")
    if len(header) != 16:
        raise Fail(f"{path} is no PNG ({header!r})")
    return int(header[:8], 16), int(header[8:], 16)


@test("capture", "Screenshot of the top, the bottom and both screens")
def screenshots(d):
    top_w, top_h = map(int, re.findall(r"(\d+)x(\d+)", d.sh("wm size"))[-1])
    for target in ("top", "bottom", "both"):
        before = d.files(SHOTS)
        d.bind(KEY, "@screenshot", target=target)
        d.wait_toasts_gone(3)
        _press(d)
        new = _new_files(d, SHOTS, before, 1)
        time.sleep(1.5)
        new = d.files(SHOTS) - before
        try:
            if len(new) != 1:
                raise Fail(f"{target}: expected one image, got {sorted(new)}")
            w, h = _png_size(d, f"{SHOTS}/{next(iter(new))}")
            if target == "top" and (w, h) not in ((top_w, top_h), (top_h, top_w)):
                raise Fail(f"top screenshot is {w}x{h}, the top screen {top_w}x{top_h}")
            if target == "both" and max(w, h) <= max(top_w, top_h):
                raise Fail(f"the screenshot of both screens is only {w}x{h}")
        finally:
            _delete(d, SHOTS, new)
        time.sleep(1.0)


@test("capture", "Screen recording starts and stops on the same hotkey and saves a playable video")
def record(d):
    d.sh(f"mkdir -p {VIDEOS}")
    before = d.files(VIDEOS)
    d.bind(KEY, "@record_video", target="top", record_mic=False)
    d.wait_toasts_gone(3)
    _press(d)
    time.sleep(4.0)
    _press(d)
    new = _new_files(d, VIDEOS, before, 1, timeout=10)
    try:
        path = f"{VIDEOS}/{next(iter(new))}"
        d.eventually(lambda: int(d.root(f"stat -c %s {path}") or 0) > 50_000, 6, f"{path} stayed (nearly) empty")
        head = d.root(f"od -A n -c -j 4 -N 4 {path} | tr -d ' \\n'")
        if head != "ftyp":
            raise Fail(f"{path} is no MP4 (header {head!r})")
        if not path.endswith("_top.mp4"):
            raise Fail(f"unexpected file name {path}")
    finally:
        _delete(d, VIDEOS, new)
