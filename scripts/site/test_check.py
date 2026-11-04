import contextlib
import io
import pathlib
import runpy
import sys
import tempfile
import unittest
from unittest.mock import patch


CHECK = pathlib.Path(__file__).with_name('check.py')

REQUIRED = (
    ('index.html', '<html></html>'),
    ('404.html', '<html></html>'),
    ('install/index.html', '<html></html>'),
    ('install/unidrive-releases.gpg', ''),
    ('.well-known/security.txt', ''),
    ('.nojekyll', ''),
)


class CheckTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = pathlib.Path(self.directory.name)
        (self.root / 'style.css').write_text('body{}', encoding='utf-8')
        for name, body in REQUIRED:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body, encoding='utf-8')
        self.enterContext(contextlib.chdir(self.root))

    def write(self, name, body):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding='utf-8')

    def run_check(self, target='.'):
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            with patch.object(sys, 'argv', [str(CHECK), target]):
                with self.assertRaises(SystemExit) as caught:
                    runpy.run_path(str(CHECK), run_name='__main__')
        return caught.exception.code, stdout.getvalue()

    def run_check_ok(self, target='.'):
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            with patch.object(sys, 'argv', [str(CHECK), target]):
                runpy.run_path(str(CHECK), run_name='__main__')
        return stdout.getvalue()

    def test_root_absolute_internal_link_fails(self):
        # The Pages deploy serves the site under a project subpath, where /style.css
        # resolves outside it; a link must be relative to the page that carries it.
        self.write('index.html', '<link rel="stylesheet" href="/style.css">')
        code, output = self.run_check()
        self.assertEqual(1, code)
        self.assertIn('root-absolute', output)
        self.assertIn('/style.css', output)

    def test_relative_links_to_existing_files_pass(self):
        self.write('index.html', '<link rel="stylesheet" href="style.css"><a href="install/index.html">Install</a>')
        self.write('install/index.html', '<a href="../style.css">s</a>')
        output = self.run_check_ok()
        self.assertIn('Checked', output)

    def test_missing_relative_link_fails(self):
        self.write('index.html', '<a href="gone.html">g</a>')
        code, output = self.run_check()
        self.assertEqual(1, code)
        self.assertIn('missing', output)
