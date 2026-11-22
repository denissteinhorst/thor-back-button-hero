# SPDX-License-Identifier: 0BSD
"""Area selection must fail closed, and a run is only green when every expected test passed."""

import unittest

from dt import runner
import suites  # noqa: F401 - registers the tests


def result(area, title, status="PASS", warnings=()):
    return {"area": area, "title": title, "status": status, "warnings": list(warnings)}


class OnlyTest(unittest.TestCase):
    def test_registered_full_names_are_accepted(self):
        self.assertEqual(runner.parse_only("sources,gestures"), {"sources", "gestures"})

    def test_short_aliases_fail(self):
        with self.assertRaises(ValueError):
            runner.parse_only("src,ges")

    def test_one_unknown_name_fails_the_whole_list(self):
        with self.assertRaises(ValueError):
            runner.parse_only("sources,nosuch")

    def test_empty_lists_fail(self):
        for text in ("", " ", "sources,", ",sources"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                runner.parse_only(text)


class ChangedTest(unittest.TestCase):
    def test_mapped_files_select_their_areas(self):
        self.assertEqual(runner.areas_for(["app/src/main/java/x/Gyro.kt"]), {"gyro"})

    def test_window_prefixes_select_windows(self):
        self.assertEqual(runner.areas_for(["app/src/main/java/x/TopRecentsFocus.kt"]), {"windows"})

    def test_shared_files_select_everything(self):
        self.assertIsNone(runner.areas_for(["app/src/main/java/x/Gyro.kt", "app/src/main/java/x/Prefs.kt"]))

    def test_an_unmapped_production_file_selects_everything(self):
        new_class = "app/src/main/java/x/ExtractedHelper.kt"
        self.assertEqual(runner.unmapped([new_class]), [new_class])
        self.assertIsNone(runner.areas_for(["app/src/main/java/x/Gyro.kt", new_class]))

    def test_unmapped_resources_select_everything(self):
        self.assertIsNone(runner.areas_for(["app/src/main/res/values/strings.xml"]))

    def test_no_change_selects_nothing(self):
        self.assertEqual(runner.areas_for([]), set())


class ExpectedTest(unittest.TestCase):
    def test_preflight_always_runs_first(self):
        ids = runner.expected({"gyro"}, ("back",))
        self.assertEqual(ids[0][0], "preflight")
        self.assertEqual({a for a, _ in ids}, {"preflight", "gyro"})

    def test_home_layer_adds_only_layered_tests(self):
        back = runner.expected(None, ("back",))
        both = runner.expected(None, runner.LAYERS)
        layered = [t for _, t in both if t.endswith(" [Home layer]")]
        self.assertTrue(layered)
        self.assertEqual(len(both), len(back) + len(layered))

    def test_full_selection_counts_every_registered_test(self):
        self.assertEqual(len(runner.expected(None, ("back",))), len(runner.TESTS))


class VerdictTest(unittest.TestCase):
    ids = [("preflight", "p"), ("gyro", "g")]

    def test_all_passed(self):
        code, _ = runner.verdict([result(*i) for i in self.ids], self.ids)
        self.assertEqual(code, 0)

    def test_a_test_that_never_ran_fails(self):
        code, lines = runner.verdict([result("preflight", "p")], self.ids)
        self.assertEqual(code, 1)
        self.assertIn("NOT RUN: [gyro] g", lines)

    def test_an_unexpected_test_fails(self):
        code, _ = runner.verdict([result(*i) for i in self.ids] + [result("ayn", "a")], self.ids)
        self.assertEqual(code, 1)

    def test_failures_and_errors_fail(self):
        for status in ("FAIL", "ERROR"):
            with self.subTest(status=status):
                code, _ = runner.verdict([result("preflight", "p"), result("gyro", "g", status)], self.ids)
                self.assertEqual(code, 1)

    def test_skips_and_warnings_are_never_green(self):
        skipped = [result("preflight", "p"), result("gyro", "g", "SKIP")]
        warned = [result("preflight", "p"), result("gyro", "g", warnings=["slow"])]
        self.assertEqual(runner.verdict(skipped, self.ids)[0], 3)
        self.assertEqual(runner.verdict(warned, self.ids)[0], 3)


if __name__ == "__main__":
    unittest.main()
