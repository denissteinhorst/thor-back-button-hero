# SPDX-License-Identifier: 0BSD
"""The owner's settings are restored verifiably, or the run fails and keeps every saved copy."""

import os
import tempfile
import unittest
from unittest import mock

from dt import device as dev
from dt.adb import AdbError
from dt.device import Device, RecoveryError, parse_prefs, prefs_differences, restore_count, snapshot_count

OWNER_XML = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="l1_package">@home</string>
    <string name="empty"></string>
    <int name="level" value="3" />
    <long name="hold_ms" value="500" />
    <boolean name="dual_mode" value="false" />
    <float name="speed" value="1.5" />
    <set name="hidden">
        <string>a.b</string>
        <string>c.d</string>
    </set>
</map>
"""
EMPTY_XML = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map />\n"
SYSTEM = {"system": {"vibrate_on": "1"}, "props": {}, "zen": "0", "mic": False, "volume": 5, "brightness": [0.5, 0.4]}


class FakeDevice(Device):
    """The app's receiver and settings file, simulated as the new contract specifies."""

    def __init__(self, xml=OWNER_XML):
        self.disk = xml
        self.copy = None          # (run, xml) once the app holds a complete copy
        self.system = dict(SYSTEM)
        self.replies = {}         # op -> forced reply, simulating failures and lost answers
        self.fallbacks = 0
        self.log = lambda *_: None

    def prefs_xml(self):
        return self.disk

    def snapshot_system(self):
        return dict(self.system)

    def restore_system(self, state):
        self.system = dict(state)

    def restore_prefs_file(self, xml):
        self.fallbacks += 1
        self.disk = xml

    def app(self, op, **extras):
        if op == "reset":
            self.disk = EMPTY_XML
        return "OK"

    def app_reply(self, op, **extras):
        if op in self.replies:
            return self.replies[op]
        if op == "snapshot":
            if self.copy:
                return f"KEPT {self.copy[0]}"
            self.copy = (extras["run"], self.disk)
            return f"OK {len(parse_prefs(self.disk))}"
        if op == "restore":
            if not self.copy:
                return "NONE"
            if extras.get("run") != self.copy[0]:
                return f"OTHER {self.copy[0]}"
            self.disk = self.copy[1]
            return f"OK {len(parse_prefs(self.disk))}"
        if op == "drop":
            self.copy = None
            return "OK"
        raise AssertionError(op)


