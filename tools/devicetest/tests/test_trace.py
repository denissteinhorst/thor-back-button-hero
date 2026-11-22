# SPDX-License-Identifier: 0BSD
"""The trace normalizer hides only what varies between runs: a wrong display, a missing, duplicate or
reordered effect, or two ids collapsed into one still differ."""

import unittest

from dt.trace import compare, messages, normalize

LOG = """\
--------- beginning of main
D/BackButtonHero( 4321): DOWN   t=1200345 (+-1ms since previous DOWN)  afterTap=false tapHoldEnabled=true -> scheduled holdRunnable in 800ms
D/OtherTag( 99): unrelated
D/BackButtonHero( 4321): UP     t=1200410 held=65ms  holdFired=false tapHoldFired=false comboFired=false -> consumed, no tap logic
D/BackButtonHero( 4321): BACK   native passthrough action=0 device=17 home=false
D/BackButtonHero( 4321): BACK   native passthrough action=1 device=17 home=false
D/BackButtonHero( 4321): BACK   native passthrough action=0 device=23 home=true
D/BackButtonHero( 4321): ACTION nativeBack longPress=false display=4 sent=true
D/BackButtonHero( 4321): TOP_RECENTS selected task=812 states=3
"""
OTHER_RUN = LOG.replace("4321", "7777").replace("1200345", "980001").replace("1200410", "980090") \
    .replace("held=65ms", "held=89ms").replace("device=17", "device=31").replace("device=23", "device=40") \
    .replace("task=812", "task=1024")


def trace(text):
    return normalize(messages(text))


class TraceTest(unittest.TestCase):
    def test_only_service_lines_are_read(self):
        self.assertEqual(7, len(messages(LOG)))

    def test_volatile_fields_become_symbols(self):
        lines = trace(LOG)
        self.assertIn("DOWN   t=<t> (+<dt> since previous DOWN)  afterTap=false tapHoldEnabled=true -> scheduled holdRunnable in 800ms", lines)
        self.assertIn("BACK   native passthrough action=0 device=D1 home=false", lines)
        self.assertIn("BACK   native passthrough action=0 device=D2 home=true", lines)
        self.assertIn("TOP_RECENTS selected task=T1 states=3", lines)

    def test_another_run_with_other_volatile_values_is_identical(self):
        self.assertEqual([], compare(trace(LOG), trace(OTHER_RUN)))

    def test_configured_durations_are_kept(self):
        self.assertTrue(compare(trace(LOG), trace(LOG.replace("in 800ms", "in 801ms"))))

    def test_wrong_display_differs(self):
        self.assertTrue(compare(trace(LOG), trace(LOG.replace("display=4", "display=0"))))

    def test_missing_effect_differs(self):
        self.assertTrue(compare(trace(LOG), trace(LOG.replace(
            "D/BackButtonHero( 4321): ACTION nativeBack longPress=false display=4 sent=true\n", ""))))

    def test_duplicate_effect_differs(self):
        line = "D/BackButtonHero( 4321): ACTION nativeBack longPress=false display=4 sent=true\n"
        self.assertTrue(compare(trace(LOG), trace(LOG.replace(line, line + line))))

    def test_reordered_dependent_lines_differ(self):
        lines = LOG.splitlines(keepends=True)
        swapped = "".join(lines[:1] + [lines[3], lines[2]] + lines[4:])
        self.assertNotEqual(LOG, swapped)
        self.assertTrue(compare(trace(LOG), trace(swapped)))

    def test_collapsed_device_ids_differ(self):
        self.assertTrue(compare(trace(LOG), trace(LOG.replace("device=23", "device=17"))))

    def test_compare_names_the_line(self):
        diffs = compare(["a", "b"], ["a"])
        self.assertEqual(["line 2: expected 'b', got '<missing>'"], diffs)


if __name__ == "__main__":
    unittest.main()
