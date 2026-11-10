#!/usr/bin/env python3
"""pairperf_cpu.py [PREFIX] -- paired blocks: runs named PREFIX-ARM-(up|down)1-BLOCK (default prefix p). Each block holds one run of every arm, so an arm is compared
with the base run of its own block (a shared host drifts by up to 2x over an hour, so absolute cpu-s of different blocks do not compare).
Prints per arm: cpu-s median (min-max) over blocks, the ratio to the block's base (median, min-max), and the JIT and main+workers split."""
import glob, json, os, re, sys, statistics as st, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from perf_cpu import run
prefix = sys.argv[1] if len(sys.argv) > 1 else "p"
blocks = collections.defaultdict(dict)   # (dir, block) -> arm -> dict
for d in sorted(glob.glob(f"runs/{prefix}-*1-*")):
    m = re.match(rf"{prefix}-(.+)-(up|down)1-(\d+)$", os.path.basename(d))
    if not m: continue
    P = json.load(open(f"{d}/passes.json"))
    if not P["passes"]: print("skipped (no pass completed):", d); continue
    P, rows = run(d)
    g = collections.Counter()
    for r in rows: g += r["g"]
    blocks[(m[2], m[3])][m[1]] = dict(total=sum(g.values()), jit=g["JIT C1"] + g["JIT C2"], work=g["main"] + g["workers"], wall=P["passes"][0]["t"] - P["t_launch"], calib=P.get("calib_s"))
def f(v): return f"{st.median(v):.1f} ({min(v):.1f}-{max(v):.1f})"
def fr(v): return f"{st.median(v):.2f} ({min(v):.2f}-{max(v):.2f})"
for dr in ("up", "down"):
    bl = {b: arms for (dd, b), arms in blocks.items() if dd == dr and "base" in arms}
    if not bl: continue
    print(f"### {dr}load, one-shot JVM, 323 files; {len(bl)} blocks; ratio = arm / base of the same block")
    print("| arm | n | total cpu-s | ratio to base (cpu) | wall s | ratio to base (wall) | JIT cpu-s | main+workers cpu-s |")
    print("|---|---|---|---|---|---|---|---|")
    for arm in ["base"] + sorted({k for a in bl.values() for k in a} - {"base"}):
        L = [(b, a[arm], a["base"]) for b, a in bl.items() if arm in a]
        if not L: continue
        print(f"| {arm} | {len(L)} | {f([x[1]['total'] for x in L])} | {fr([x[1]['total']/x[2]['total'] for x in L])} | {f([x[1]['wall'] for x in L])} | {fr([x[1]['wall']/x[2]['wall'] for x in L])} | {f([x[1]['jit'] for x in L])} | {f([x[1]['work'] for x in L])} |")
    print("base cpu-s per block (host-speed drift): " + ", ".join(f"{b}: {a['base']['total']:.0f} (probe {a['base']['calib']:.2f}s)" if a['base'].get('calib') else f"{b}: {a['base']['total']:.0f}" for b, a in sorted(bl.items())))
