#!/usr/bin/env python3
"""jfrperf_cpu.py RUNDIR FILE.jfr -- frames of a drive.py run from its JFR file (JDK `jfr print`, nothing else).
CPUTimeSample = Java threads only (the JIT threads are native: their cost is the jdk.Compilation durations, reported separately)."""
import sys, re, json, subprocess, os, time, collections, calendar
JFR = os.environ.get("JFR") or "jfr"   # the `jfr` tool of the JDK that wrote the recording (or a newer one)
d, f = sys.argv[1], sys.argv[2]
P = json.load(open(f"{d}/passes.json"))
ends = [x["t"] for x in P["passes"]] + [P["t_exit"] + 5]
def widx(t):
    for i, e in enumerate(ends):
        if t <= e: return i
    return len(ends) - 1
def pt(s):  # "15:55:26.401 (2026-11-09)"
    m = re.match(r"(\d\d):(\d\d):(\d\d)\.(\d+) \((\d+)-(\d+)-(\d+)\)", s)
    h, mi, se, ms_, Y, M, D = m.groups()
    return calendar.timegm((int(Y), int(M), int(D), int(h), int(mi), int(se))) + int(ms_) / 1000.0
def events(name, depth):
    out = subprocess.run([JFR, "print", "--events", name, "--stack-depth", str(depth), f], capture_output=True, text=True).stdout
    for blk in out.split("\n}\n"):
        if not blk.strip(): continue
        ev = {}
        m = re.search(r"startTime = (.*)", blk); ev["t"] = pt(m[1]) if m else 0
        for k in ("eventThread", "method", "compiler", "duration", "compileLevel", "isOsr", "codeSize", "reason", "action", "samplingPeriod", "succeded", "thrownClass"):
            m = re.search(rf"^\s+{k} = (.*)$", blk, re.M)
            if m: ev[k] = m[1].strip()
        for k in ("compiler",):
            if k in ev: ev[k] = ev[k].strip(chr(34))
        if "eventThread" in ev: ev["eventThread"] = re.match(r'"(.*?)"', ev["eventThread"])[1]
        m = re.search(r"stackTrace = \[\n(.*?)\n\s*\]", blk, re.S)
        ev["st"] = [l.strip() for l in m[1].split("\n") if l.strip() and l.strip() != "..."] if m else []
        yield ev
def grp(c):
    if c.startswith("DefaultDispatch"): return "workers"
    if c == "main": return "main"
    return "other:" + re.sub(r"[-#\d]+$", "", c)
COMP = [("class loading/linking", r"jdk\.internal\.loader|ClassLoader\.(load|define)|java\.lang\.invoke\.|ensureClassInitialized|sun\.reflect|java\.lang\.reflect"),
        ("SQLite / StateDatabase", r"org\.sqlite|StateDatabase|java\.sql"),
        ("SigV4 / crypto / digest", r"Sigv4|SigV4|AwsSigner|javax\.crypto|java\.security\.MessageDigest|sun\.security|HashVerifier|MessageDigest"),
        ("RequestId plugin / redaction", r"RequestId|UrlRedaction"),
        ("S3 XML / java.time parsing", r"S3Api\.(parse|xml|list)|javax\.xml|com\.sun\.org\.apache|java\.time\.|DateTimeFormatter"),
        ("logging (logback/slf4j)", r"ch\.qos\.logback|org\.slf4j|MDC"),
        ("Ktor client", r"io\.ktor"),
        ("local scan / file IO", r"LocalScanner|java\.nio\.file|sun\.nio\.fs|java\.io\.File|Files\.|FileChannel|FileInputStream|sun\.nio\.ch\.FileChannelImpl"),
        ("reconcile / engine", r"Reconciler|SyncEngine|RemoteEnumeration|Transfers|ActionPlan"),
        ("CLI progress / console", r"CliProgressReporter|ProgressReporter|System\.out|PrintStream|AnsiHelper|IpcServer|ipcReporter"),
        ("kotlinx.coroutines machinery", r"kotlinx\.coroutines|kotlin\.coroutines"),
        ]
CRE = [(n, re.compile(r)) for n, r in COMP]
def bucket(st):
    for fr in st:           # leaf first
        for n, r in CRE[:-1]:
            if r.search(fr): return n
    for fr in st:
        if CRE[-1][1].search(fr): return CRE[-1][0]
    return "(none matched)"
