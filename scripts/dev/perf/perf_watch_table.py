#!/usr/bin/env python3
"""perf_watch_table.py -- watch-mode runs: cold pass (1) against the warm data passes and the idle passes, per thread group, with the user/system split."""
import glob, os, re, sys, json, statistics as st, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from perf_cpu import run, GR, grp
TCK = os.sysconf("SC_CLK_TCK") if hasattr(os, "sysconf") else 100
def usplit(d):
    """per pass window: (user cpu-s, system cpu-s) of the whole process"""
    P = json.load(open(f"{d}/passes.json")); S = json.load(open(f"{d}/timeline.json"))
    ends = [x["t"] for x in P["passes"]]
    out = []; prev = (0.0, 0.0)
    for e in ends:
        best = {}
        for ts, row in S:
            if ts > e: break
            for tid, (comm, u, s) in row.items(): best[tid] = (u, s)
        cur = (sum(v[0] for v in best.values()) / TCK, sum(v[1] for v in best.values()) / TCK)
        out.append((cur[0] - prev[0], cur[1] - prev[1])); prev = cur
    return out
def med(v): return f"{st.median(v):.1f}" + (f" ({min(v):.1f}-{max(v):.1f})" if len(v) > 1 else "")
tags = sorted({re.sub(r"-\d+$", "", os.path.basename(d)) for d in glob.glob("runs/*w-[0-9]*") + glob.glob("runs/*w")}) if len(sys.argv) < 2 else sys.argv[1:]
print("| arm | dir | runs | pass kind | n | files | wall s | cpu-s | cpu-s/file | JIT C2 | JIT C1 | main | workers | GC+VM | other | user | sys |")
print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
for tag in tags:
    m = re.match(r"(.+)(up|down)w$", tag)
    if not m: continue
    runs = sorted(glob.glob(f"runs/{tag}-[0-9]*"))
    if not runs: continue
    kinds = collections.defaultdict(list)   # kind -> list of (wall, files, total, g, user, sys)
    for d in runs:
        P, rows = run(d); us = usplit(d)
        dat = 0
        for i, r in enumerate(rows[:-1]):
            if i == 0: k = "1 cold (boot pass)"
            elif r["files"] > 0: dat += 1; k = "warm data pass" if dat > 1 else "warm data pass"
            else: k = "idle poll (no change)"
            if k == "warm data pass" and dat == 1: k = "warm 1 (first after cold)"
            elif k == "warm data pass": k = "warm 2+"
            if i > 0 and r["files"] == 0 and r["wall"] < 3 and i <= 2 and dat == 0: k = "idle poll right after the cold pass"
            kinds[k].append((r["wall"], r["files"], r["total"], r["g"], us[i][0], us[i][1]))
    order = ["1 cold (boot pass)", "idle poll right after the cold pass", "warm 1 (first after cold)", "warm 2+", "idle poll (no change)"]
    for k in order:
        L = kinds.get(k)
        if not L: continue
        files = st.median([x[1] for x in L])
        print(f"| {m[1].strip('-')} | {m[2]} | {len(runs)} | {k} | {len(L)} | {files:.0f} | {med([x[0] for x in L])} | {med([x[2] for x in L])} | " +
              (f"{st.median([x[2] for x in L])/files:.3f}" if files else "-") + " | " + " | ".join(med([x[3][g] for x in L]) for g in ["JIT C2", "JIT C1", "main", "workers", "GC+VMthread", "other"]) +
              f" | {med([x[4] for x in L])} | {med([x[5] for x in L])} |")
