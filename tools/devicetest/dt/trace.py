# SPDX-License-Identifier: 0BSD
"""Gesture traces from a DEBUG_GESTURES build: the service's own decision log, normalized so two runs
compare exactly. Only identified volatile fields are replaced, and each by a symbol bound one-to-one,
so reuse and distinctness survive: pid/time, measured durations, input device ids, task ids and the
live battery/temperature readings in the status notice. Keys,
actions, displays, arguments and the order of the lines stay as they are."""

import re

TAG = "BackButtonHero"
LINE = re.compile(r"^[VDIWEF]/" + TAG + r"\(\s*\d+\): (?P<msg>.*)$")
# Durations measured on the device, never configured values like "holdRunnable in 800ms".
MEASURED = [
    (re.compile(r"\bt=-?\d+\b"), "t=<t>"),
    (re.compile(r"\(\+-?\d+ms since previous DOWN\)"), "(+<dt> since previous DOWN)"),
    (re.compile(r"\bheld=-?\d+ms\b"), "held=<dt>"),
]
# The status notice reports live device readings; only those numbers vary, its wording stays.
NOTICE = re.compile(r"^Notice: ")
READINGS = [
    (re.compile(r"\b\d+ %"), "<n> %"),
    (re.compile(r"-?\d+(?:[.,]\d+)? °C"), "<n> °C"),
]
SYMBOLS = [("device", "D"), ("task", "T")]


def messages(logcat):
    """The service's lines out of brief-format logcat text, in order."""
    out = []
    for line in logcat.splitlines():
        m = LINE.match(line.rstrip())
        if m:
            out.append(m.group("msg").rstrip())
    return out


def normalize(lines):
    """Replaces the volatile fields; ids are numbered by first appearance, per kind."""
    seen = {kind: {} for kind, _ in SYMBOLS}
    result = []
    for line in lines:
        for pattern, replacement in MEASURED:
            line = pattern.sub(replacement, line)
        if NOTICE.match(line):
            for pattern, replacement in READINGS:
                line = pattern.sub(replacement, line)
        for kind, prefix in SYMBOLS:
            def bind(m, kind=kind, prefix=prefix):
                ids = seen[kind]
                ids.setdefault(m.group(1), f"{prefix}{len(ids) + 1}")
                return f"{kind}={ids[m.group(1)]}"
            line = re.sub(r"\b" + kind + r"=(-?\d+)\b", bind, line)
        result.append(line)
    return result


def compare(expected, actual):
    """Differences between two normalized traces as readable lines; empty when they are identical."""
    if expected == actual:
        return []
    diffs = []
    for i in range(max(len(expected), len(actual))):
        a = expected[i] if i < len(expected) else "<missing>"
        b = actual[i] if i < len(actual) else "<missing>"
        if a != b:
            diffs.append(f"line {i + 1}: expected {a!r}, got {b!r}")
    return diffs
