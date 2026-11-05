#!/usr/bin/env python3
"""Summarize DEBUG file_event records from explicitly supplied engine logs."""

import argparse
import json
import re
import sys
from collections import defaultdict


TOKEN = re.compile(r'([a-z_]+)=("(?:[^"\\]|\\.)*"|[^\s"]+)(?:\s+|$)')
MARKER = re.compile(r'(?:^|\s)file_event (?=phase=)')


def parse(line):
    marker = MARKER.search(line)
    if marker is None:
        return None
    text = line[marker.end():].rstrip()
    fields = {}
    pos = 0
    while pos < len(text):
        match = TOKEN.match(text, pos)
        if not match:
            raise ValueError("malformed file_event fields")
        key, value = match.groups()
        if key in fields:
            raise ValueError("duplicate field")
        fields[key] = json.loads(value) if value.startswith('"') else value
        pos = match.end()
    if not fields.get("phase") or "path" not in fields:
        raise ValueError("missing phase or path")
    for key in ("bytes", "ms", "retries"):
        if key in fields:
            fields[key] = int(fields[key])
            if fields[key] < 0:
                raise ValueError("negative measurement")
    return fields


def summarize(events, per_file=False):
    totals = defaultdict(lambda: dict(events=0, bytes=0, byte_samples=0,
                                     duration_ms=0, duration_samples=0, recovered_retries=0))
    for event in events:
        key = (event["phase"], event["path"] if per_file else None)
        row = totals[key]
        row["events"] += 1
        for field, total, samples in (("bytes", "bytes", "byte_samples"), ("ms", "duration_ms", "duration_samples")):
            if field in event:
                row[total] += event[field]
                row[samples] += 1
        if event["phase"] in ("upload_done", "download_done"):
            row["recovered_retries"] += event.get("retries", 0)
    return [dict(phase=phase, **({"path": path} if per_file else {}), **row)
            for (phase, path), row in sorted(totals.items())]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("logs", nargs="+", help="log files from one run (include rotated files as needed)")
    parser.add_argument("--per-file", action="store_true")
    args = parser.parse_args()

    def events():
        for name in args.logs:
            with open(name, encoding="utf-8-sig") as log:
                for number, line in enumerate(log, 1):
                    try:
                        event = parse(line)
                    except ValueError as error:
                        raise ValueError(f"{name}:{number}: {error}") from error
                    if event is not None:
                        yield event

    try:
        rows = summarize(events(), args.per_file)
        if not rows:
            raise ValueError("no DEBUG file_event records found")
    except (OSError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    print(json.dumps(rows, indent=2, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
