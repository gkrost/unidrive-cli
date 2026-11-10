#!/usr/bin/env python3
"""perf_drive_win.py -- main-thread CPU, blocked time and wall of one UniDrive JVM against the loopback S3 stub (perf_stub_s3.py),
on Windows (perf_drive.py reads /proc and is Linux only). Same plan as perf_drive.py: `sync --watch`, pass 1 is the cold boot pass with
the whole data set, passes 2..N are warm (a copy of the set per pass). No credentials: the profile holds dummy keys for the stub.

Environment: PERF_DATASET = a directory with the test files (perf_dataset.py writes the 323-file / 113.4 MB shape), PERF_DIR = work/output
directory, UNI_JAR = the shadow jar, UNI_JAVA = java executable (default: java on PATH).

usage: perf_drive_win.py TAG MODE [--passes N] [--opts "JVM FLAGS"] [--trace "pkg.Class::method;..."]
  MODE up    pass 1 uploads the set; passes 2..N move another copy into the sync root
  MODE down  the set is seeded onto the stub with a separate JVM; pass 1 downloads it; passes 2..N are server-side copies of that prefix
Writes PERF_DIR/runs/TAG/result.json: per pass the engine's wall (the "Sync complete" line), the JVM's CPU by thread group (from
`jcmd Thread.print` cpu= fields and GetProcessTimes), and, with --trace, the time the main thread spent inside the traced methods
(JFR jdk.MethodTrace, JDK 25+). Everything else (stub, profiles, sync roots) lives under PERF_DIR/work-TAG and is removed at the end."""
import os, sys, time, json, shutil, subprocess, threading, re, urllib.request, argparse, ctypes, ctypes.wintypes as wt

PASS_RE = re.compile(r"Sync complete: (\d+) downloaded, (\d+) uploaded.*\(([\d.]+)s\)")
THREAD_RE = re.compile(r'^"([^"]+)".*?\bcpu=([\d.]+)ms')


def proc_cpu_s(handle):
    c, e, k, u = (wt.FILETIME() for _ in range(4))
    if not ctypes.windll.kernel32.GetProcessTimes(wt.HANDLE(int(handle)), ctypes.byref(c), ctypes.byref(e), ctypes.byref(k), ctypes.byref(u)):
        return None
    ft = lambda f: ((f.dwHighDateTime << 32) | f.dwLowDateTime) / 1e7
    return dict(user=ft(u), sys=ft(k))


def group_of(name):
    if name == "main": return "main"
    if "CompilerThread" in name: return "jit"
    if name.startswith("DefaultDispatcher") or "dispatcher" in name.lower(): return "workers"
    return "other"


def thread_cpu(jcmd, pid):
    out = subprocess.run([jcmd, str(pid), "Thread.print"], capture_output=True, text=True, timeout=60).stdout
    groups = {}
    for ln in out.splitlines():
        m = THREAD_RE.match(ln)
        if m: groups[group_of(m[1])] = groups.get(group_of(m[1]), 0.0) + float(m[2]) / 1000.0
    return groups


