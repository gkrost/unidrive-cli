import contextlib
import pathlib
import runpy
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


BUILD = pathlib.Path(__file__).with_name('build.py')


class BuildTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = pathlib.Path(self.directory.name)
        self.source = self.root / 'site'
        self.source.mkdir()
        (self.source / 'index.html').write_text('<span data-build>development</span>', encoding='utf-8')
        self.enterContext(contextlib.chdir(self.root))

    def build(self, target):
        with patch.object(sys, 'argv', [str(BUILD), str(target)]):
            runpy.run_path(str(BUILD), run_name='__main__')

    def test_nested_output_is_rejected_before_copying(self):
        # Stop an unsafe copy before it can recursively consume disk space.
        with patch('shutil.copytree', side_effect=AssertionError('copy attempted')) as copy:
            with self.assertRaisesRegex(SystemExit, 'outside'):
                self.build('site/../site/output')
            copy.assert_not_called()
        self.assertFalse((self.source / 'output').exists())

    def test_git_failure_does_not_leave_output(self):
        with patch('subprocess.check_output', side_effect=subprocess.CalledProcessError(128, 'git')):
            with self.assertRaises(subprocess.CalledProcessError):
                self.build('output')
        self.assertFalse((self.root / 'output').exists())

    def test_existing_output_is_preserved(self):
        target = self.root / 'output'
        target.mkdir()
        (target / 'keep').write_bytes(b'existing')
        with self.assertRaisesRegex(SystemExit, 'already exists'):
            self.build(target)
        self.assertEqual(b'existing', (target / 'keep').read_bytes())

    def test_build_stamps_pages_and_preserves_static_bytes(self):
        (self.source / '.well-known').mkdir()
        (self.source / '.well-known/security.txt').write_bytes(b'contact\n')
        (self.source / '.nojekyll').touch()
        (self.source / 'install').mkdir()
        key = self.source / 'install/unidrive-releases.gpg'
        key.write_bytes(b'public key\r\n')
        with patch('subprocess.check_output', return_value='abcdef123456\n'):
            self.build('output')
        target = self.root / 'output'
        self.assertIn('abcdef123456 · built ', (target / 'index.html').read_text(encoding='utf-8'))
        self.assertEqual('<span data-build>development</span>', (self.source / 'index.html').read_text(encoding='utf-8'))
        self.assertEqual(key.read_bytes(), (target / 'install/unidrive-releases.gpg').read_bytes())
        self.assertEqual(b'contact\n', (target / '.well-known/security.txt').read_bytes())
        self.assertTrue((target / '.nojekyll').is_file())


if __name__ == '__main__':
    unittest.main()
