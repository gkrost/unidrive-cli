#!/usr/bin/env python3
"""perf_cpu.py RUNDIR... -- per-pass CPU by thread group from drive.py output. Window i = (end of pass i-1, end of pass i]; window 1 starts at JVM launch."""
import json, sys, os, collections, re
TCK = os.sysconf("SC_CLK_TCK") if hasattr(os, "sysconf") else 100
def grp(c):
    if c.startswith("C2 Compiler"): return "JIT C2"
    if c.startswith("C1 Compiler"): return "JIT C1"
    if c.startswith("DefaultDispatch"): return "workers"
    if c == "main" or c.startswith("java"): return "main"
    if c.startswith("GC ") or c.startswith("G1 ") or "GC" in c or c.startswith("VM Thread"): return "GC+VMthread"
    return "other"
def cum_at(samples, t):
    best = {}
    for ts, row in samples:
        if ts > t: break
        for tid, (comm, u, s) in row.items(): best[tid] = (comm, u + s)
    g = collections.Counter(); names = collections.Counter()
    for tid, (comm, ticks) in best.items():
        g[grp(comm)] += ticks / TCK
        if grp(comm) == "other": names[re.sub(r"[-#\d]+$", "", comm)] += ticks / TCK
    return g, names, len(best)
def run(d):
    P = json.load(open(f"{d}/passes.json")); S = [(t, r) for t, r in json.load(open(f"{d}/timeline.json"))]
    ends = [x["t"] for x in P["passes"]] + [P["t_exit"]]
    prev_g, prev_t, rows = collections.Counter(), P["t_launch"], []
    for i, e in enumerate(ends):
        g, names, nt = cum_at(S, e + 0.0)
        delta = g - prev_g
        mv = (P["passes"][i]["up"] + P["passes"][i]["down"]) if i < len(P["passes"]) else 0
        rows.append(dict(i=i + 1 if i < len(P["passes"]) else "exit", wall=e - prev_t, files=mv, g=delta, total=sum(delta.values()), threads=nt))
        prev_g, prev_t = g, e
    return P, rows
GR = ["JIT C2", "JIT C1", "main", "workers", "GC+VMthread", "other"]
if __name__ == "__main__" and sys.argv[1] != "--stages":
    for d in sys.argv[1:]:
        P, rows = run(d)
        print(f"### {d}  opts={P['opts']!r}  cmd={P['cmd'].split('sync')[1]}")
        print("| pass | files | wall s | cpu-s | cpu-s/file | " + " | ".join(GR) + " |")
        print("|---|---|---|---|---|" + "---|" * len(GR))
        for r in rows:
            pf = f"{r['total']/r['files']:.2f}" if r["files"] else "-"
            print(f"| {r['i']} | {r['files']} | {r['wall']:.1f} | {r['total']:.1f} | {pf} | " + " | ".join(f"{r['g'][k]:.1f}" for k in GR) + " |")

# ---- stages: python3 perf_cpu.py --stages RUNDIR... : CPU of each phase of a pass, cut at progress lines of the sync output
STAGES = [("start/boot", None), ("scan remote", r"^Scanning remote changes"), ("scan local", r"^Scanning local files"),
          ("reconcile", r"^Reconcil"), ("transfers", r"^\[1/"), ("tail", r"^Sync complete")]
def stages(d):
    P = json.load(open(f"{d}/passes.json")); S = json.load(open(f"{d}/timeline.json"))
    L = []
    for ln in open(f"{d}/out.log"):
        t, _, rest = ln.rstrip("\n").partition(" ")
        try: L.append((float(t), rest))
        except ValueError: pass
    ends = [x["t"] for x in P["passes"]]
    prev = P["t_launch"]; out = []
    for i, e in enumerate(ends):
        marks = [(prev, "start/boot")]
        seen = set()
        for t, rest in L:
            if t <= prev or t > e: continue
            for name, rx in STAGES[1:]:
                if name not in seen and re.match(rx, rest): seen.add(name); marks.append((t, name))
        marks.append((e, "end"))
        marks.sort()
        rows = []
        for (t0, n0), (t1, _) in zip(marks, marks[1:]):
            g0, _, _ = cum_at(S, t0); g1, _, _ = cum_at(S, t1)
            rows.append((n0, t1 - t0, g1 - g0))
        out.append((i + 1, rows)); prev = e
    return out
if __name__ == "__main__" and False: pass

if __name__ == "__main__" and sys.argv[1] == "--stages":
    for d in sys.argv[2:]:
        print(f"### {d}: CPU (cpu-s) per stage of each pass, main / workers / JIT(C1+C2) / total")
        print("| pass | stage | wall s | main | workers | JIT | total |")
        print("|---|---|---|---|---|---|---|")
        for i, rows in stages(d):
            for n, w, g in rows:
                tot = sum(g.values())
                if w < 0.05 and tot < 0.1: continue
                print(f"| {i} | {n} | {w:.1f} | {g['main']:.1f} | {g['workers']:.1f} | {g['JIT C1']+g['JIT C2']:.1f} | {tot:.1f} |")
