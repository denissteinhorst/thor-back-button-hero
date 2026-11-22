# SPDX-License-Identifier: 0BSD
"""Raw frames are validated strictly and every unmasked pixel change is detected."""

import struct
import unittest

from dt.frames import FrameError, differences, parse


def raw(width, height, fmt=1, colorspace=2, fill=b"\x10\x20\x30\xff", extra=b""):
    return struct.pack("<4I", width, height, fmt, colorspace) + fill * (width * height) + extra


def poke(data, width, x, y, value=b"\x00\x00\x00\xff"):
    i = 16 + (y * width + x) * 4
    return data[:i] + value + data[i + 4:]


class ParseTest(unittest.TestCase):
    def test_a_valid_frame(self):
        f = parse(raw(3, 2))
        self.assertEqual(f.meta(), {"width": 3, "height": 2, "format": 1, "colorspace": 2})
        self.assertEqual(len(f.pixels), 24)

    def test_rejections(self):
        for data in (b"", b"\x00" * 15, raw(3, 2, fmt=2), raw(3, 2, fmt=5), raw(0, 2),
                     raw(3, 2)[:-1], raw(3, 2, extra=b"\x00"), raw(3, 2, extra=b"\x00" * 12)):
            with self.subTest(n=len(data)), self.assertRaises(FrameError):
                parse(data)


class DifferenceTest(unittest.TestCase):
    def test_identical(self):
        self.assertEqual(differences(parse(raw(4, 3)), parse(raw(4, 3))), (0, None))

    def test_every_single_pixel_and_channel_is_detected(self):
        base = raw(4, 3)
        for x in range(4):
            for y in range(3):
                for channel in range(4):
                    value = bytearray(b"\x10\x20\x30\xff"); value[channel] ^= 1
                    with self.subTest(x=x, y=y, c=channel):
                        self.assertEqual(differences(parse(base), parse(poke(base, 4, x, y, bytes(value)))),
                                         (1, (x, y, x + 1, y + 1)))

    def test_masks_hide_only_their_area(self):
        base = raw(5, 4)
        changed = poke(poke(base, 5, 1, 1), 5, 4, 3)
        self.assertEqual(differences(parse(base), parse(changed), masks=[(0, 0, 2, 2)]), (1, (4, 3, 5, 4)))
        self.assertEqual(differences(parse(base), parse(changed), masks=[(0, 0, 2, 2), (4, 3, 5, 4)]), (0, None))
        # Exclusive ends: a mask that stops one pixel short leaves the change visible.
        self.assertEqual(differences(parse(base), parse(changed), masks=[(0, 0, 1, 1), (4, 3, 5, 4)])[0], 1)

    def test_incomparable_frames_and_bad_masks_are_rejected(self):
        with self.assertRaises(FrameError):
            differences(parse(raw(4, 3)), parse(raw(3, 4)))
        with self.assertRaises(FrameError):
            differences(parse(raw(4, 3)), parse(raw(4, 3, colorspace=1)))
        with self.assertRaises(FrameError):
            differences(parse(raw(4, 3)), parse(raw(4, 3)), masks=[(0, 0, 5, 1)])


if __name__ == "__main__":
    unittest.main()
