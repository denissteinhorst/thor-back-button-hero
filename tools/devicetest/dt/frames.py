# SPDX-License-Identifier: 0BSD
"""Raw screen frames: binary-safe capture, strict header validation and masked exact comparison.

`screencap` without `-p` writes a little-endian header of four uint32 fields (width, height, pixel
format, colorspace) followed by width*height pixels without row padding. Only the format verified on
the Thor (1 = RGBA_8888, 4 bytes per pixel) is accepted; anything else is rejected, never guessed.
"""

import struct
import subprocess

HEADER = struct.Struct("<4I")
RGBA_8888 = 1
BYTES = {RGBA_8888: 4}


class FrameError(ValueError):
    pass


class Frame:
    def __init__(self, width, height, fmt, colorspace, pixels):
        self.width, self.height, self.format, self.colorspace, self.pixels = width, height, fmt, colorspace, pixels

    def meta(self):
        return {"width": self.width, "height": self.height, "format": self.format, "colorspace": self.colorspace}


def parse(data: bytes) -> Frame:
    if len(data) < HEADER.size:
        raise FrameError(f"truncated header: {len(data)} bytes")
    width, height, fmt, colorspace = HEADER.unpack_from(data)
    if fmt not in BYTES:
        raise FrameError(f"unsupported pixel format {fmt}")
    if width == 0 or height == 0:
        raise FrameError(f"empty frame {width}x{height}")
    expected = HEADER.size + width * height * BYTES[fmt]
    if len(data) != expected:
        raise FrameError(f"payload length {len(data)} != {expected} for {width}x{height}")
    return Frame(width, height, fmt, colorspace, data[HEADER.size:])


def capture(serial: str, physical_display: str, timeout=30) -> bytes:
    """One raw frame of a physical display, over a binary-safe exec-out channel."""
    res = subprocess.run(["adb", "-s", serial, "exec-out", "screencap", "-d", str(physical_display)],
                         capture_output=True, timeout=timeout)
    if res.returncode != 0:
        raise FrameError(f"screencap failed: {res.stderr.decode(errors='replace').strip()}")
    return res.stdout


def differences(a: Frame, b: Frame, masks=()):
    """(changed pixel count, bounding box or None) outside [masks] = [(left, top, right, bottom)], exclusive ends."""
    if a.meta() != b.meta():
        raise FrameError(f"incomparable frames {a.meta()} vs {b.meta()}")
    for left, top, right, bottom in masks:
        if not (0 <= left < right <= a.width and 0 <= top < bottom <= a.height):
            raise FrameError(f"mask {(left, top, right, bottom)} outside {a.width}x{a.height}")
    stride = a.width * BYTES[a.format]
    count, box = 0, None
    for y in range(a.height):
        row_a = a.pixels[y * stride:(y + 1) * stride]
        row_b = b.pixels[y * stride:(y + 1) * stride]
        if row_a == row_b:
            continue
        covered = [(l, r) for l, t, r, bt in masks if t <= y < bt]
        for x in range(a.width):
            i = x * 4
            if row_a[i:i + 4] == row_b[i:i + 4] or any(l <= x < r for l, r in covered):
                continue
            count += 1
            box = (x, y, x + 1, y + 1) if box is None else (min(box[0], x), min(box[1], y), max(box[2], x + 1), max(box[3], y + 1))
    return count, box
