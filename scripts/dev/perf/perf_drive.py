#!/usr/bin/env python3
"""perf_drive.py -- cold vs warm CPU of one UniDrive JVM against the loopback S3 stub (perf_stub_s3.py).

Environment: PERF_DATASET = a directory with the test files (the 323-file set of gkrost/unidrive-cli#563 was 113 MB),
PERF_DIR = where runs/ and the throwaway profiles go (default ~/unidrive-perf), UNI_BIN = the launcher (default: unidrive on PATH),
UNIDRIVE_JAVA as the launcher reads it. Linux only (it reads /proc). No credentials: the profile holds dummy keys for the stub.

usage: perf_drive.py TAG MODE [--passes N] [--opts "JAVA_TOOL_OPTIONS"] [--tail S]
  MODE up        sync --watch --upload-only: pass 1 (boot) uploads 323 files, then N-1 more copies are moved into the sync root, one per pass
  MODE down      sync --watch --download-only: pass 1 downloads 323 files, then N-1 server-side copies of that prefix appear, one per pass
  MODE up1|down1 the same single pass as a one-shot JVM (no --watch), for comparison
Writes runs/TAG/{timeline.json,out.log,passes.json,stub.jsonl}. Everything (stub, config, sync root) lives under $W/work-TAG and is removed at the end.
Tables: perf_cpu.py, perf_oneshot_table.py, perf_watch_table.py; frames: perf_jfr.py; compilation counts: perf_compile.py.
The watch flow itself (watch_flow) is importable and unit-tested (test_perf_drive.py)."""
import os, sys, time, json, glob, shutil, subprocess, threading, signal, re, urllib.request, argparse

# The settles of the watch plan, in seconds; the tests shrink them.
SETTLE_S = 3.0   # after an idle poll, before the next batch is injected
QUIET_S = 1.0    # around the idle tail, and before the stop signal


def early_exit(p, lines, stage):
    """Why the plan of a child that died early cannot go on: the return code and the output tail (the review of #592)."""
    tail = "\n".join(l for _, l in lines[-8:])
    return f"the measured child exited early (rc={p.returncode}) before {stage} finished; output tail:\n{tail}"


