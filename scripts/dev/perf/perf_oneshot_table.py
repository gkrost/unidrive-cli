#!/usr/bin/env python3
"""perf_oneshot_table.py -- tables over all runs in runs/: one-shot arms (median/min/max of n) and warm-pass summaries of watch runs."""
import glob, os, re, sys, statistics as st, json, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from perf_cpu import run, GR
def pass_wall(d):   # wall from JVM launch to the end of the single pass, and to exit
    P = json.load(open(f"{d}/passes.json")); return P["passes"][0]["t"] - P["t_launch"], P["t_exit"] - P["t_launch"], P["passes"][0]["secs"]
arms = collections.defaultdict(list)
SKIP = {"norid-up1-2", "rid-down1-2"}   # overlapped a side measurement on the host; re-run as ...-2b
for d in sorted(glob.glob("runs/*1-[0-9]*")):
    tag = os.path.basename(d); m = re.match(r"(.+)-(up|down|ls)1-(\d+b?)$", tag)
    if tag in SKIP: continue
    if m and os.path.exists(f"{d}/passes.json"): arms[(m[1], m[2])].append(d)
def f(v): return f"{st.median(v):.1f} ({min(v):.1f}-{max(v):.1f})" if len(v) > 1 else f"{v[0]:.1f}"
print("## one-shot JVM, 323 files, cpu-s median (min-max) over n runs; wall = JVM launch to the 'Sync complete' line")
print("| arm | dir | n | wall s | total cpu-s | JIT C2 | JIT C1 | main | workers | GC+VM | other |")
print("|---|---|---|---|---|---|---|---|---|---|---|")
for (arm, dr), ds in sorted(arms.items(), key=lambda kv: (kv[0][1], kv[0][0] != "base", kv[0][0])):
    rows = []
    for d in ds:
        P, r = run(d); x = r[0]; tot_all = x["total"] + (r[1]["total"] if len(r) > 1 else 0)
        rows.append((pass_wall(d)[0], tot_all, [x["g"][k] for k in GR]))
    cols = [f([r[2][i] for r in rows]) for i in range(len(GR))]
    print(f"| {arm} | {dr} | {len(ds)} | {f([r[0] for r in rows])} | {f([r[1] for r in rows])} | " + " | ".join(cols) + " |")

from perf_cpu import stages
print("\n## the same one-shot arms cut at the first 'Scanning remote' line: boot = JVM launch to there; rest = scan + reconcile + 323 transfers (median, min-max)")
print("| arm | dir | boot wall s | boot cpu-s | boot main | boot JIT | rest wall s | rest cpu-s | rest main | rest workers | rest JIT |")
print("|---|---|---|---|---|---|---|---|---|---|---|")
for (arm, dr), ds in sorted(arms.items(), key=lambda kv: (kv[0][1], kv[0][0] != "base", kv[0][0])):
    if dr == "ls": continue
    B = []; R = []
    for d in ds:
        rows = stages(d)[0][1]
        b = rows[0]; rest = rows[1:]
        rw = sum(r[1] for r in rest); rg = collections.Counter()
        for r in rest: rg += r[2]
        B.append((b[1], sum(b[2].values()), b[2]["main"], b[2]["JIT C1"] + b[2]["JIT C2"]))
        R.append((rw, sum(rg.values()), rg["main"], rg["workers"], rg["JIT C1"] + rg["JIT C2"]))
    print(f"| {arm} | {dr} | " + " | ".join(f([b[i] for b in B]) for i in range(4)) + " | " + " | ".join(f([r[i] for r in R]) for i in range(5)) + " |")