class Isolated(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        state = os.path.join(self.tmp.name, "state.json")
        for name, value in (("WORK", self.tmp.name), ("STATE_FILE", state)):
            patcher = mock.patch.object(dev, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        self.state = state


class ParseTest(unittest.TestCase):
    def test_every_value_type(self):
        prefs = parse_prefs(OWNER_XML)
        self.assertEqual(prefs["l1_package"], ("string", "@home"))
        self.assertEqual(prefs["empty"], ("string", ""))
        self.assertEqual(prefs["level"], ("int", "3"))
        self.assertEqual(prefs["hold_ms"], ("long", "500"))
        self.assertEqual(prefs["dual_mode"], ("boolean", "false"))
        self.assertEqual(prefs["speed"], ("float", "1.5"))
        self.assertEqual(prefs["hidden"], ("set", frozenset({"a.b", "c.d"})))

    def test_a_valid_empty_map(self):
        self.assertEqual(parse_prefs(EMPTY_XML), {})

    def test_a_failed_read_is_never_an_empty_map(self):
        for text in ("", "run-as: package not debuggable", "<other/>", "<map><int name='x'/></map>"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                parse_prefs(text)

    def test_type_changes_are_differences(self):
        saved = parse_prefs(OWNER_XML)
        current = dict(saved, level=("long", "3"))
        del current["empty"]
        self.assertEqual(len(prefs_differences(saved, current)), 2)


class ReplyTest(unittest.TestCase):
    def test_snapshot_needs_a_fresh_complete_copy(self):
        self.assertEqual(snapshot_count("OK 0"), 0)
        for reply in ("KEPT 1", "KEPT unmarked", "NONE", "", "OK", "FAIL snapshot write", "garbage"):
            with self.subTest(reply=reply), self.assertRaises(RecoveryError):
                snapshot_count(reply)

    def test_restore_replies(self):
        self.assertEqual(restore_count("OK 7"), 7)
        self.assertIsNone(restore_count("NONE"))
        self.assertIsNone(restore_count("OTHER 20260101-000000"))
        for reply in ("", "OK", "FAIL restore write", "ERR x", "KEPT 1"):
            with self.subTest(reply=reply), self.assertRaises(RecoveryError):
                restore_count(reply)


class TransactionTest(Isolated):
    def test_round_trip_restores_and_cleans_up(self):
        d = FakeDevice()
        d.begin()
        self.assertEqual(d.disk, EMPTY_XML)
        d.disk = "<map><int name='level' value='9' /></map>"
        d.system["volume"] = 1
        self.assertTrue(d.end())
        self.assertEqual(parse_prefs(d.disk), parse_prefs(OWNER_XML))
        self.assertEqual(d.system["volume"], 5)
        self.assertIsNone(d.copy)
        self.assertFalse(os.path.exists(self.state))
        self.assertEqual(d.fallbacks, 0)

    def test_an_empty_owner_map_round_trips(self):
        d = FakeDevice(EMPTY_XML)
        d.begin()
        self.assertTrue(d.end())
        self.assertEqual(parse_prefs(d.disk), {})

    def test_an_earlier_copy_blocks_the_run_before_anything_changes(self):
        d = FakeDevice()
        d.copy = ("old-run", EMPTY_XML)
        with self.assertRaises(RecoveryError):
            d.begin()
        self.assertEqual(d.disk, OWNER_XML)

    def test_an_unfinished_run_blocks_the_next(self):
        d = FakeDevice()
        d.begin()
        with self.assertRaises(AdbError):
            d.begin()

    def test_a_stale_copy_of_another_run_is_not_adopted(self):
        d = FakeDevice()
        d.begin()
        d.copy = ("other-run", EMPTY_XML)
        self.assertTrue(d.end())
        self.assertEqual(d.fallbacks, 1)
        self.assertEqual(parse_prefs(d.disk), parse_prefs(OWNER_XML))

    def test_a_failed_restore_write_keeps_everything(self):
        d = FakeDevice()
        d.begin()
        d.replies["restore"] = "FAIL restore write"
        with self.assertRaises(RecoveryError):
            d.end()
        self.assertTrue(os.path.exists(self.state))
        self.assertIsNotNone(d.copy)

    def test_a_readback_mismatch_keeps_everything_and_a_retry_succeeds(self):
        d = FakeDevice()
        d.begin()
        d.replies["restore"] = "OK 7"  # claims success, writes nothing
        with self.assertRaises(RecoveryError):
            d.end()
        self.assertTrue(os.path.exists(self.state))
        self.assertIsNotNone(d.copy)
        del d.replies["restore"]
        self.assertTrue(d.end())
        self.assertEqual(parse_prefs(d.disk), parse_prefs(OWNER_XML))

    def test_a_system_mismatch_keeps_everything(self):
        d = FakeDevice()
        d.begin()
        d.restore_system = lambda state: None
        d.system["zen"] = "2"
        with self.assertRaises(RecoveryError):
            d.end()
        self.assertTrue(os.path.exists(self.state))
        self.assertIsNotNone(d.copy)

    def test_a_lost_drop_reply_is_retried(self):
        d = FakeDevice()
        d.begin()
        d.replies["drop"] = ""
        with self.assertRaises(RecoveryError):
            d.end()
        self.assertTrue(os.path.exists(self.state))
        del d.replies["drop"]
        self.assertTrue(d.end())

    def test_a_missing_app_copy_falls_back_to_the_host_file(self):
        d = FakeDevice()
        d.begin()
        d.copy = None  # dropped, or the run stopped before the app took it
        self.assertTrue(d.end())
        self.assertEqual(d.fallbacks, 1)
        self.assertEqual(parse_prefs(d.disk), parse_prefs(OWNER_XML))

    def test_a_snapshot_count_mismatch_stops_before_the_reset(self):
        d = FakeDevice()
        d.replies["snapshot"] = "OK 2"
        with self.assertRaises(RecoveryError):
            d.begin()
        self.assertEqual(d.disk, OWNER_XML)
        self.assertTrue(os.path.exists(self.state))

    def test_an_interrupted_begin_is_recoverable(self):
        d = FakeDevice()
        d.replies["snapshot"] = ""  # the reply was lost
        with self.assertRaises(RecoveryError):
            d.begin()
        del d.replies["snapshot"]
        self.assertTrue(d.end())
        self.assertEqual(parse_prefs(d.disk), parse_prefs(OWNER_XML))
        self.assertFalse(os.path.exists(self.state))


if __name__ == "__main__":
    unittest.main()
