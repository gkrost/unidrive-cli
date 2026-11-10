#!/usr/bin/env python3
"""Writes the 323-file / 113,368,230-byte workload shape of gkrost/unidrive-cli#563 (8 files of 2.0-8.4 MB, 315 of
53 KB-4.2 MB, median about 234 KB) with seeded random bytes. usage: perf_dataset.py DIR"""
import os, sys, random

TOTAL = 113_368_230
rng = random.Random(567)
big = [int(rng.uniform(2.0e6, 8.4e6)) for _ in range(8)]
rest_total = TOTAL - sum(big)
# 315 sizes, log-uniform between 53 KB and 4.2 MB, then scaled so the sum is exact
raw = [min(4_200_000, max(53_000, rng.lognormvariate(12.3, 0.45))) for _ in range(315)]
scale = rest_total / sum(raw)
small = [min(4_200_000, max(53_000, int(x * scale))) for x in raw]
i_max = max(range(len(small)), key=small.__getitem__)
small[i_max] += rest_total - sum(small)
sizes = big + small
assert sum(sizes) == TOTAL and len(sizes) == 323, (sum(sizes), len(sizes))
d = sys.argv[1]
for i, n in enumerate(sizes):
    sub = os.path.join(d, f"d{i % 12:02d}")
    os.makedirs(sub, exist_ok=True)
    with open(os.path.join(sub, f"f{i:04d}.bin"), "wb") as f:
        f.write(rng.randbytes(n))
print(f"{len(sizes)} files, {sum(sizes)} bytes, median {sorted(sizes)[161]}")
