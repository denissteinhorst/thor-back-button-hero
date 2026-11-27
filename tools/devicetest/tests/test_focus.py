# SPDX-License-Identifier: 0BSD
"""The focused-screen tests read Android's input display exactly as the app does."""

import unittest

from dt.device import focused_input_display


class FocusedInputDisplayTest(unittest.TestCase):
    def test_the_display_id_of_the_line(self):
        self.assertEqual(focused_input_display("  FocusedDisplayId: 4"), 4)
        self.assertEqual(focused_input_display("FocusedDisplayId: 0\n"), 0)

    def test_anything_else_is_unknown(self):
        for output in (None, "", "garbage", "  FocusedDisplayId: -1", "  FocusedDisplayId: 4 extra"):
            with self.subTest(output=output):
                self.assertIsNone(focused_input_display(output))
