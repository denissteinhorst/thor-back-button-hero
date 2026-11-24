# SPDX-License-Identifier: 0BSD
"""The tracks of an MP4 file, read from its boxes: handler, codec, duration and, for audio, sample
rate and channel count. Only what the recording checks need; anything malformed raises Mp4Error."""

import struct


class Mp4Error(Exception):
    pass


CONTAINERS = {b"moov", b"trak", b"mdia", b"minf", b"stbl"}


def boxes(data, start=0, end=None):
    """(type, payload start, payload end) of each box between start and end."""
    end = len(data) if end is None else end
    at = start
    while at < end:
        if at + 8 > end:
            raise Mp4Error(f"truncated box header at {at}")
        size, kind = struct.unpack(">I4s", data[at:at + 8])
        header = 8
        if size == 1:
            if at + 16 > end:
                raise Mp4Error(f"truncated large box at {at}")
            size = struct.unpack(">Q", data[at + 8:at + 16])[0]
            header = 16
        elif size == 0:
            size = end - at
        if size < header or at + size > end:
            raise Mp4Error(f"box {kind!r} at {at} has size {size}")
        yield kind, at + header, at + size
        at += size


def child(data, start, end, kind):
    for k, s, e in boxes(data, start, end):
        if k == kind:
            return s, e
    raise Mp4Error(f"no {kind!r} box")


def tracks(data):
    """One dict per track: handler ('vide'/'soun'), codec, seconds, and rate/channels for audio."""
    moov = child(data, 0, len(data), b"moov")
    found = []
    for kind, s, e in boxes(data, *moov):
        if kind != b"trak":
            continue
        mdia = child(data, s, e, b"mdia")
        mdhd = child(data, *mdia, b"mdhd")
        version = data[mdhd[0]]
        if version == 1:
            timescale, duration = struct.unpack(">IQ", data[mdhd[0] + 20:mdhd[0] + 32])
        else:
            timescale, duration = struct.unpack(">II", data[mdhd[0] + 12:mdhd[0] + 20])
        hdlr = child(data, *mdia, b"hdlr")
        handler = data[hdlr[0] + 8:hdlr[0] + 12].decode("ascii", "replace")
        stbl = child(data, *child(data, *mdia, b"minf"), b"stbl")
        stsd = child(data, *stbl, b"stsd")
        entry = stsd[0] + 8
        if entry + 8 > stsd[1]:
            raise Mp4Error("empty sample description")
        codec = data[entry + 4:entry + 8].decode("ascii", "replace")
        track = {"handler": handler, "codec": codec, "seconds": duration / timescale if timescale else 0.0}
        if handler == "soun":
            # Audio sample entry: 8 header + 6 reserved + 2 index + 8 reserved, then channels, size, ...
            channels = struct.unpack(">H", data[entry + 24:entry + 26])[0]
            rate = struct.unpack(">I", data[entry + 32:entry + 36])[0] >> 16
            track.update(channels=channels, rate=rate)
        found.append(track)
    return found
