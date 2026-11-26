# SPDX-License-Identifier: 0BSD
"""Instrumented results count only what actually ran: a failure, a crash and a test that never
started are all told apart from a pass."""

import unittest

import instrument

OUT = """\
INSTRUMENTATION_STATUS: class=a.B
INSTRUMENTATION_STATUS: test=ok
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=a.B
INSTRUMENTATION_STATUS: test=ok
INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_STATUS: class=a.B
INSTRUMENTATION_STATUS: test=broken
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: stack=java.lang.AssertionError
INSTRUMENTATION_STATUS: class=a.B
INSTRUMENTATION_STATUS: test=broken
INSTRUMENTATION_STATUS_CODE: -2
INSTRUMENTATION_STATUS: class=a.C
INSTRUMENTATION_STATUS: test=crashed
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=a.C
INSTRUMENTATION_STATUS: test=crashed
INSTRUMENTATION_STATUS_CODE: -1
INSTRUMENTATION_CODE: -1
"""


class InstrumentTest(unittest.TestCase):
    def test_pass_failure_and_error_are_told_apart(self):
        passed, failed = instrument.results(OUT)
        self.assertEqual({"a.B#ok"}, passed)
        self.assertEqual({"a.B#broken", "a.C#crashed"}, failed)

    def test_a_started_but_unfinished_test_is_not_a_pass(self):
        passed, failed = instrument.results("INSTRUMENTATION_STATUS: class=a.B\nINSTRUMENTATION_STATUS: test=hang\n"
                                            "INSTRUMENTATION_STATUS_CODE: 1\n")
        self.assertEqual(set(), passed)
        self.assertEqual(set(), failed)

    def test_no_output_runs_nothing(self):
        self.assertEqual((set(), set()), instrument.results(""))

    def test_expected_tests_come_from_the_sources(self):
        self.assertIn("dev.steinhorst.backbuttonhero.SettingsActivitySmokeTest#settingsActivityLaunchesAndShowsCoreSections",
                      instrument.expected_tests())


if __name__ == "__main__":
    unittest.main()