def pkgframe(fr): return re.sub(r"\(.*", "", fr)

S = list(events("jdk.CPUTimeSample", 64))
print(f"## {d}: {len(S)} CPUTimeSample events (period 10 ms; jdk.CPUTimeSamplesLost not added back, read shares not seconds)")
tab = collections.defaultdict(collections.Counter)
for e in S: tab[widx(e["t"])][grp(e["eventThread"])] += 1
print("samples per pass window and Java thread group:")
for i in sorted(tab): print(f"  window {i+1}: " + ", ".join(f"{k} {v}" for k, v in tab[i].most_common(5)))
def sel(g, win=None): return [e for e in S if grp(e["eventThread"]) == g and (win is None or widx(e["t"]) == win)]
nw = len(ends)
for g in ("main", "workers"):
    for win in [None] + list(range(nw)):
        ev = sel(g, win)
        if len(ev) < 20: continue
        title = "all passes" if win is None else f"pass window {win+1}"
        print(f"\n### {g}, {title}: {len(ev)} samples")
        b = collections.Counter(bucket(e["st"]) for e in ev)
        print("  component (first match from the leaf): " + "; ".join(f"{k} {v*100/len(ev):.0f}%" for k, v in b.most_common(9)))
        leaf = collections.Counter(pkgframe(e["st"][0]) for e in ev if e["st"])
        print("  top self frames: " + "; ".join(f"{k} {v*100/len(ev):.1f}%" for k, v in leaf.most_common(10)))
        own = collections.Counter()
        for e in ev:
            for fr in e["st"]:
                if "org.krost" in fr: own[pkgframe(fr)] += 1; break
        print("  innermost org.krost frame: " + "; ".join(f"{k.replace('org.krost.unidrive.','')} {v*100/len(ev):.1f}%" for k, v in own.most_common(10)))
        incl = collections.Counter()
        for e in ev:
            for fr in set(pkgframe(x) for x in e["st"] if "org.krost" in x): incl[fr] += 1
        print("  org.krost frames on the stack (inclusive, depth<=64): " + "; ".join(f"{k.replace('org.krost.unidrive.','')} {v*100/len(ev):.0f}%" for k, v in incl.most_common(14)))


# main thread by stage of the pass (stage cut points = progress lines of the sync output, see perf_cpu.py)
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from perf_cpu import stages as _stages
print("\n### main thread by stage (samples; component = first match from the leaf; own = innermost org.krost frame)")
for pi, rows in _stages(d):
    if pi not in (1, 2, 3): continue
    t0 = P["t_launch"] if pi == 1 else P["passes"][pi - 2]["t"]
    tcur = t0
    for name, wall, _g in rows:
        t1 = tcur + wall
        ev = [e for e in S if grp(e["eventThread"]) == "main" and tcur < e["t"] <= t1]
        tcur = t1
        if len(ev) < 15: continue
        b = collections.Counter(bucket(e["st"]) for e in ev)
        own = collections.Counter()
        for e in ev:
            for fr in e["st"]:
                if "org.krost" in fr: own[pkgframe(fr).replace("org.krost.unidrive.", "")] += 1; break
        print(f"- pass {pi} / {name}: {len(ev)} samples. component: " + "; ".join(f"{k} {v*100/len(ev):.0f}%" for k, v in b.most_common(6)) + " | own: " + "; ".join(f"{k} {v*100/len(ev):.0f}%" for k, v in own.most_common(5)))

def ms(s):
    n, u = s.split()[:2]
    return float(n) * {"ms": 1, "s": 1000, "us": 0.001, "ns": 1e-6}.get(u, 1)
C = list(events("jdk.Compilation", 1))
print(f"\n## jdk.Compilation: {len(C)} events")
cp = collections.defaultdict(lambda: collections.defaultdict(float)); cn = collections.defaultdict(collections.Counter)
for e in C:
    w = widx(e["t"]); lvl = e.get("compiler"); cp[w][lvl] += ms(e["duration"]) / 1000; cn[w][lvl] += 1
