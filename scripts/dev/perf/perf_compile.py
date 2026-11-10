#!/usr/bin/env python3
"""pcperf_cpu.py RUNDIR -- -XX:+PrintCompilation lines in RUNDIR/out.log (stdout of the JVM, timestamped by drive.py), counted per pass window."""
import sys, re, json, collections
d = sys.argv[1]
P = json.load(open(f"{d}/passes.json"))
ends = [x["t"] for x in P["passes"]] + [P["t_exit"] + 5]
def widx(t):
    for i, e in enumerate(ends):
        if t <= e: return i
    return len(ends) - 1
comp = re.compile(r"^\s*(\d+)\s+(\d+)\s+([%sbn! ]{0,5})\s*(\d)\s+(\S+)\s+\((\d+) bytes\)(.*)$")
not_ent = re.compile(r"^\s*(\d+)\s+(\d+)\s+(?:[%sbn! ]{0,5})\s*(\d)?\s*(\S+)\s+\((\d+) bytes\)\s+made not entrant")
tier = collections.defaultdict(collections.Counter); ne = collections.Counter(); ne_t = collections.defaultdict(collections.Counter)
osr = collections.Counter(); meth = collections.defaultdict(collections.Counter)
for ln in open(f"{d}/out.log"):
    t, _, rest = ln.rstrip("\n").partition(" ")
    try: t = float(t)
    except ValueError: continue
    w = widx(t)
    if "made not entrant" in rest:
        m = not_ent.match(rest); ne[w] += 1
        if m: ne_t[w][m[3] or "?"] += 1
        continue
    m = comp.match(rest)
    if m:
        tier[w][m[4]] += 1
        if "%" in m[3]: osr[w] += 1
        meth[w][m[5]] += 1
print(f"## {d}: -XX:+PrintCompilation per pass window (window i = (end of pass i-1, end of pass i], 1 = JVM start .. pass 1)")
print("| window | compilations by tier (1=C1 simple, 2/3=C1 profiled, 4=C2) | OSR | made not entrant (by tier of the made-not-entrant version) | distinct methods compiled | methods compiled >1 time in this window |")
print("|---|---|---|---|---|---|")
for w in range(len(ends)):
    t = tier[w]; n = sum(t.values())
    if not n and not ne[w]: continue
    tt = ", ".join(f"t{k}:{v}" for k, v in sorted(t.items()))
    nn = ", ".join(f"t{k}:{v}" for k, v in sorted(ne_t[w].items()))
    print(f"| {w+1} | {n} ({tt}) | {osr[w]} | {ne[w]} ({nn}) | {len(meth[w])} | {sum(1 for v in meth[w].values() if v>1)} |")
tot = sum(ne.values()); print(f"total made not entrant: {tot}; total compilations: {sum(sum(c.values()) for c in tier.values())}")
