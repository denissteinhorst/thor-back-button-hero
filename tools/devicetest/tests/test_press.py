# SPDX-License-Identifier: 0BSD
"""The press suite judges a timed clone capture correctly, and its sources select its area."""

import unittest

from dt import pad, runner
from dt.device import getevent_events
from suites.press import EV_KEY, held_inside
import suites  # noqa: F401 - registers the tests

A = (EV_KEY, pad.A)
L1 = (EV_KEY, pad.L1)


def key(t, code, value):
    return (t, EV_KEY, code, value)


class GeteventTest(unittest.TestCase):
    def test_plain_and_timed_lines(self):
        text = "[    1234.500000] 0001 0130 00000001\r\n[    1234.600000] 0000 0000 00000000\r\n"
        self.assertEqual(getevent_events(text, timed=True), [(1234.5, 1, 0x130, 1), (1234.6, 0, 0, 0)])
        self.assertEqual(getevent_events("0003 0011 ffffffff\n"), [(3, 0x11, 0xffffffff)])


class HeldInsideTest(unittest.TestCase):
    def test_a_press_held_across_the_trigger_passes(self):
        events = [key(1.0, pad.L1, 1), key(1.05, pad.A, 1), key(1.7, pad.L1, 0), key(1.75, pad.A, 0)]
        self.assertIsNone(held_inside(events, L1, A))

    def test_a_missing_or_stuck_press_fails(self):
        self.assertIn("never pressed", held_inside([key(1.0, pad.L1, 1), key(1.7, pad.L1, 0)], L1, A))
        stuck = [key(1.0, pad.L1, 1), key(1.05, pad.A, 1), key(1.7, pad.L1, 0)]
        self.assertIn("stay pressed", held_inside(stuck, L1, A))

    def test_a_tap_instead_of_a_hold_fails(self):
        events = [key(1.0, pad.L1, 1), key(1.05, pad.A, 1), key(1.13, pad.A, 0), key(1.7, pad.L1, 0)]
        self.assertIn("held 0.08 s", held_inside(events, L1, A))

    def test_a_press_released_before_its_trigger_fails(self):
        events = [key(1.0, pad.L1, 1), key(1.05, pad.A, 1), key(1.6, pad.A, 0), key(1.7, pad.L1, 0)]
        self.assertIn("not held inside the trigger", held_inside(events, L1, A))

    def test_a_withheld_trigger_needs_only_the_press(self):
        events = [key(1.05, pad.R1, 1), key(1.75, pad.R1, 0)]
        self.assertIsNone(held_inside(events, None, (EV_KEY, pad.R1)))
        self.assertIn("did not reach the clone", held_inside(events, L1, (EV_KEY, pad.R1)))


class AreaTest(unittest.TestCase):
    def test_press_sources_select_the_press_area(self):
        self.assertEqual(runner.areas_for(["app/src/main/java/x/pad/PadPress.kt"]), {"press"})
        self.assertIn("press", runner.areas_for(["app/src/main/cpp/padhelper.c"]))
        self.assertIn("press", runner.registered_areas())


if __name__ == "__main__":
    unittest.main()
