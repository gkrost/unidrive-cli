# JVM flag evolution (gkrost/unidrive-cli#34)

`jvm_evolve.py` searches the JVM flags of one unidrive workload with a small genetic algorithm. It is a
measurement tool. It installs nothing: the best flags are written to a file for review.

## What it searches

Five genes, on top of the base flags in `dist/launcher/jvm-flags.txt` (`-Xms` and `-Xmx` are genes and are dropped
from that base):

| Gene | Choices (the first is the production value) |
|---|---|
| `gc` | G1, Parallel, Serial, Z |
| `xmx` | 2g, 1g, 3g, 4g |
| `xms` | 64m, 0 (omit), 256m |
| `xss` | (omit), 512k, 2m |
| `codecache` | (omit), 128m, 240m |

432 genomes. Generation 0 always contains the production genome.

## What it measures

Each genome gets a fresh localfs mirror profile in its own scratch config directory. One timed `unidrive sync` runs
under the candidate flags. The tree is 400 files by default: 1 KiB each, with every eighth file 64 KiB. Measured per
run:

- wall time (`/usr/bin/time`, `%e`),
- peak RSS (`/usr/bin/time`, `%M`),
- total GC pause time (`-Xlog:gc`, the `Pause ... <n>ms` lines).

Fitness, lower is better:

    fitness = wall_s + 0.5 * peak_rss_GB + 1.0 * gc_pause_s

Each genome runs `--repeats` times (default 2). The best wall time, the highest RSS and the highest GC pause count,
because noise shows up as slower runs, not faster ones.

The weights are a judgement, not a measurement. They are in `W_RSS_PER_GB` and `W_GC_PER_S`, and `summary.json`
records them.

## Running it

```bash
# Plan and base flags only; starts no JVM.
python3 scripts/dev/jvm-tune/jvm_evolve.py --jar <unidrive.jar> --workdir <scratch dir> --dry-run

# A real search. Pick a fresh --workdir; it must not be a unidrive config, state or cache directory.
python3 scripts/dev/jvm-tune/jvm_evolve.py --jar <unidrive.jar> --workdir <scratch dir> \
    --population 8 --generations 5 --repeats 2 --files 400 --seed 1

# Unit tests (no JVM).
python3 -m unittest discover -s scripts/dev/jvm-tune -p 'test_*.py'
```

A full default run is at most 80 timed syncs. Results go to `<workdir>/results/`:

- `best_flags.txt`: the winning flags, one per line, in the format of `jvm-flags.txt`,
- `history.csv`: every evaluated genome by generation, with its fitness and errors,
- `summary.json`: the winner, its genes, and the weights used.

## Safety

- `--workdir` is refused if it overlaps `~/.config/unidrive`, `~/.local/share/unidrive` or `~/.cache/unidrive`, or if
  it is non-empty and was not created by the tool (the marker file `.jvm-evolve-workdir`).
- The profile is created with `-c <workdir>/…`, so the live config and state are never read or written.
- The tool never edits `jvm-flags.txt`, the launchers, systemd units or the running daemon.

## Known limits (read before trusting a result)

- Single machine, single workload (localfs mirror sync). The daemon's idle behaviour and the Internxt upload path are
  not measured here.
- Two repeats is noise-level evidence. Confirm a winner with more repeats and a second workload before adopting it.
- The GA is only as good as the fitness. Start with a short run to check the plumbing, then decide the weights.
- JDK options that change the JVM build (for example the AppCDS archive) are not in the search space; they belong in
  `scripts/dev/CdsBench.java` and issue #34's wave 2.
