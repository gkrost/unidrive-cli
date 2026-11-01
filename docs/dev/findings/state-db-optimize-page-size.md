# state.db: `PRAGMA optimize` and a larger `page_size`, measured

The last two open questions of the state database work: does `PRAGMA optimize` (planner statistics) help, and does a
`page_size` larger than 4096 help, now that the writer fast path, WAL, the reader connections and the schema trims are
in. Answer in short: `PRAGMA optimize` at close is cheap and harmless and helps one query; the variant SQLite suggests
for long-lived connections (`PRAGMA optimize=0x10002` at open) makes the merge 3-8x slower with the bundled SQLite and
must not be used as is; a larger page size gives no merge gain, only faster full-table scans, and is not worth a
rewrite of existing files.

## Method

- A probe in the style of `readerLatencyProbe` (same data shape, same merge replay, same three readers): a fresh
  `state.db` in a temp folder, seeded with N rows (`/small/f0..19` plus folders of 1,000 files), then **merge 1** (the
  merge replay over every row, chunks of 1,000 rows, while `getSyncState`, `listDirectChildren` of a 20-child folder and
  `getEntry` run every 10 ms on the reader connections), then the variant under test, `close()`, a new `StateDatabase`
  on the same file (a daemon restart), the query plans and timings of 16 hot queries on a reader, and **merge 2** with
  the readers again. Merge 2 is the one that runs with whatever the variant left (statistics, page size).
- Row sets: 200,000 narrow rows (paths like `/d5/f5`, 2-character hashes, the shape of `readerLatencyProbe`), 200,000
  wide rows (70-character paths, 64-character hashes: 105 MB file), and 600,000 narrow rows (110 MB file).
- Every configuration ran 3 times, the configurations interleaved round by round. Tables give the median and
  `[min-max]`. The machine carried other builds, VMs and daemons: the same configuration varied up to 2x between rounds,
  so differences inside the spreads are noise. Point-query times are the median of 21 calls on a quiet database.
- The bundled SQLite is built with STAT4. The probe is not part of the repository.

## `PRAGMA optimize`

Variants (200,000 narrow rows unless stated):

| variant | cost of the pragma | statistics written | merge 2 writer | merge 2 reader p99 / max | one prepared lookup, new binding per call |
|---|---:|---|---:|---:|---:|
| none (today) | - | none | 13.1 s [12.8-22.5] | 0.57 / 4.1 ms | 37.5 us [34.7-81.2] |
| `PRAGMA optimize` before close | 4.1 ms [3.8-4.2] | `sqlite_stat1` | 13.5 s [13.4-16.7] | 0.60 / 5.0 ms | 37.2 us [37.1-41.6] |
| `PRAGMA optimize=0x10002` after open | 211 ms [192-415] | `sqlite_stat1` + `sqlite_stat4` | 109 s [85-216] (runs without statistics in the same hour: 23-45 s) | 2.0 / 25 ms | not measured |
| full `ANALYZE` before close | 106 ms [97-154] | `sqlite_stat1` + `sqlite_stat4` | 56.8 s [46.0-78.1] | 1.1 / 14 ms | 173 us [165-183] |
| `PRAGMA analysis_limit=400` then `optimize=0x10002` after open | 47 ms [33-58] | `sqlite_stat1` | 19.0 s [15.4-23.7] | 1.3 / 20 ms | 42.7 us [33.8-81.2] |

At 600,000 rows: `PRAGMA optimize` before close took 100 ms [54-128]; merge 2 ran 97.8 s [84.1-146.1] with it and
75.6 s [73.6-123.8] without (overlapping spreads); reader p99 2.0 vs 0.8 ms, max 36 vs 9 ms (single outliers in both).

Plans (`EXPLAIN QUERY PLAN` on a reader, after the reopen) with `sqlite_stat1` against none:

- **Unchanged** for 13 of the 16 queries: `getEntry`, `getEntryByRemoteId`, `getEntryByRemotePath` (two seeks),
  the upsert probe, `listDirectChildren` (seek of `idx_sync_entries_dir_alive`), `getEntryCaseInsensitive`,
  `getEntriesByPrefix` and `countEntriesUnderTopLevel` (path range), `blockedRows` (`ORDER BY +path`, seek of
  `idx_sync_entries_name_block`), `pendingUploadRows`, `getEntryCount`, `foldersWithoutContentIds`.
- `countWriteUploadFailed` and `getHydratedEntryCount`: a scan of the table instead of a scan through
  `idx_sync_entries_dir_alive`; times within noise.
- `blockedRows` with a plain `ORDER BY path` (not what the code runs): seek of `name_block` instead of a walk of
  `path_alive`, 102-135 ms -> 0.07 ms. The statistics make the `+path` workaround unnecessary; it stays harmless.
- `foldersWithoutContentIds` (the `SELECT DISTINCT` of folders a mount fills content ids for): the same plan text,
  but 47 ms [40-80] -> 0.18 ms at 200,000 rows and 84 ms -> 0.21 ms at 600,000 rows. With `sqlite_stat1` showing many
  rows per folder key, SQLite skips ahead over the duplicates instead of reading every row. This data has 1,000 files
  per folder; a real drive has a few per folder, so the gain there is smaller [inferred].

