#!/usr/bin/env python3
"""jvm_evolve.py -- evolutionary search over the JVM flags of one unidrive workload (gkrost/unidrive-cli#34).

A genome is one choice per gene of SEARCH_SPACE (GC, heap, initial heap, thread stack, code cache). Each genome is
rendered to JVM flags on top of the base flags in dist/launcher/jvm-flags.txt, then scored by running a local sync
of a generated localfs tree in a scratch profile:

    fitness = wall_s + W_RSS * peak_rss_GB + W_GC * total_gc_pause_s     (lower is better)

The search is a plain generational GA: tournament selection, uniform crossover, per-gene mutation, elitism. Results
are cached by genome, so a repeated genome costs nothing. Nothing is installed: the best flags are written to a file
for review, and the live daemon, the systemd units and the real profiles are never touched.

Usage (dry run prints the plan and the base flags, runs no JVM):
    jvm_evolve.py --jar core/app/cli/build/libs/unidrive-X.jar --workdir /tmp/jvm-evolve-run --dry-run

Real run (pick a fresh --workdir; it must not be a unidrive config or state directory):
    jvm_evolve.py --jar ... --workdir ... --population 8 --generations 5 --repeats 2 --files 400 --seed 1

Standard library only. Python 3.10 or newer.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import random
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

# One choice list per gene. The first entry of each list is the value the current launchers use, so generation 0
# contains the production baseline. Empty string = flag omitted.
SEARCH_SPACE: dict[str, list[str]] = {
    "gc": ["G1", "Parallel", "Serial", "Z"],
    "xmx": ["1g", "2g", "3g", "4g"],
    "xms": ["64m", "0", "256m"],
    "xss": ["", "512k", "2m"],
    "codecache": ["", "128m", "240m"],
}
BASELINE: dict[str, str] = {"gc": "G1", "xmx": "2g", "xms": "64m", "xss": "", "codecache": ""}

GC_FLAG = {
    "G1": "-XX:+UseG1GC",
    "Parallel": "-XX:+UseParallelGC",
    "Serial": "-XX:+UseSerialGC",
    "Z": "-XX:+UseZGC",
}

# Weights of the fitness (see the module docstring). Wall time is the unit.
W_RSS_PER_GB = 0.5
W_GC_PER_S = 1.0

REPO_FLAGS = Path(__file__).resolve().parents[3] / "dist" / "launcher" / "jvm-flags.txt"
# -Xms is a gene, so the base file's value is dropped; -XX:FlightRecorderOptions is never added (see the file's header).
BASE_EXCLUDE_PREFIXES = ("-Xms", "-Xmx", "-XX:FlightRecorderOptions")

TIME_LINE = re.compile(r"^(\d+(?:\.\d+)?) (\d+)$")
# The JVM writes decimal commas under a comma locale (e.g. "2,786ms"), so both separators are accepted.
GC_PAUSE = re.compile(r"Pause .*? (\d+(?:[.,]\d+)?)ms$")

FORBIDDEN_WORKDIRS = (
    Path.home() / ".config" / "unidrive",
    Path.home() / ".local" / "share" / "unidrive",
    Path.home() / ".cache" / "unidrive",
)
WORKDIR_MARKER = ".jvm-evolve-workdir"


@dataclass(frozen=True)
class Genome:
    genes: tuple[tuple[str, str], ...]

    @staticmethod
    def of(values: dict[str, str]) -> "Genome":
        return Genome(tuple((k, values[k]) for k in SEARCH_SPACE))

    def values(self) -> dict[str, str]:
        return dict(self.genes)

    def key(self) -> str:
        return hashlib.sha256(json.dumps(self.genes).encode()).hexdigest()[:16]


@dataclass
class Score:
    genome: Genome
    fitness: float
    wall_s: float | None = None
    rss_gb: float | None = None
    gc_s: float | None = None
    error: str | None = None
    generation: int = 0
    samples: list[dict] = field(default_factory=list)


def render_flags(genome: Genome, base: list[str]) -> list[str]:
    """The JVM flags of one genome: the base flags plus the genome's gene flags, in a fixed order."""
    v = genome.values()
    flags = list(base)
    flags.append(GC_FLAG[v["gc"]])
    flags.append(f"-Xmx{v['xmx']}")
    if v["xms"] != "0":
        flags.append(f"-Xms{v['xms']}")
    if v["xss"]:
        flags.append(f"-Xss{v['xss']}")
    if v["codecache"]:
        flags.append(f"-XX:ReservedCodeCacheSize={v['codecache']}")
    return flags


