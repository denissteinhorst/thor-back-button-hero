# SPDX-License-Identifier: 0BSD
"""Closing must end the original process and task, even if Android preloads a replacement."""

import unittest

from dt.device import Fail
from suites.common import expect_closed


class ObservedDevice:
    def __init__(self, pid='', tasks=(), resumed=None):
        self.current_pid = pid
        self.tasks = tasks
        self.front = resumed or {}

    def pid(self, _pkg):
        return self.current_pid

    def task_ids(self, _pkg):
        return self.tasks

    def resumed(self):
        return self.front

    def eventually(self, predicate, _timeout, message):
        if not predicate():
            raise Fail(message)


class CloseObservationTest(unittest.TestCase):
    def test_no_process_or_task_remains(self):
        expect_closed(ObservedDevice(), 'example.app', '123')

    def test_a_new_background_process_does_not_reopen_the_closed_task(self):
        expect_closed(ObservedDevice(pid='456'), 'example.app', '123')

    def test_the_original_process_must_actually_end(self):
        with self.assertRaises(Fail):
            expect_closed(ObservedDevice(pid='123'), 'example.app', '123')

    def test_a_remaining_task_is_a_failure_even_with_a_new_process(self):
        with self.assertRaises(Fail):
            expect_closed(ObservedDevice(pid='456', tasks=('7',)), 'example.app', '123')

    def test_a_foreground_activity_is_a_failure_even_if_the_recent_query_misses_it(self):
        with self.assertRaises(Fail):
            expect_closed(ObservedDevice(pid='456', resumed={0: 'example.app/.Main'}), 'example.app', '123')

    def test_missing_setup_process_is_a_failure(self):
        with self.assertRaises(Fail):
            expect_closed(ObservedDevice(), 'example.app', '')


if __name__ == '__main__':
    unittest.main()