Why the STAT4 variants are slow [inferred from the SQLite documentation, the symptom measured]: with `sqlite_stat4`
present, a prepared statement whose bound parameter is compared to an indexed column is compiled again at the first
step after each new binding. The statement cache then saves nothing: one lookup by remote path costs 173 us instead of
37 us, and the merge, which does that per row, runs 3-8x longer. `PRAGMA optimize` with its default mask applies an
analysis limit and writes no `sqlite_stat4`; `optimize=0x10002` and a plain `ANALYZE` analyse fully and do. The plans
with `sqlite_stat4` are the same as with `sqlite_stat1` alone (all 16 checked), so the time goes to compiling, not to a
worse plan.

## `page_size`

Fresh files created with the page size (the page size of a WAL file cannot change):

| rows | page size | file after merge 2 | merge 1 writer | merge 2 writer | reader p99 merge 1 / 2 | full scans: `countWriteUploadFailed` / `getHydratedEntryCount` / `getAllEntries` |
|---|---:|---:|---:|---:|---:|---|
| 200k narrow | 4096 | 36.1 MB | 37.6 s [25.7-52.8] | 28.2 s [23.2-43.1] | 3.0 / 1.1 ms | 124 / 105 / 2,087 ms |
| 200k narrow | 8192 | 35.8 MB | 28.5 s [28.4-39.5] | 25.7 s [20.6-26.6] | 1.2 / 1.0 ms | 71 / 64 / 1,921 ms |
| 200k narrow | 16384 | 35.5 MB | 31.4 s [22.2-48.6] | 23.0 s [19.9-28.8] | 2.1 / 0.9 ms | 56 / 56 / 2,125 ms |
| 200k wide | 4096 | 106.0 MB | 29.8 s [29.1-31.9] | 25.5 s [25.5-39.7] | 1.1 / 1.4 ms | 181 / 157 / 2,113 ms |
| 200k wide | 8192 | 104.4 MB | 27.1 s [26.9-28.5] | 24.1 s [23.7-27.0] | 1.1 / 1.2 ms | 94 / 93 / 1,956 ms |
| 200k wide | 16384 | 103.4 MB | 33.1 s [26.4-61.6] | 39.3 s [22.5-63.4] | 1.3 / 2.2 ms | 81 / 78 / 2,062 ms |
| 600k narrow | 4096 | 109.8 MB | 76.9 s [68.3-192.4] | 75.6 s [73.6-123.8] | 0.7 / 0.8 ms | 294 / 288 / 8,434 ms |
| 600k narrow | 16384 | 107.5 MB | 78.8 s [76.5-85.3] | 86.6 s [56.9-98.0] | 0.9 / 1.5 ms | 165 / 145 / 6,244 ms |

- Merge: no gain from 8192 or 16384 at any size; every difference sits inside the spreads. Point reads (`getEntry`,
  the lookups, listings, ranges: 0.05-2 ms) are unchanged.
- File: 1-2.5% smaller.
- Full-table scans: about half the time with 8192 or 16384. In the engine those are `countWriteUploadFailed` (doctor),
  `getHydratedEntryCount`, the alive count at open (`getEntryCount`, 49 -> 25 ms at 600k) and the full loads
  (`getAllEntries`: within noise at 200k, 8.4 -> 6.2 s at 600k).
- The earlier spike measured -20% / -32% merge time for 8192 / 16384 on a fresh file, on its own JDBC replay (another
  index set, WAL with `synchronous=NORMAL`). This probe runs the engine's `StateDatabase` and does not reproduce it.

Changing an existing file:

- In WAL, `PRAGMA page_size=16384; VACUUM` leaves the page size at 4096, silently (checked).
- The way that works: `journal_mode=DELETE`, `page_size=16384`, `VACUUM`, `journal_mode=WAL`, which needs the file to
  itself (a CLI reader makes the switch fail as busy) and writes a full copy. 1.4 s [1.4-1.7] for 34 MB, 2.5 s
  [2.2-3.1] for 101 MB wide, 3.6 s [3.0-3.9] for 110 MB; a plain `VACUUM` in WAL costs as much or more (1.4-1.6 s,
  3.5 s, 5.6 s).
  The earlier spike measured 17.6 s for the 434 MB file of a real drive.
- Existing files have had their one compaction already (`compactOnce` records it), so a page size change would be a
  second full rewrite, at a daemon start, for faster full scans only.

## Recommendation

1. **Page size: keep 4096.** No merge or reader gain; the scan gain does not pay for a second exclusive rewrite of
   every existing file. If it is wanted later, only for new files (a `PRAGMA page_size` before the first table is
   created), with no migration.
2. **`PRAGMA optimize`: optional, and only in its default form.** Running it on the writer in `close()` before the
   checkpoint is safe for existing files (it only adds `sqlite_stat1`, which an older build reads as well), costs 4 ms
   at 200,000 rows and about 0.1 s at 600,000, and leaves the merge and the readers as they are; its one measured
   benefit is `foldersWithoutContentIds`. It is not a clear win on the merge or reader latency, so it is proposed here,
   not built. A daemon closes rarely, so the statistics come from the previous run.
3. **Never** `PRAGMA optimize=0x10002` without an `analysis_limit`, and never a plain `ANALYZE`, on state.db: with the
   bundled SQLite's STAT4 they make every bound lookup recompile and the merge 3-8x slower. If statistics at open are
   wanted, `PRAGMA analysis_limit=400` must come first (47 ms, no `sqlite_stat4`). A test that a file never gains a
   `sqlite_stat4` table would guard this if any `optimize` or `ANALYZE` is ever added.

## Not measured

A real drive's file (narrower folders, longer names); a 372,588-row acceptance run with JFR; `PRAGMA optimize` on a
file whose statistics are stale (grown 25x since they were taken); Linux and Android.