def watch_flow(a, up, p, passes, lines, wait, inject, marks):
    """The --watch measurement plan: the boot pass, a injected batch per pass until one carried data, then the idle tail,
    then the stop signal. Returns None when the plan ran to its end, or the message of an early child exit —
    wait() says False for exactly that (the child is gone and no pass is still in reading), and the plan stops there
    instead of testing the same idle pass forever, so the partial results are still written and cleaned up."""
    if not wait(1):
        return early_exit(p, lines, "the boot pass")
    for k in range(2, a.passes + 1):
        time.sleep(SETTLE_S)
        marks.append((time.time(), f"inject p{k}"))
        inject(k)
        # wait for a pass that actually moved files (idle polls may complete in between)
        while True:
            if not wait(len(passes) + 1):
                return early_exit(p, lines, f"the pass after inject p{k}")
            if passes[-1]["up" if up else "down"] > 0:
                break
    time.sleep(QUIET_S)
    if not wait(len(passes) + a.tail):   # idle passes: what a no-change poll costs at this tree size
        return early_exit(p, lines, "the idle tail")
    time.sleep(QUIET_S)
    p.send_signal(signal.SIGTERM)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("tag"); ap.add_argument("mode")
    ap.add_argument("--passes", type=int, default=4)
    ap.add_argument("--opts", default="")
    ap.add_argument("--tail", type=int, default=2, help="idle (no-op) passes to observe after the last data pass")
    ap.add_argument("--port", type=int, default=19150)
    ap.add_argument("--hz", type=float, default=5.0)
    a = ap.parse_args()

    HOME = os.path.expanduser("~"); W = os.environ.get("PERF_DIR") or f"{HOME}/unidrive-perf"
    DATA = os.environ.get("PERF_DATASET") or sys.exit("set PERF_DATASET to a directory of test files")
    STUB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "perf_stub_s3.py")
    RUN = f"{W}/runs/{a.tag}"; WORK = f"{W}/work-{a.tag}"
    shutil.rmtree(RUN, ignore_errors=True); shutil.rmtree(WORK, ignore_errors=True)
    os.makedirs(RUN); os.makedirs(WORK)
    env = dict(os.environ)
    UNI = os.environ.get("UNI_BIN") or shutil.which("unidrive") or sys.exit("unidrive is not on PATH; set UNI_BIN")
    watch = not a.mode.endswith("1"); up = a.mode.startswith("up"); ls = a.mode == "ls1"

    stub = subprocess.Popen([sys.executable, STUB, "serve", "--port", str(a.port), "--log", f"{RUN}/stub.jsonl"])
    for _ in range(100):
        try: urllib.request.urlopen(f"http://127.0.0.1:{a.port}/__mark/ready", timeout=1).read(); break
        except Exception: time.sleep(0.1)

    def mkprofile(name):
        c = f"{WORK}/cfg-{name}"; r = f"{WORK}/root-{name}"; os.makedirs(c); os.makedirs(r)
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

    def host_stat():
        f = open("/proc/stat").readline().split()[1:]
        v = [int(x) for x in f]; idle = v[3] + v[4]; steal = v[7] if len(v) > 7 else 0
        return dict(busy=(sum(v) - idle) / os.sysconf("SC_CLK_TCK"), steal=steal / os.sysconf("SC_CLK_TCK"))
    def proc_cpu(pid):
        try:
            f = open(f"/proc/{pid}/stat").read(); f = f[f.rindex(")") + 2:].split(); return (int(f[11]) + int(f[12])) / os.sysconf("SC_CLK_TCK")
        except OSError: return 0.0
    def calib():   # host-speed probe: a fixed single-thread loop, best of 3 (s); it moves with CPU contention on a shared host
        best = 9e9
        for _ in range(3):
            t = time.perf_counter(); x = 0
            for i in range(2_000_000): x += i * i
            best = min(best, time.perf_counter() - t)
        return best
    procs = []
    def cleanup():
        for p in procs:
            if p.poll() is None: p.kill()
        stub.kill()
        shutil.rmtree(WORK, ignore_errors=True)

    try:
        if not up:   # stage 0 (not measured): put the dataset on the stub with a separate JVM, then a fresh profile downloads it
            c0, r0 = mkprofile("seed")
            subprocess.run(["cp", "-a", DATA, f"{r0}/p1"], check=True)
            subprocess.run([UNI, "-c", c0, "-p", "stub-s3", "sync", "--upload-only"], env=env, check=True,
                           stdout=open(f"{RUN}/seed.out", "w"), stderr=subprocess.STDOUT)
            shutil.rmtree(r0)
        c, r = mkprofile("main")
        if up: subprocess.run(["cp", "-a", DATA, f"{r}/p1"], check=True)   # pass 1 = the boot pass, with 323 files already there

        cmd = [UNI, "-c", c, "-p", "stub-s3", "sync"] + (["--watch"] if watch else []) + (["--upload-only"] if up else ["--download-only"])
        if ls: cmd = [UNI, "-c", c, "-p", "stub-s3", "ls", "/"]
        penv = dict(env, JAVA_TOOL_OPTIONS=a.opts)
        calib0 = calib()
        t_launch = time.time(); h0 = host_stat(); stub0 = proc_cpu(stub.pid)
        p = subprocess.Popen(cmd, env=penv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        procs.append(p); pid = p.pid

        samples = []; stop = threading.Event()
        def sampler():
            while not stop.is_set():
                t = time.time(); row = {}
                for d in glob.glob(f"/proc/{pid}/task/[0-9]*"):
                    try:
                        st = open(d + "/stat").read(); comm = open(d + "/comm").read().strip()
                    except OSError: continue
                    f = st[st.rindex(")") + 2:].split(); row[d.rsplit("/", 1)[1]] = (comm, int(f[11]), int(f[12]))
                if row: samples.append((t, row))
                time.sleep(1.0 / a.hz)
        threading.Thread(target=sampler, daemon=True).start()

        passes = []; lines = []; cv = threading.Condition()
        def reader():
            for ln in p.stdout:
                t = time.time(); ln = ln.rstrip("\n"); lines.append((t, ln))
                m = re.match(r"Sync complete: (\d+) downloaded, (\d+) uploaded.*\(([\d.]+)s\)", ln)
                if m:
                    with cv: passes.append(dict(t=t, down=int(m[1]), up=int(m[2]), secs=float(m[3]), line=ln)); cv.notify_all()
        rt = threading.Thread(target=reader, daemon=True); rt.start()

        def wait_passes(n, timeout=300):
            end = time.time() + timeout
            with cv:
                while len(passes) < n:
                    if p.poll() is not None and not rt.is_alive(): return False
                    cv.wait(0.5)
                    if time.time() > end: raise SystemExit(f"timeout waiting for pass {n}")
            return True

        def inject(k):
            if up:
                tmp = f"{WORK}/inj-p{k}"; subprocess.run(["cp", "-a", DATA, tmp], check=True); os.rename(tmp, f"{r}/p{k}")
            else:
                urllib.request.urlopen(f"http://127.0.0.1:{a.port}/__clone/testbucket?from=p1/&to=p{k}/", timeout=30).read()

        marks = []
        early = watch_flow(a, up, p, passes, lines, wait_passes, inject, marks) if watch else None
        try: p.wait(timeout=60)
        except subprocess.TimeoutExpired: p.kill(); p.wait()
        t_exit = time.time(); h1 = host_stat(); stub1 = proc_cpu(stub.pid); rt.join(5)
        if ls: passes.append(dict(t=t_exit, down=0, up=0, secs=t_exit - t_launch, line="ls /")); time.sleep(0.5); stop.set()
        if not watch: pass
        json.dump(dict(cmd=" ".join(cmd), opts=a.opts, t_launch=t_launch, t_exit=t_exit, passes=passes, marks=marks, rc=p.returncode, calib_s=calib0, host=dict(busy_s=h1["busy"] - h0["busy"], steal_s=h1["steal"] - h0["steal"], stub_cpu_s=stub1 - stub0, loadavg=open("/proc/loadavg").read().split()[:3])), open(f"{RUN}/passes.json", "w"), indent=1)
        json.dump(samples, open(f"{RUN}/timeline.json", "w"))
        open(f"{RUN}/out.log", "w").write("\n".join(f"{t:.3f} {l}" for t, l in lines))
        print(f"done {a.tag}: {len(passes)} passes, {len(samples)} samples, rc={p.returncode}")
        if early:
            raise SystemExit(early)   # after the partial artifacts are written: the run died in the plan (the review of #592)
    finally:
        cleanup()


if __name__ == "__main__":
    main()