print("compile time (sum of task durations, s) / count per pass window:")
for w in sorted(cp): print(f"  window {w+1}: " + ", ".join(f"{k} {v:.1f}s/{cn[w][k]}" for k, v in sorted(cp[w].items())))
byc = collections.defaultdict(lambda: [0.0, 0])
for e in C:
    k = re.sub(r"\(.*", "", e["method"]); byc[(e["compiler"], k)][0] += ms(e["duration"]) / 1000; byc[(e["compiler"], k)][1] += 1
for comp in ("c2", "c1"):
    print(f"top {comp} methods by total compile time (s / compilations):")
    for (c, k), (t, n) in sorted([x for x in byc.items() if x[0][0] == comp], key=lambda x: -x[1][0])[:12]: print(f"   {t:6.2f}s x{n:<3} {k}")
pk = collections.defaultdict(float)
for e in C:
    m = re.sub(r"\(.*", "", e["method"]).rsplit(".", 1)[0]
    top = ".".join(m.split(".")[:3]) if m.startswith(("org.krost", "io.ktor", "kotlinx", "kotlin")) else ".".join(m.split(".")[:2])
    pk[(e["compiler"], top)] += ms(e["duration"]) / 1000
print("compile time by package prefix:")
for (c, k), t in sorted(pk.items(), key=lambda x: -x[1])[:14]: print(f"   {t:6.2f}s {c} {k}")
recomp = collections.Counter(re.sub(r"\(.*", "", e["method"]) for e in C if e["compiler"] == "c2")
print(f"c2 compilations: {sum(recomp.values())} for {len(recomp)} distinct methods; compiled by c2 more than once: {sum(1 for v in recomp.values() if v>1)}; most: {recomp.most_common(3)}")
D = list(events("jdk.Deoptimization", 1))
print(f"\n## jdk.Deoptimization: {len(D)} events")
dw = collections.Counter(widx(e["t"]) + 1 for e in D); print("per pass window: " + ", ".join(f"{k}: {v}" for k, v in sorted(dw.items())))
print("by reason/action: " + "; ".join(f"{k} {v}" for k, v in collections.Counter(f"{e['reason']}/{e['action']}" for e in D).most_common(8)))
print("top methods: " + "; ".join(f"{re.sub(r'[(].*','',k)} {v}" for k, v in collections.Counter(e['method'] for e in D).most_common(6)))

X = list(events("jdk.JavaExceptionThrow", 48))
print(f"\n## jdk.JavaExceptionThrow (profile.jfc enables it; creating the traces is part of what JFR perturbs): {len(X)} events")
xw = collections.Counter(widx(e["t"]) + 1 for e in X); print("per pass window: " + ", ".join(f"{k}: {v}" for k, v in sorted(xw.items())))
def thrower(st):
    for fr in st:
        if "<init>" in fr or "fillInStackTrace" in fr: continue
        return pkgframe(fr)
    return "?"
def ownf(st):
    for fr in st:
        if "org.krost" in fr: return pkgframe(fr).replace("org.krost.unidrive.", "")
    return "-"
for cls, n in collections.Counter(re.sub(r" \(.*", "", e.get("thrownClass", "?")) for e in X).most_common(8):
    sub = [e for e in X if re.sub(r" \(.*", "", e.get("thrownClass", "?")) == cls]
    print(f"  {n:5d} {cls}: thrown in " + "; ".join(f"{k} {v}" for k, v in collections.Counter(thrower(e["st"]) for e in sub).most_common(2)) + " | innermost org.krost: " + "; ".join(f"{k} {v}" for k, v in collections.Counter(ownf(e["st"]) for e in sub).most_common(3)))

# ---- warm-window JIT detail: what C2 still compiles after the cold pass
NL = chr(10)
print(NL + "## C2 compilations per pass window: new methods vs methods compiled before (recompilation)")
seen_before = set(); firstw = {}
rows = []
for w in range(len(ends)):
    ev = [e for e in C if e.get("compiler") == "c2" and widx(e["t"]) == w]
    if not ev: continue
    names = [re.sub(r"[(].*", "", e["method"]) for e in ev]
    new = [n for n in names if n not in seen_before]; again = [n for n in names if n in seen_before]
    secs = sum(ms(e["duration"]) for e in ev) / 1000
    print(f"  window {w+1}: {len(ev)} c2 compilations ({secs:.1f}s): {len(set(new))} methods never compiled by c2 before, {len(again)} recompilations of earlier c2 methods")
    if w >= 3:
        pk = collections.Counter()
        for e in ev:
            n = re.sub(r"[(].*", "", e["method"]); pk[".".join(n.split(".")[:3])] += ms(e["duration"]) / 1000
        print("     by package: " + "; ".join(f"{k} {v:.1f}s" for k, v in pk.most_common(6)))
        print("     recompiled: " + "; ".join(sorted(set(again))[:8]))
    seen_before.update(names)

