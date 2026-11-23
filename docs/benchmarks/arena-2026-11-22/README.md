# Benchmark arena 2026-11-22/23: raw data (Windows side)

Raw output of the Windows runs. Data set: `bench.ps1 -Phase dataset`, 323 files, 113,368,230 bytes (seeded, incompressible; the `tree/` folders are left out, they regenerate from the seed).
Delete guards were disabled for these runs (client `.settings` and engine `bulk_reap_guard` / `top_level_guard`), so guard pauses do not distort the times.

## `unidrive-windows/` (scripts/dev/bench.ps1, `-Phase all`, from unidrive-windows)
| Run folder | Profile | Build | Note |
|---|---|---|---|
| internxt-2-20261122-124753 | internxt-2 | before the main-139 deploy | first baseline |
| internxt-2-20261123-052808 | internxt-2 | main-139 (engine 0a9fb431, client 74eac600) | rerun 1 |
| internxt-2-20261123-055154 | internxt-2 | main-139 | rerun 2, par8 wire limit FAIL (1.057x > 1.05) |
| scw-bench-20261122-150634, -154813 | scw-bench (Scaleway S3) | main-139 | two runs |
| onedrive-5-20261122-160812 | onedrive-5 | main-139 (OneDrive large-file fix, engine PR #895) | the earlier -153537 run left only 2 files, incomplete, so it is not kept |

Per phase: `<phase>.json` (measurements), `<phase>.cut.log` (engine log cut, redacted), `summary.json`. Exit code 1 on several runs is the landing check seeing the sub-tree still listed (listing lag), not a failed phase.

## `internxt-desktop/` (official Internxt Drive desktop client, scripts/dev/bench-inx-desktop.ps1)
- `initial-sync.csv`: 1 Hz sampler (process tree CPU/memory/I/O, NIC) during the client's first sync.
- `rounds/`: 3 rounds, one upload then one cold download with 4 readers (`rounds-*.log`, `summary-*.json`, `r<N>-upload|download.csv`).
- `sweep/`: 2 rounds, one upload then cold reads with 1, 4 and 8 readers (`r<N>-download-x<readers>.csv`).
Log timestamps in the client's own logs are +1 h against local time.

## `console-logs/`
Console output of the runners (bench.ps1 wrappers, client runner, sweep, UniDrive internxt-2 rerun).

## Headline (internxt-2, same data set)
| Readers | Internxt client | UniDrive, main-139 (2 runs) |
|---|---|---|
| upload | 143 to 206 s | 116 s / 99 s |
| 1 | 244 to 297 s | 169 s / 178 s |
| 4 | 57.5 to 65.4 s | 42.1 s / 42.6 s |
| 8 | 32.9 to 42.2 s | 24.3 s / 22.0 s |
The client numbers were taken the evening of 2026-11-22, the UniDrive reruns the morning of 2026-11-23.
