#!/usr/bin/env python3
"""perf_series_win.py -- alternating runs of perf_drive_win.py for two builds, so that drift of a noisy host falls on both arms.

usage: perf_series_win.py MODES REPS TAGBASE      (MODES: up | down | up,down)
Environment: PERF_DIR, PERF_DATASET, UNI_JAVA as for perf_drive_win.py, plus UNI_JAR_BASE and UNI_JAR_AFTER (the two shadow jars).
Per repetition and mode it runs base, after, in that order, and writes PERF_DIR/runs/TAGBASE-MODE-ARM-N. Then:
    python perf_table_win.py $PERF_DIR TAGBASE up down"""
import os, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
PERF = os.environ.get("PERF_DIR") or sys.exit("set PERF_DIR")
JARS = {"base": os.environ.get("UNI_JAR_BASE") or sys.exit("set UNI_JAR_BASE"), "after": os.environ.get("UNI_JAR_AFTER") or sys.exit("set UNI_JAR_AFTER")}
TRACE = "org.krost.unidrive.sync.StateDatabase::upsertEntry;org.krost.unidrive.io.FileDigestKt::fileDigestHex"


def main():
    modes, reps, tagbase = sys.argv[1].split(","), int(sys.argv[2]), sys.argv[3]
    for mode in modes:
        for rep in range(1, reps + 1):
            for arm in ("base", "after"):
                tag = f"{tagbase}-{mode}-{arm}-{rep}"
                t = time.time()
                r = subprocess.run([sys.executable, os.path.join(HERE, "perf_drive_win.py"), tag, mode, "--passes", "4", "--trace", TRACE],
                                   env=dict(os.environ, UNI_JAR=JARS[arm]), capture_output=True, text=True)
                print(tag, "rc", r.returncode, f"{time.time() - t:.0f}s", (r.stdout.strip().splitlines() or [""])[-1][:300], flush=True)
                if r.returncode:
                    print(r.stderr[-600:], flush=True)


if __name__ == "__main__":
    main()
