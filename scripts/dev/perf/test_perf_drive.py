"""Tests for the watch flow of perf_drive.py: a measured child that exits early must end the plan, not spin (the review of #592).

The flow is a pure function over fakes here — no JVM, no stub, nothing of the Linux-only driver around it. Run:
    python scripts/dev/perf/test_perf_drive.py
"""
import signal
import unittest
from types import SimpleNamespace

import perf_drive


class FakeChild:
    """The bits of Popen the plan touches: poll, returncode, send_signal."""

    def __init__(self, returncode=None, exited=False):
        self.returncode = returncode
        self._exited = exited
        self.signals = []

    def poll(self):
        return self.returncode if self._exited else None

    def send_signal(self, sig):
        self.signals.append(sig)


def idle_pass():
    return dict(t=1.0, down=0, up=0, secs=1.0, line="Sync complete: 0 downloaded, 0 uploaded (1.0s)")


class WatchFlowTest(unittest.TestCase):
    def setUp(self):
        perf_drive.SETTLE_S = 0
        perf_drive.QUIET_S = 0

    def test_exit_before_the_first_completion_ends_the_plan(self):
        child = FakeChild(returncode=1, exited=True)
        passes, lines, marks = [], [(1.0, "JVM terminating in error")], []

        def wait(n):
            return False

        early = perf_drive.watch_flow(SimpleNamespace(passes=4, tail=2), True, child, passes, lines, wait, lambda k: None, marks)

        self.assertTrue(early.startswith("the measured child exited early (rc=1)"), early)
        self.assertIn("the boot pass", early)
        self.assertIn("JVM terminating in error", early, "the output tail is in the failure")
        self.assertEqual([], child.signals, "nothing is signalled after an early exit")
        self.assertEqual([], marks, "no batch is injected after an early exit")

    def test_exit_after_an_idle_pass_ends_the_plan(self):
        # The review's spin: the boot pass is idle, the child exits, and the batch pass p2 never arrives.
        child = FakeChild(returncode=7, exited=True)
        passes, lines, marks = [], [], []
        false_waits = [0]

        def wait(n):
            if n == 1 and not passes:
                passes.append(idle_pass())
                return True
            false_waits[0] += 1
            if false_waits[0] > 5:
                raise AssertionError("the plan kept testing the same idle pass instead of stopping")
            return False

        early = perf_drive.watch_flow(SimpleNamespace(passes=4, tail=2), True, child, passes, lines, wait, lambda k: None, marks)

        self.assertTrue(early.startswith("the measured child exited early (rc=7)"), early)
        self.assertIn("the pass after inject p2", early)
        self.assertEqual(1, false_waits[0], "the plan stops at the first premature exit instead of spinning")
        self.assertEqual([], child.signals)
        self.assertEqual(1, len(marks), "p2 was injected before the plan stopped")

    def test_exit_in_the_idle_tail_ends_the_plan(self):
        child = FakeChild(returncode=3, exited=True)
        passes, lines, marks = [], [], []

        def wait(n):
            if len(passes) < n and len(passes) < 3:
                passes.append(dict(t=1.0, down=5, up=5, secs=1.0, line="Sync complete: 5 downloaded, 5 uploaded (1.0s)"))
                return True
            return False

        early = perf_drive.watch_flow(SimpleNamespace(passes=3, tail=2), True, child, passes, lines, wait, lambda k: None, marks)

        self.assertTrue(early.startswith("the measured child exited early (rc=3)"), early)
        self.assertIn("the idle tail", early)
        self.assertEqual([], child.signals)

    def test_a_plan_that_runs_to_its_end_stops_the_child(self):
        child = FakeChild()
        passes, lines, marks = [], [], []

        def wait(n):
            while len(passes) < n:
                passes.append(dict(t=1.0, down=3, up=3, secs=1.0, line="Sync complete: 3 downloaded, 3 uploaded (1.0s)"))
            return True

        early = perf_drive.watch_flow(SimpleNamespace(passes=3, tail=1), True, child, passes, lines, wait, lambda k: None, marks)

        self.assertIsNone(early)
        self.assertEqual([m[1] for m in marks], ["inject p2", "inject p3"])
        self.assertEqual([signal.SIGTERM], child.signals)


if __name__ == "__main__":
    unittest.main()
