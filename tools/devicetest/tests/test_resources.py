# SPDX-License-Identifier: 0BSD
"""Resource readings group what varies by accident (thread numbers, pool sizes) and keep what matters;
a missing reading, an unfinished leak analysis or a skipped workload cycle never passes."""

import unittest

import resources as r

LEAK_LOG = """\
11-22 17:21:23.203 1 1 D LeakCanary: Watching instance of dev.steinhorst.backbuttonhero.SwitcherService (x) with key a
11-22 17:21:30.203 1 1 D LeakCanary: Watching instance of dev.steinhorst.backbuttonhero.SwitcherService (x) with key b
11-22 17:22:00.000 1 2 D LeakCanary: HEAP ANALYSIS RESULT
11-22 17:22:00.000 1 2 D LeakCanary: 1 APPLICATION LEAKS
11-22 17:22:00.000 1 2 D LeakCanary: Signature: bd0178976084c8549ea1a5e0417e0d6ffe34eaa3
11-22 17:22:00.000 1 2 D LeakCanary: ┬───
11-22 17:22:00.000 1 2 D LeakCanary: │ GC Root: Global variable in native code
11-22 17:22:00.000 1 2 D LeakCanary: ├─ android.accessibilityservice.AccessibilityService$IAccessibilityServiceClientWrapper instance
11-22 17:22:00.000 1 2 D LeakCanary: │    ↓ AccessibilityService$IAccessibilityServiceClientWrapper.mContext
11-22 17:22:00.000 1 2 D LeakCanary: ╰→ dev.steinhorst.backbuttonhero.SwitcherService instance
11-22 17:22:00.000 1 2 D LeakCanary: 0 LIBRARY LEAKS
"""


def sample(pss=60000, threads=None, cycles=r.CYCLES):
    return {"memory": {"pss_kb": pss, "java_kb": 4000, "native_kb": 12000, "graphics_kb": 17000},
            "threads": threads or {"PadHelper": 1, "binder": 5, "pool-thread": 2}, "fds": {"socket": 7, "file": 80},
            "cycles": cycles}


def measured(samples):
    metrics = {}
    for key in ("pss_kb", "java_kb", "native_kb", "graphics_kb"):
        metrics[key] = r.summarize([s["memory"][key] for s in samples])
    metrics["threads"] = r.summarize([r.own_threads(s) for s in samples])
    metrics["fds"] = r.summarize([sum(s["fds"].values()) for s in samples])
    return {"samples": samples, "metrics": metrics}


class ParseTest(unittest.TestCase):
    def test_thread_numbers_and_cut_names_fold_into_roles(self):
        self.assertEqual(r.thread_role("binder:17015_4"), "binder")
        self.assertEqual(r.thread_role("pool-2-thread-1"), "pool-thread")
        self.assertEqual(r.thread_role("pool-32-thread-"), "pool-thread")
        self.assertEqual(r.thread_role("RenderThread"), "RenderThread")
        self.assertEqual(r.thread_role("Runtime worker 3"), "Runtime worker")
        self.assertEqual(r.roles(["binder:1_1", "binder:1_2", "PadHelper", ""]), {"PadHelper": 1, "binder": 2})

    def test_descriptors_are_counted_by_kind(self):
        ls = ["total 0", "lrwx 1 root root 64 x 3 -> socket:[7374904]", "lr-x 1 root root 64 x 4 -> anon_inode:inotify",
              "l-wx 1 root root 64 x 5 -> /dev/uinput", "lr-x 1 root root 64 x 8 -> /dev/input/event9 (deleted)",
              "lr-x 1 root root 64 x 9 -> /data/app/base.apk", "lr-x 1 root root 64 x 0 -> /dev/null"]
        self.assertEqual(r.fds(ls), {"anon_inode:inotify": 1, "device": 1, "file": 1, "input": 1, "socket": 1, "uinput": 1})

    def test_meminfo_needs_every_figure(self):
        text = ("  Java Heap:     2932      26156\n Native Heap:  1916   6796\n    Graphics:  0   0\n"
                "  TOTAL PSS:    21859   TOTAL RSS: 110792\n")
        self.assertEqual(r.meminfo(text), {"java_kb": 2932, "native_kb": 1916, "graphics_kb": 0, "pss_kb": 21859})
        with self.assertRaises(ValueError):
            r.meminfo(text.replace("Graphics:", "Graph:"))

    def test_leak_analysis_reads_watches_counts_path_and_signature(self):
        a = r.leak_analysis(LEAK_LOG)
        self.assertEqual(a["watched_services"], 2)
        self.assertTrue(a["analysis_complete"])
        self.assertEqual((a["application_leaks"], a["library_leaks"]), (1, 0))
        self.assertEqual(a["paths"], [["android.accessibilityservice.AccessibilityService$IAccessibilityServiceClientWrapper",
                                       "dev.steinhorst.backbuttonhero.SwitcherService"]])
        self.assertEqual(a["signatures"], ["bd0178976084c8549ea1a5e0417e0d6ffe34eaa3"])

    def test_no_log_is_no_analysis(self):
        a = r.leak_analysis("")
        self.assertFalse(a["analysis_complete"])
        self.assertIsNone(a["application_leaks"])