def calib():   # host-speed probe: a fixed single-thread loop, best of 3 (s); it moves with CPU contention on a shared host
    best = 9e9
    for _ in range(3):
        t = time.perf_counter(); x = 0
        for i in range(2_000_000): x += i * i
        best = min(best, time.perf_counter() - t)
    return best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("tag"); ap.add_argument("mode")
    ap.add_argument("--passes", type=int, default=4)
    ap.add_argument("--opts", default="")
    ap.add_argument("--trace", default="")
    ap.add_argument("--port", type=int, default=19150)
    a = ap.parse_args()
    W = os.environ["PERF_DIR"]; DATA = os.environ["PERF_DATASET"]; JAR = os.environ["UNI_JAR"]
    JAVA = os.environ.get("UNI_JAVA") or "java"; JCMD = os.path.join(os.path.dirname(JAVA), "jcmd") if os.path.dirname(JAVA) else "jcmd"
    STUB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "perf_stub_s3.py")
    RUN = f"{W}/runs/{a.tag}"; WORK = f"{W}/work-{a.tag}"
    shutil.rmtree(RUN, ignore_errors=True); shutil.rmtree(WORK, ignore_errors=True)
    os.makedirs(RUN); os.makedirs(WORK)
    up = a.mode == "up"
    stub = subprocess.Popen([sys.executable, STUB, "serve", "--port", str(a.port), "--log", f"{RUN}/stub.jsonl"])
    for _ in range(100):
        try: urllib.request.urlopen(f"http://127.0.0.1:{a.port}/__mark/ready", timeout=1).read(); break
        except Exception: time.sleep(0.1)

    def mkprofile(name):
        c = f"{WORK}/cfg-{name}".replace("\\", "/"); r = f"{WORK}/root-{name}".replace("\\", "/"); os.makedirs(c); os.makedirs(r)
        open(f"{c}/config.toml", "w").write(f'''[general]
poll_interval = 10
min_poll_interval = 10
max_poll_interval = 10

[providers.stub-s3]
type = "s3"
mode = "mirror"
sync_root = "{r}"
bucket = "testbucket"
region = "us-east-1"
endpoint = "http://127.0.0.1:{a.port}"
access_key_id = "stub"
secret_access_key = "stubstubstub"
''')
        return c, r

    procs = []
    def cleanup():
        for p in procs:
            if p.poll() is None: p.kill()
        stub.kill()
        time.sleep(0.5)
        shutil.rmtree(WORK, ignore_errors=True)

    try:
        base = [JAVA, "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8"]
        if not up:   # stage 0 (not measured): put the set on the stub with a separate JVM, then a fresh profile downloads it
            c0, r0 = mkprofile("seed")
            shutil.copytree(DATA, f"{r0}/p1")
            subprocess.run(base + ["-jar", JAR, "-c", c0, "-p", "stub-s3", "sync", "--upload-only"], check=True,
                           stdout=open(f"{RUN}/seed.out", "w"), stderr=subprocess.STDOUT)
            shutil.rmtree(r0)
        c, r = mkprofile("main")
        if up: shutil.copytree(DATA, f"{r}/p1")
        jvm = a.opts.split() if a.opts else []
        jfr = f"{RUN}/trace.jfr".replace("\\", "/")
        if a.trace:
            jvm.append(f"-XX:StartFlightRecording:filename={jfr},method-trace={a.trace}")
        cmd = base + jvm + ["-jar", JAR, "-c", c, "-p", "stub-s3", "sync", "--watch", "--upload-only" if up else "--download-only"]
        calib0 = calib()
        t_launch = time.time()
        p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1, encoding="utf-8", errors="replace")
        procs.append(p)

        passes = []; lines = []; cv = threading.Condition()
        def reader():
            for ln in p.stdout:
                t = time.time(); ln = ln.rstrip("\n"); lines.append((t, ln))
                m = PASS_RE.search(ln)
                if m:
                    with cv: passes.append(dict(t=t, down=int(m[1]), up=int(m[2]), secs=float(m[3]))); cv.notify_all()
        rt = threading.Thread(target=reader, daemon=True); rt.start()

        def wait_passes(n, timeout=300):
            end = time.time() + timeout
            with cv:
                while len(passes) < n:
                    if p.poll() is not None: return False
                    cv.wait(0.5)
                    if time.time() > end: raise SystemExit(f"timeout waiting for pass {n}")
            return True

        def snap():
            t = time.time(); pc = proc_cpu_s(p._handle); tc = thread_cpu(JCMD, p.pid)
            return dict(t=t, proc=pc, threads=tc)

        def inject(k):
            if up:
                tmp = f"{WORK}/inj-p{k}"; shutil.copytree(DATA, tmp); os.rename(tmp, f"{r}/p{k}")
            else:
                urllib.request.urlopen(f"http://127.0.0.1:{a.port}/__clone/testbucket?from=p1/&to=p{k}/", timeout=60).read()

        rows = []   # per data pass: engine wall, cpu deltas by group over [window start, pass complete]
        s_prev = None
        ok = wait_passes(1)
        if not ok: raise SystemExit("child exited before the boot pass finished:\n" + "\n".join(l for _, l in lines[-10:]))
        s_launch0 = dict(t=t_launch, proc=dict(user=0.0, sys=0.0), threads={})
        s_after = snap()
        rows.append(dict(pass_no=1, kind="cold", engine_s=passes[0]["secs"], moved=passes[0]["up"] + passes[0]["down"],
                         start=s_launch0, end=s_after))
        for k in range(2, a.passes + 1):
            time.sleep(4.0)   # let the idle tail, the stub and the disk settle
            s_start = snap()
            n0 = len(passes)
            inject(k)
            while True:
                if not wait_passes(n0 + 1): raise SystemExit("child exited during pass %d" % k)
                hit = next((q for q in passes[n0:] if q["up" if up else "down"] > 0), None)
                if hit: break
                n0 = len(passes)
            s_end = snap()
            rows.append(dict(pass_no=k, kind="warm", engine_s=hit["secs"], moved=hit["up"] + hit["down"], start=s_start, end=s_end))
        time.sleep(1.0)
        if a.trace:
            subprocess.run([JCMD, str(p.pid), "JFR.dump", f"filename={jfr}"], capture_output=True, timeout=120)
        s_final = snap()
        p.kill(); p.wait()
        stub_cpu = None
        out = []
        for row in rows:
            s, e = row["start"], row["end"]
            d = {g: round(e["threads"].get(g, 0.0) - s["threads"].get(g, 0.0), 3) for g in set(e["threads"]) | set(s["threads"])}
            pc = e["proc"]["user"] + e["proc"]["sys"] - s["proc"]["user"] - s["proc"]["sys"]
            out.append(dict(pass_no=row["pass_no"], kind=row["kind"], engine_s=row["engine_s"], moved=row["moved"],
                            window_s=round(e["t"] - s["t"], 2), proc_cpu_s=round(pc, 3), threads_cpu_s=d))
        traced = {}
        if a.trace:
            import datetime
            js = subprocess.run([os.path.join(os.path.dirname(JAVA), "jfr") if os.path.dirname(JAVA) else "jfr", "print", "--json", "--events", "jdk.MethodTrace", jfr],
                                capture_output=True, text=True, encoding="utf-8").stdout
            js = js[js.index("{"):]
            evs = json.loads(js)["recording"]["events"]
            for ev in evs:
                v = ev["values"]; ts = datetime.datetime.fromisoformat(v["startTime"]).timestamp()
                dur = v["duration"]
                dur_s = float(re.match(r"PT([\d.]+)S", dur).group(1)) if dur.startswith("PT") and dur.endswith("S") else 0.0
                thr = (v.get("eventThread") or {}).get("javaName", "?")
                for row in rows:
                    if row["start"]["t"] - 0.5 <= ts <= row["end"]["t"]:
                        key = f"{v['method']['type']['name'].split('.')[-1]}.{v['method']['name']}|{'main' if thr == 'main' else 'other'}"
                        d = traced.setdefault(row["pass_no"], {}).setdefault(key, [0, 0.0]); d[0] += 1; d[1] += dur_s
        for o in out: o["traced"] = {k: dict(calls=v[0], total_s=round(v[1], 3)) for k, v in traced.get(o["pass_no"], {}).items()}
        json.dump(dict(cmd=" ".join(cmd), calib_s=round(calib0, 4), passes=out, final_threads=s_final["threads"], trace=a.trace), open(f"{RUN}/result.json", "w"), indent=1)
        open(f"{RUN}/out.log", "w", encoding="utf-8").write("\n".join(f"{t:.3f} {l}" for t, l in lines))
        print(f"done {a.tag}: " + "; ".join(f"p{r['pass_no']} wall {r['engine_s']}s cpu {r['proc_cpu_s']}s main {r['threads_cpu_s'].get('main')}" for r in out))
    finally:
        cleanup()


if __name__ == "__main__":
    main()