def load_base_flags(path: Path = REPO_FLAGS) -> list[str]:
    """Active lines of dist/launcher/jvm-flags.txt, minus the genes and the always-excluded flags."""
    out: list[str] = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if not line.startswith("-"):
            continue
        if line.startswith(BASE_EXCLUDE_PREFIXES):
            continue
        out.append(line)
    return out


def parse_gc_pauses_ms(gc_log: str) -> float:
    """Total pause time in ms from an -Xlog:gc output: the trailing `<n>ms` of every `Pause` line."""
    total = 0.0
    for line in gc_log.splitlines():
        m = GC_PAUSE.search(line)
        if m:
            total += float(m.group(1).replace(",", "."))
    return total


def parse_time_line(stderr: str) -> tuple[float, int] | None:
    """The last `/usr/bin/time -f '%e %M'` line of stderr: wall seconds and peak RSS in KiB."""
    for line in reversed(stderr.splitlines()):
        m = TIME_LINE.match(line.strip())
        if m:
            return float(m.group(1)), int(m.group(2))
    return None


def fitness_of(wall_s: float, rss_kib: int, gc_ms: float) -> float:
    rss_gb = rss_kib / (1024 * 1024)
    return wall_s + W_RSS_PER_GB * rss_gb + W_GC_PER_S * (gc_ms / 1000.0)


# ---- the genetic algorithm (pure: no I/O, the scorer is injected) -------------------------------------------------


def random_genome(rng: random.Random) -> Genome:
    return Genome.of({k: rng.choice(v) for k, v in SEARCH_SPACE.items()})


def initial_population(rng: random.Random, size: int) -> list[Genome]:
    """Generation 0: the production baseline first, then random genomes (duplicates removed)."""
    pop = [Genome.of(BASELINE)]
    seen = {pop[0].key()}
    while len(pop) < size:
        g = random_genome(rng)
        if g.key() not in seen:
            seen.add(g.key())
            pop.append(g)
    return pop


def tournament(rng: random.Random, scored: list[Score], k: int = 2) -> Genome:
    picks = rng.sample(scored, min(k, len(scored)))
    return min(picks, key=lambda s: s.fitness).genome


def crossover(rng: random.Random, a: Genome, b: Genome) -> Genome:
    av, bv = a.values(), b.values()
    return Genome.of({k: (av[k] if rng.random() < 0.5 else bv[k]) for k in SEARCH_SPACE})


def mutate(rng: random.Random, g: Genome, rate: float) -> Genome:
    v = g.values()
    for k, choices in SEARCH_SPACE.items():
        if rng.random() < rate:
            v[k] = rng.choice(choices)
    return Genome.of(v)


def next_generation(rng: random.Random, scored: list[Score], size: int, mutation_rate: float) -> list[Genome]:
    """Elitism of one, then offspring of tournament-selected parents until the population is full."""
    ranked = sorted(scored, key=lambda s: s.fitness)
    nxt: list[Genome] = [ranked[0].genome]
    seen = {nxt[0].key()}
    attempts = 0
    while len(nxt) < size and attempts < size * 50:
        attempts += 1
        child = mutate(rng, crossover(rng, tournament(rng, ranked), tournament(rng, ranked)), mutation_rate)
        if child.key() not in seen:
            seen.add(child.key())
            nxt.append(child)
    # A tiny search space can run out of distinct genomes; fill with the best ones again rather than stall.
    while len(nxt) < size:
        nxt.append(ranked[len(nxt) % len(ranked)].genome)
    return nxt


def evolve(
    rng: random.Random,
    population: int,
    generations: int,
    mutation_rate: float,
    scorer,
    log=print,
) -> tuple[list[Score], list[list[Score]]]:
    """Runs the GA. `scorer(genome, generation) -> Score`; results are cached per genome across generations."""
    cache: dict[str, Score] = {}
    history: list[list[Score]] = []
    pop = initial_population(rng, population)
    for gen in range(generations):
        scored: list[Score] = []
        for g in pop:
            if g.key() not in cache:
                cache[g.key()] = scorer(g, gen)
            s = cache[g.key()]
            s.generation = gen
            scored.append(s)
        history.append(sorted(scored, key=lambda s: s.fitness))
        best = history[-1][0]
        log(f"gen {gen}: best fitness {best.fitness:.3f} {best.genome.values()} ({len(cache)} evaluated)")
        if gen + 1 < generations:
            pop = next_generation(rng, scored, population, mutation_rate)
    best_overall = min(cache.values(), key=lambda s: s.fitness)
    return [best_overall], history


