# SPDX-License-Identifier: 0BSD
"""The MP4 track reader finds handler, codec, duration and the audio format, and rejects broken boxes."""

import struct
import unittest

from dt.mp4 import Mp4Error, tracks


def box(kind, payload=b""):
    return struct.pack(">I4s", 8 + len(payload), kind) + payload


def trak(handler, codec, timescale, duration, channels=0, rate=0, version=0):
    if version == 1:
        mdhd = box(b"mdhd", bytes([1, 0, 0, 0]) + b"\x00" * 16 + struct.pack(">IQ", timescale, duration) + b"\x00" * 4)
    else:
        mdhd = box(b"mdhd", b"\x00" * 12 + struct.pack(">II", timescale, duration) + b"\x00" * 4)
    hdlr = box(b"hdlr", b"\x00" * 8 + handler + b"\x00" * 12)
    if handler == b"soun":
        sample = b"\x00" * 6 + b"\x00\x01" + b"\x00" * 8 + struct.pack(">HH", channels, 16) + b"\x00" * 4 + struct.pack(">I", rate << 16)
    else:
        sample = b"\x00" * 70
    stsd = box(b"stsd", b"\x00" * 4 + struct.pack(">I", 1) + struct.pack(">I4s", 8 + len(sample), codec) + sample)
    return box(b"trak", box(b"mdia", mdhd + hdlr + box(b"minf", box(b"stbl", stsd))))


class TracksTest(unittest.TestCase):
    def test_video_and_audio(self):
        data = box(b"ftyp", b"isom") + box(b"moov", trak(b"vide", b"avc1", 90000, 360000)
                                             + trak(b"soun", b"mp4a", 48000, 192000, channels=2, rate=48000))
        self.assertEqual(tracks(data), [
            {"handler": "vide", "codec": "avc1", "seconds": 4.0},
            {"handler": "soun", "codec": "mp4a", "seconds": 4.0, "channels": 2, "rate": 48000},
        ])

    def test_version_one_durations(self):
        data = box(b"moov", trak(b"vide", b"hvc1", 1000, 2500, version=1))
        self.assertEqual(tracks(data)[0]["seconds"], 2.5)

    def test_rejections(self):
        good = box(b"moov", trak(b"vide", b"avc1", 1000, 1000))
        for data in (b"", box(b"ftyp"), good[:-3], struct.pack(">I4s", 4, b"moov"), box(b"moov", box(b"trak"))):
            with self.subTest(n=len(data)), self.assertRaises(Mp4Error):
                tracks(data)


if __name__ == "__main__":
    unittest.main()