# ---- boot detail: inclusive frames of the main thread before the first scan line
print(NL + "## main thread, boot stage (JVM launch to first Scanning line): inclusive share of selected frames")
bt = P["t_launch"]
try:
    first = min(t for t, ln in ((float(l.split(" ", 1)[0]), l) for l in open(f"{d}/out.log") if l[:1].isdigit()) if "Scanning remote" in ln)
except ValueError:
    first = bt + 10
ev = [e for e in S if grp(e["eventThread"]) == "main" and bt < e["t"] <= first]
print(f"  {len(ev)} samples, {first - bt:.1f}s wall")
pats = [("picocli (CommandLine model building)", "picocli[.]"), ("kotlinx.serialization / TOML config decode", "kotlinx[.]serialization|Toml|SyncConfig"),
        ("logback init", "ch[.]qos[.]logback"), ("ServiceLoader / provider registry", "ServiceLoader|ProviderRegistry|ProviderFactory"),
        ("sqlite open / schema", "org[.]sqlite|JdbcStateDatabaseConnector|StateDatabase[.]initialize"), ("Ktor client construction", "io[.]ktor[.]client[.]HttpClient|s3HttpClient"),
        ("class loading under any of the above", "BuiltinClassLoader|ClassLoader[.]loadClass|URLClassPath|JarFile|Inflater"), ("java.lang.invoke / lambda linkage", "java[.]lang[.]invoke|LambdaMetafactory"),
        ("Main.kt main()", "MainKt[.]main"), ("SyncCommand.run before the first scan", "SyncCommand[.]run")]
for name, rx in pats:
    r = re.compile(rx); n = sum(1 for e in ev if any(r.search(f) for f in e["st"]))
    print(f"  {n*100/max(len(ev),1):5.1f}%  {name}")
leafs = collections.Counter(pkgframe(e["st"][0]) for e in ev if e["st"])
print("  top self frames: " + "; ".join(f"{k} {v*100/len(ev):.1f}%" for k, v in leafs.most_common(8)))

# ---- inclusive share (a frame anywhere on the stack) of selected components, per data window, over main+workers samples (the Java part of the CPU)
print(NL + "## inclusive share of the Java-thread samples (main + workers) that have the component anywhere on the stack; data windows only")
INC = [("RequestId plugin + UrlRedaction", "RequestIdPluginKt|UrlRedaction"), ("StateDatabase (SQLite)", "StateDatabase|org[.]sqlite"),
       ("SigV4 signing", "SigV4Signer|S3Api[.]signed"), ("file digest (SHA-256/MD5)", "FileDigest|HashVerifier|MessageDigest"),
       ("logback / slf4j", "ch[.]qos[.]logback|org[.]slf4j"), ("S3 XML + java.time parse", "S3Api[$]Companion[.](xml|parseIso|children)|javax[.]xml|com[.]sun[.]org[.]apache"),
       ("Ktor client pipeline (any io.ktor.client frame)", "io[.]ktor[.]client"), ("Ktor CIO / network", "io[.]ktor[.]network|io[.]ktor[.]client[.]engine[.]cio|sun[.]nio[.]ch"),
       ("kotlinx.coroutines scheduler", "kotlinx[.]coroutines[.]scheduling"), ("progress / FileEvents / audit", "FileEvents|CliProgressReporter|AuditLog|ProgressTracking")]
INCR = [(n, re.compile(r)) for n, r in INC]
for w in range(len(P["passes"])):
    x = P["passes"][w]
    if x["up"] + x["down"] == 0: continue
    ev = [e for e in S if widx(e["t"]) == w and grp(e["eventThread"]) in ("main", "workers")]
    if len(ev) < 200: continue
    print(f"- window {w+1} ({len(ev)} samples): " + "; ".join(f"{n} {sum(1 for e in ev if any(r.search(f) for f in e['st']))*100/len(ev):.0f}%" for n, r in INCR))
