#!/usr/bin/env python3
# SPDX-License-Identifier: 0BSD
"""Runs the device harness's host tests (synthetic state, no adb) for the quality gate.

Fails on any failure and on an empty discovery, so a broken import path cannot pass silently.
"""

import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))


def main() -> int:
    sys.path.insert(0, HERE)
    suite = unittest.defaultTestLoader.discover(os.path.join(HERE, "tests"))
    count = suite.countTestCases()
    if count == 0:
        print("host tests: discovery found no tests", file=sys.stderr)
        return 1
    result = unittest.TextTestRunner(verbosity=1).run(suite)
    print(f"host tests: {result.testsRun} run, {len(result.failures)} failures, {len(result.errors)} errors")
    return 0 if result.wasSuccessful() and result.testsRun == count else 1


if __name__ == "__main__":
    sys.exit(main())
