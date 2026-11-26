"""Tests of golden.py's run wait and report, with a fake daemon view: no daemon, mount or account.

  python3 -I -m unittest discover -s scripts/golden/unicode-tree -p 'test_*.py' -v
"""
import importlib.util
import os
import select
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('golden', HERE / 'golden.py')
golden = importlib.util.module_from_spec(spec)
spec.loader.exec_module(golden)

PREFIX = '/_INBOX/golden-unicode-v2'
MANIFEST = '#manifest\nD\t-\t-\t-\tboth\td\td\nF\t0\t-\t-\tboth\td/empty.bin\td/empty.bin\nF\t3\t-\t-\tboth\td/ok.bin\td/ok.bin\n'


class FakeIpc:
    """hydration.list over a fixed set of rows: path -> flags."""
    def __init__(self, rows):
        self.rows = rows

    def request(self, req):
        p = req['prefix']
        out = []
        for path, e in self.rows.items():
            if path.rsplit('/', 1)[0] == p:
                out.append(dict(path=path, **e))
        return {'ok': True, 'entries': out}


def rows(**over):
    base = {PREFIX + '/d': {'folder': True},
            PREFIX + '/d/empty.bin': {'size': 0, 'remote_id': 'r1'},
            PREFIX + '/d/ok.bin': {'size': 3, 'remote_id': 'r2'}}
    base.update(over)
    return base


class WaitTest(unittest.TestCase):
    def run_wait(self, row_set):
        """(findings or None if the wait went to sleep, number of sleeps)"""
        class Slept(Exception):
            pass

        def sleep(_):
            raise Slept()
        with tempfile.TemporaryDirectory() as t:
            man = Path(t) / 'manifest.tsv'
            man.write_text(MANIFEST, encoding='utf-8')
            with mock.patch.object(golden.time, 'sleep', side_effect=sleep):
                try:
                    return golden.daemon_findings(FakeIpc(row_set), PREFIX, man, watch=True, timeout=600, log=lambda m: None), 0
                except Slept:
                    return None, 1

    def test_failed_row_ends_the_wait_without_sleeping(self):
        failed = rows(**{PREFIX + '/d/empty.bin': {'size': 0, 'error': 'plan_limit', 'pending_upload': True}})
        f, slept = self.run_wait(failed)
        self.assertEqual(0, slept, 'the wait slept although the only unfinished row had failed')
        self.assertIn(f'ERROR    {PREFIX}/d/empty.bin', f)

    def test_pending_row_still_waits(self):
        pending = rows(**{PREFIX + '/d/ok.bin': {'size': 3, 'pending_upload': True}})
        f, slept = self.run_wait(pending)
        self.assertEqual(1, slept, 'a row that is still pending must keep the wait going')


class VerdictTest(unittest.TestCase):
    def test_failed_rows_are_reported_failed_not_settled(self):
        label, code = golden.upload_verdict(['ERROR    a', 'ERROR    b'], classified=False)
        self.assertEqual('FAILED 2', label)
        self.assertEqual(1, code)

    def test_failed_rows_with_expected_file_leave_the_exit_to_compare(self):
        self.assertEqual(('FAILED 1', 0), golden.upload_verdict(['ERROR    a'], classified=True))

    def test_exit_2_only_for_pending_rows(self):
        self.assertEqual(('NOT SETTLED (1 pending), FAILED 1', 2), golden.upload_verdict(['PENDING  a', 'ERROR    b'], classified=True))
        self.assertEqual(('settled', 0), golden.upload_verdict(['SIZE     a: expected 1, daemon says 2'], classified=False))


class OutputTest(unittest.TestCase):
    def test_progress_line_is_visible_before_the_process_ends(self):
        child = ('import sys; sys.path.insert(0, %r); import golden; golden.cmd_generate = lambda a: (print("step 1"), sys.stdin.read())[1]; '
                 'sys.argv = ["golden", "generate"]; golden.main()' % str(HERE))
        env = {k: v for k, v in os.environ.items() if k != 'PYTHONUNBUFFERED'}
        p = subprocess.Popen([sys.executable, '-c', child], stdin=subprocess.PIPE, stdout=subprocess.PIPE, env=env, text=True)
        try:
            ready, _, _ = select.select([p.stdout], [], [], 10)
            self.assertTrue(ready, 'stdout is block-buffered: "step 1" did not arrive while the process was running')
            self.assertEqual('step 1\n', p.stdout.readline())
        finally:
            p.stdin.close()
            p.wait(timeout=10)


if __name__ == '__main__':
    unittest.main()