# ---- the real scorer (runs JVMs) ----------------------------------------------------------------------------------


def check_workdir(workdir: Path) -> None:
    resolved = workdir.resolve()
    for forbidden in FORBIDDEN_WORKDIRS:
        if resolved == forbidden.resolve() or forbidden.resolve() in resolved.parents or resolved in forbidden.resolve().parents:
            raise SystemExit(f"refusing --workdir {resolved}: it overlaps a live unidrive directory ({forbidden})")
    if workdir.exists() and any(workdir.iterdir()) and not (workdir / WORKDIR_MARKER).exists():
        raise SystemExit(f"refusing --workdir {resolved}: not empty and not created by jvm_evolve (no {WORKDIR_MARKER})")
    workdir.mkdir(parents=True, exist_ok=True)
    (workdir / WORKDIR_MARKER).write_text("jvm_evolve workdir: safe to delete\n", encoding="utf-8")


def make_tree(root: Path, files: int, big_every: int) -> None:
    """A deterministic tree: `files` files, every `big_every`-th is 64 KiB, the rest 1 KiB."""
    if root.exists():
        shutil.rmtree(root)
    root.mkdir(parents=True)
    for i in range(files):
        sub = root / f"d{i % 10}"
        sub.mkdir(exist_ok=True)
        size = 64 * 1024 if i % big_every == 0 else 1024
        (sub / f"f{i}.bin").write_bytes(bytes([i % 251]) * size)


class Workload:
    """One evaluation = a fresh localfs mirror profile in its own config dir, one timed `sync` under the candidate JVM."""

    def __init__(self, jar: Path, workdir: Path, base_flags: list[str], files: int, timeout_s: int):
        self.jar = jar
        self.workdir = workdir
        self.base_flags = base_flags
        self.files = files
        self.timeout_s = timeout_s
        self.tree = workdir / "tree"
        make_tree(self.tree, files, big_every=8)

    def _unidrive(self, cfg: Path, *args: str, java_flags: list[str] | None = None, timeout: int | None = None):
        java = ["java", *(java_flags or ["-Xmx2g"]), "-jar", str(self.jar), "-c", str(cfg), *args]
        return subprocess.run(
            ["/usr/bin/time", "-f", "%e %M", *java],
            capture_output=True,
            text=True,
            timeout=timeout or self.timeout_s,
        )

    def run(self, flags: list[str], run_dir: Path) -> dict:
        if run_dir.exists():
            shutil.rmtree(run_dir)
        cfg = run_dir / "cfg"
        local = run_dir / "local"
        remote = run_dir / "remote"
        cfg.mkdir(parents=True)
        remote.mkdir(parents=True)
        shutil.copytree(self.tree, local)
        setup = self._unidrive(
            cfg,
            "profile", "add", "--type", "localfs", "--name", "lfs", "--mode", "mirror",
            "--sync-root", str(local), "--option", f"root_path={remote}", "--json",
        )
        if setup.returncode != 0:
            raise RuntimeError(f"profile add failed: {setup.stderr.strip()[-400:]}")
        gc_log = run_dir / "gc.log"
        timed = self._unidrive(
            cfg, "-p", "lfs", "sync",
            java_flags=[*flags, f"-Xlog:gc:file={gc_log}:uptime"],
        )
        if timed.returncode != 0:
            raise RuntimeError(f"sync exited {timed.returncode}: {timed.stderr.strip()[-400:]}")
        parsed = parse_time_line(timed.stderr)
        if parsed is None:
            raise RuntimeError("no /usr/bin/time line in stderr")
        wall_s, rss_kib = parsed
        gc_ms = parse_gc_pauses_ms(gc_log.read_text(encoding="utf-8")) if gc_log.exists() else 0.0
        return {"wall_s": wall_s, "rss_kib": rss_kib, "gc_ms": gc_ms}


