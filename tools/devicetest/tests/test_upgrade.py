# SPDX-License-Identifier: 0BSD
"""The update check reads fixtures exactly, writes every entry it claims to, and counts a service that
needed help after the update as a failure, never as a pass."""

import shlex
import unittest

import upgrade
from dt.device import parse_prefs

GOOD = {"enabled": True, "accessibility_enabled": "1", "bound": True, "process_replaced": True}


class FixtureTest(unittest.TestCase):
    def test_escapes_round_trip_through_the_settings_file(self):
        entries = upgrade.read_fixture("a\tstring\tx\\ty\\nz\\\\w\nb\tlong\t700\nc\tboolean\ttrue\nd\tstring\t\n")
        self.assertEqual(entries["a"], ("string", "x\ty\nz\\w"))
        self.assertEqual(entries["d"], ("string", ""))
        self.assertEqual(parse_prefs(upgrade.prefs_file(entries)), entries)

    def test_every_frozen_fixture_used_is_readable(self):
        for name in ("populated.txt", "legacy-v1.txt", "fresh.txt"):
            self.assertTrue(upgrade.load(name), name)

    def test_unknown_types_and_duplicates_are_refused(self):
        with self.assertRaises(ValueError):
            upgrade.read_fixture("a\tset\tx\n")
        with self.assertRaises(ValueError):
            upgrade.read_fixture("a\tint\t1\na\tint\t2\n")

    def test_xml_special_characters_survive(self):
        entries = {"k": ("string", "<a & 'b' \"c\">")}
        self.assertEqual(parse_prefs(upgrade.prefs_file(entries)), entries)


class SeedTest(unittest.TestCase):
    def test_one_broadcast_carries_every_entry_escaped_like_the_fixtures(self):
        entries = {"a": ("boolean", "true"), "b": ("string", "it's\nhere\t\\"), "c": ("float", "0.25")}
        script = upgrade.seed_script(entries)
        self.assertEqual(script.count("am broadcast"), 1)
        self.assertIn("--es op putall", script)
        words = shlex.split(script.split("|")[0])
        text = words[words.index("entries") + 1]
        self.assertEqual(upgrade.read_fixture(text), entries)

    def test_only_a_full_count_is_accepted(self):
        self.assertEqual(upgrade.seed_count('data="OK 3"\n'), 3)
        for reply in ('data="FAIL"', 'data="ERR x"', "", 'data="OK"'):
            self.assertIsNone(upgrade.seed_count(reply), reply)


class JudgeTest(unittest.TestCase):
    def test_a_kept_service_passes(self):
        self.assertEqual(upgrade.judge(GOOD), [])

    def test_each_missing_observation_fails(self):
        for key, value in (("enabled", False), ("accessibility_enabled", "0"), ("bound", False), ("process_replaced", False)):
            self.assertEqual(len(upgrade.judge(dict(GOOD, **{key: value}))), 1, key)

    def test_an_absent_observation_fails_everything(self):
        self.assertEqual(len(upgrade.judge({})), 4)

    def test_a_result_passes_only_without_any_finding(self):
        self.assertTrue(upgrade.passed({"problems": [], "differences": []}))
        self.assertFalse(upgrade.passed({"differences": ["x: saved a now b"]}))
        self.assertFalse(upgrade.passed({"seed_failures": ["FAIL k"]}))
        self.assertFalse(upgrade.passed({"problems": ["unbound"]}))


if __name__ == "__main__":
    unittest.main()
