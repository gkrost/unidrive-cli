import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("file_events", Path(__file__).with_name("file-events.py"))
events = importlib.util.module_from_spec(spec)
spec.loader.exec_module(events)


class FileEventsTest(unittest.TestCase):
    def test_escaped_paths_and_prefix(self):
        record = events.parse(r'12:34 DEBUG [scan] file_event phase=download_done path="/a b\\c\n\"d" bytes=7 ms=2 retries=0')
        self.assertEqual('/a b\\c\n"d', record["path"])
        self.assertEqual(7, record["bytes"])

    def test_progress_and_summaries_are_not_counted(self):
        self.assertIsNone(events.parse('file_trace phase=upload_progress path="/a" bytes=7'))
        self.assertIsNone(events.parse('file_summary scan=x upload_bytes=7'))

    def test_phases_and_unknown_bytes_remain_separate(self):
        records = [events.parse(line) for line in [
            'file_event phase=download_failed path="/a" ms=2 retries=1',
            'file_event phase=download_done path="/a" bytes=7 ms=3 retries=1',
            'file_event phase=hydrate_served path="/a" bytes=7 ms=5 cache_hit=false',
            'file_event phase=hydrate_served path="/a" bytes=7 ms=1 cache_hit=true',
        ]]
        rows = {row['phase']: row for row in events.summarize(records)}
        self.assertEqual(7, rows['download_done']['bytes'])
        self.assertEqual(14, rows['hydrate_served']['bytes'])
        self.assertEqual(0, rows['download_failed']['byte_samples'])
        self.assertEqual(1, rows['download_done']['recovered_retries'])
        self.assertEqual(0, rows['download_failed']['recovered_retries'])
        self.assertEqual('/a', events.summarize(records, per_file=True)[0]['path'])

    def test_malformed_record_is_not_silently_dropped(self):
        for line in ['file_event phase=x path="unterminated',
                     'file_event phase=x path="/a" bytes=-1',
                     'file_event phase=x path="/a" bytes=1 bytes=2']:
            with self.subTest(line=line), self.assertRaises(ValueError):
                events.parse(line)


if __name__ == '__main__':
    unittest.main()