def make_scorer(workload: Workload, repeats: int, root: Path):
    def score(genome: Genome, generation: int) -> Score:
        flags = render_flags(genome, workload.base_flags)
        samples: list[dict] = []
        for r in range(repeats):
            run_dir = root / "runs" / f"{genome.key()}-{r}"
            try:
                samples.append(workload.run(flags, run_dir))
            except (RuntimeError, subprocess.TimeoutExpired) as e:
                return Score(genome, float("inf"), error=str(e)[:300], generation=generation, samples=samples)
        wall = min(s["wall_s"] for s in samples)  # best of N: wall time is noisy from above, not below
        rss = max(s["rss_kib"] for s in samples)
        gc = max(s["gc_ms"] for s in samples)
        return Score(
            genome,
            fitness_of(wall, rss, gc),
            wall_s=wall,
            rss_gb=rss / (1024 * 1024),
            gc_s=gc / 1000.0,
            generation=generation,
            samples=samples,
        )

    return score


def write_outputs(out: Path, best: list[Score], history: list[list[Score]], base: list[str]) -> None:
    out.mkdir(parents=True, exist_ok=True)
    winner = best[0]
    flags = render_flags(winner.genome, base)
    (out / "best_flags.txt").write_text(
        "# Best genome of jvm_evolve (gkrost/unidrive-cli#34). Review before adopting; nothing was installed.\n"
        + "\n".join(flags) + "\n",
        encoding="utf-8",
    )
    with (out / "history.csv").open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["generation", "fitness", "wall_s", "rss_gb", "gc_s", *SEARCH_SPACE.keys(), "error"])
        for gen in history:
            for s in gen:
                v = s.genome.values()
                w.writerow([s.generation, f"{s.fitness:.4f}", s.wall_s, s.rss_gb, s.gc_s, *[v[k] for k in SEARCH_SPACE], s.error or ""])
    (out / "summary.json").write_text(
        json.dumps(
            {
                "best": {"fitness": winner.fitness, "genes": winner.genome.values(), "flags": flags},
                "weights": {"wall_s": 1.0, "rss_gb": W_RSS_PER_GB, "gc_s": W_GC_PER_S},
            },
            indent=2,
        ),
        encoding="utf-8",
    )


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--jar", type=Path, required=True, help="the unidrive fat jar to test")
    ap.add_argument("--workdir", type=Path, required=True, help="scratch directory for trees, profiles and runs")
    ap.add_argument("--out", type=Path, default=None, help="where results go (default: <workdir>/results)")
    ap.add_argument("--population", type=int, default=8)
    ap.add_argument("--generations", type=int, default=5)
    ap.add_argument("--repeats", type=int, default=2, help="runs per genome; the best wall time counts")
    ap.add_argument("--mutation-rate", type=float, default=0.2)
    ap.add_argument("--files", type=int, default=400, help="files in the workload tree")
    ap.add_argument("--timeout", type=int, default=600, help="seconds per timed sync")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--dry-run", action="store_true", help="print the plan and the base flags; run no JVM")
    args = ap.parse_args(argv)

    base = load_base_flags()
    space = 1
    for choices in SEARCH_SPACE.values():
        space *= len(choices)
    print(f"base flags ({len(base)}): {' '.join(base)}")
    print(f"search space: {space} genomes; population {args.population}, generations {args.generations}, "
          f"repeats {args.repeats}; at most {args.population * args.generations * args.repeats} timed runs")
    if args.dry_run:
        print("dry run: no JVM started")
        print("baseline genome flags:", " ".join(render_flags(Genome.of(BASELINE), base)))
        return 0

    if not args.jar.is_file():
        raise SystemExit(f"--jar {args.jar} does not exist")
    check_workdir(args.workdir)
    out = args.out or (args.workdir / "results")
    workload = Workload(args.jar, args.workdir, base, args.files, args.timeout)
    rng = random.Random(args.seed)
    scorer = make_scorer(workload, args.repeats, args.workdir)
    best, history = evolve(rng, args.population, args.generations, args.mutation_rate, scorer)
    write_outputs(out, best, history, base)
    b = best[0]
    print(f"best: fitness {b.fitness:.3f} wall {b.wall_s} rss_gb {b.rss_gb} gc_s {b.gc_s} genes {b.genome.values()}")
    print(f"results in {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