class CompareTest(unittest.TestCase):
    def setUp(self):
        self.base = measured([sample(60000 + i * 500) for i in range(5)])

    def test_the_baseline_against_itself_passes(self):
        self.assertEqual(r.compare(self.base, self.base)[0], [])

    def test_memory_beyond_budget_fails(self):
        cand = measured([sample(60000 + 4000 + i * 500) for i in range(5)])
        failures, _ = r.compare(self.base, cand)
        self.assertEqual(len(failures), 1)
        self.assertIn("pss_kb", failures[0])

    def test_less_is_never_a_failure(self):
        self.assertEqual(r.compare(self.base, measured([sample(50000) for _ in range(5)]))[0], [])

    def test_an_incomplete_workload_fails(self):
        cand = measured([sample(cycles=9)] + [sample() for _ in range(4)])
        self.assertTrue(any("cycle" in f for f in r.compare(self.base, cand)[0]))

    def test_a_new_thread_role_fails(self):
        cand = measured([sample(threads={"PadHelper": 1, "binder": 5, "pool-thread": 2, "Leaky": 1}) for _ in range(5)])
        self.assertTrue(any("Leaky" in f for f in r.compare(self.base, cand)[0]))

    def test_framework_pools_do_not_count_as_own_threads(self):
        self.assertEqual(r.own_threads(sample(threads={"binder": 13, "hwuiTask": 2, "PadHelper": 1})), 1)


class LifecycleTest(unittest.TestCase):
    def states(self, **changes):
        on = {"threads": {"PadHelper": 1, "pool-thread": 2}, "fds": {"file": 90}, "helper": {"pid": "9"}, "helpers_running": 1}
        off = {"threads": {"pool-thread": 1}, "fds": {"file": 80}, "helper": {"pid": ""}, "helpers_running": 0}
        s = {"bound": on, "disabled": [off] * 5, "enabled": [on] * 5, "after_death": on,
             "leaks": {"watched_services": 5, "analysis_complete": True}}
        s.update(changes)
        return s

    def test_a_clean_lifecycle_passes(self):
        self.assertEqual(r.lifecycle_findings(self.states()), [])

    def test_a_helper_surviving_the_disabled_service_fails(self):
        off = {"threads": {"PadHelper": 1}, "fds": {}, "helper": {"pid": "9"}, "helpers_running": 1}
        self.assertEqual(len(r.lifecycle_findings(self.states(disabled=[off] + [self.states()["disabled"][0]] * 4))), 2)

    def test_two_helpers_fail(self):
        two = dict(self.states()["bound"], helpers_running=2)
        self.assertTrue(r.lifecycle_findings(self.states(after_death=two)))

    def test_growing_descriptors_fail(self):
        on = self.states()["bound"]
        grown = [dict(on, fds={"file": 90 + i}) for i in range(5)]
        self.assertTrue(any("grew" in f for f in r.lifecycle_findings(self.states(enabled=grown))))

    def test_unobserved_leaks_fail(self):
        self.assertEqual(len(r.lifecycle_findings(self.states(leaks={"watched_services": 3, "analysis_complete": False}))), 2)


if __name__ == "__main__":
    unittest.main()


class LatencyTest(unittest.TestCase):
    STATS = """\
---PROFILEDATA---
Flags,FrameTimelineVsyncId,IntendedVsync,Vsync,HandleInputStart,FrameCompleted,
0,1,1000000000,1000000000,0,1020000000,
0,2,900000000,900000000,0,905000000,
---PROFILEDATA---
Window: overlay
---PROFILEDATA---
Flags,FrameTimelineVsyncId,IntendedVsync,Vsync,HandleInputStart,FrameCompleted,
0,3,1100000000,1100000000,0,1150000000,
---PROFILEDATA---
"""

    def test_frames_of_every_window_are_read(self):
        self.assertEqual(r.frames(self.STATS), [(1000000000, 1020000000), (900000000, 905000000), (1100000000, 1150000000)])

    def test_latency_counts_from_the_stamp_to_the_first_frame_begun_after_it(self):
        self.assertEqual(r.first_frame_latency((950000000, 955000000), r.frames(self.STATS)), 70.0)

    def test_no_frame_after_the_trigger_is_missing_not_fast(self):
        self.assertIsNone(r.first_frame_latency((2000000000, 2000000001), r.frames(self.STATS)))

    def test_stamps_must_both_be_reported(self):
        self.assertEqual(r.timer_stamps("T0 now at 5 nsecs\nT1 now at 9 nsecs\nDONE"), (5, 9))
        with self.assertRaises(ValueError):
            r.timer_stamps("T0 now at 5 nsecs\nDONE")


class LatencyCompareTest(unittest.TestCase):
    BASE = {"summary": {k: {"median": 244, "p90": 255, "mad": 5, "missing": 0} for k in ("cold", "warm")}}

    def test_itself_passes(self):
        self.assertEqual(r.latency_compare(self.BASE, self.BASE), [])

    def test_a_slower_median_fails_and_missing_frames_fail(self):
        slow = {"summary": dict(self.BASE["summary"], warm={"median": 270, "p90": 255, "mad": 5, "missing": 1})}
        self.assertEqual(len(r.latency_compare(self.BASE, slow)), 2)
