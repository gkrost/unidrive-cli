#!/usr/bin/env python3
"""perf_table_win.py -- medians over the runs of perf_drive_win.py. usage: perf_table_win.py PERF_DIR TAGBASE [MODE ...]
Runs are PERF_DIR/runs/TAGBASE-MODE-ARM-N/result.json (ARM = base | after). Per mode and arm: the cold pass (pass 1) and the warm passes
(2..N, pooled over runs), the median and the range of: engine wall (the "Sync complete" line), process CPU, CPU of the main thread, of the
IO/IO-dispatcher workers, of the JIT, and the time the main thread spent inside the traced methods."""
import json, glob, os, sys, statistics as st

perf, base = sys.argv[1], sys.argv[2]
modes = sys.argv[3:] or ["up", "down"]


def med(xs):
    xs = [x for x in xs if x is not None]
    if not xs: return "-"
    return f"{st.median(xs):.2f} ({min(xs):.2f}-{max(xs):.2f})"


for mode in modes:
    for kind in ("cold", "warm"):
        print(f"\n### {mode} {kind}")
        print("| arm | n | engine wall s | process cpu-s | main cpu-s | workers cpu-s | jit cpu-s | main in upsertEntry s (calls) | main in digest s (calls) | upsertEntry off main s |")
        print("|---|---|---|---|---|---|---|---|---|---|")
        for arm in ("base", "after"):
            rows = []
            calib = []
            for f in sorted(glob.glob(f"{perf}/runs/{base}-{mode}-{arm}-*/result.json")):
                r = json.load(open(f)); calib.append(r.get("calib_s"))
                rows += [p for p in r["passes"] if p["kind"] == kind]
            if not rows: continue
            def tr(p, key, f):
                # the driver keys a call as "<type>.<method>|<thread>" with the type as the JFR names it (slashes)
                hits = [v for k, v in p.get("traced", {}).items() if k.replace("/", ".").endswith("." + key)]
                return sum(h[f] for h in hits)
            up_main = [tr(p, "StateDatabase.upsertEntry|main", "total_s") for p in rows]
            up_calls = [tr(p, "StateDatabase.upsertEntry|main", "calls") for p in rows]
            up_off = [tr(p, "StateDatabase.upsertEntry|other", "total_s") for p in rows]
            dg_main = [tr(p, "FileDigestKt.fileDigestHex|main", "total_s") for p in rows]
            dg_calls = [tr(p, "FileDigestKt.fileDigestHex|main", "calls") for p in rows]
            print(f"| {arm} | {len(rows)} | {med([p['engine_s'] for p in rows])} | {med([p['proc_cpu_s'] for p in rows])} | "
                  f"{med([p['threads_cpu_s'].get('main') for p in rows])} | {med([p['threads_cpu_s'].get('workers') for p in rows])} | "
                  f"{med([p['threads_cpu_s'].get('jit') for p in rows])} | {med(up_main)} ({st.median(up_calls):.0f}) | {med(dg_main)} ({st.median(dg_calls):.0f}) | {med(up_off)} |")
        print("calibration (best-of-3 fixed loop, s) per run: " + ", ".join(
            f"{a}:{[round(json.load(open(f)).get('calib_s') or 0, 3) for f in sorted(glob.glob(f'{perf}/runs/{base}-{mode}-{a}-*/result.json'))]}" for a in ("base", "after")))
