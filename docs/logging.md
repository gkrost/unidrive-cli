# Engine log

The engine writes `unidrive.log` (Windows: `%LOCALAPPDATA%\unidrive\unidrive.log`; Linux: `~/.local/share/unidrive/unidrive.log`),
rolled at 10 MB per file, five files, 50 MB in all (`core/app/cli/src/main/resources/logback.xml`). The logger
`org.krost.unidrive` is at DEBUG; the console shows WARN and above. Every line passes through `UrlRedaction`
(`RedactingMessageConverter`), which keeps scheme, host and path of a URL and drops its query, so a pre-authenticated URL
that an exception quotes does not reach the file. Every line carries `[build] [profile] [scan]`; a grep of `scan=<id>` or
`[<id>]` gives the slice of one sync pass.

## Per-file events

One shared helper, `org.krost.unidrive.engine.FileEvents` (`core/app/core`), writes the events of a file's way through
the engine. All of them go to the logger `org.krost.unidrive.FileEvents`, so a single logger setting switches them.

```
file_event phase=<phase> path="<path>" [bytes=<n>] [ms=<n>] [retries=<n>] [key=value ...]     DEBUG
file_trace phase=<phase> path="<path>" [bytes=<n>] [key=value ...]                              TRACE
file_summary scan=<id> remote_changes=<n> uploads=<n> upload_bytes=<n> upload_ms=<n> upload_failed=<n> retries=<n> hash_bytes=<n> hash_ms=<n>     INFO
```

- `path` is always quoted (`"` and `\` escaped, control characters as `\n`, `\t`, `\uXXXX`), in the engine's own spelling
  (sync-relative, leading slash), and passed through `UrlRedaction`. Other values are numbers or words; a value with blanks is quoted.
- `bytes` is the size of the file's content that the phase concerns; `ms` the wall time of the phase; `retries` the earlier
  failed attempts of this upload by this process (a provider's own retries inside one call, for example on an HTTP 429,
  are not visible to the engine and are not counted).
- Events never carry exception text, tokens or URLs. A failure names the exception class (`error=ProviderException`).
- DEBUG: one to four lines per file and phase, never one per chunk. TRACE: `upload_progress`, once per 4 MiB and at the end.
- `file_summary` is INFO, once at the end of a sync pass that moved or saw something, and sums the pass: the same numbers a
  script gets by adding up the `file_event` lines of that `scan`. `remote_change` lines are capped at 2000 per pass; the
  summary says `remote_change_lines_suppressed=<n>` when the cap held lines back.

| `phase`            | written when                                                                                  | where |
|--------------------|-----------------------------------------------------------------------------------------------|-------|
| `pickup`           | the local watcher hands a debounced path to the next pass                                     | `LocalWatcher` |
| `remote_change`    | the cloud reports a file that is new (`state=new`), differs from the row (`changed`) or is gone (`deleted`) | `SyncEngine.logRemoteChanges` / `RemoteEnumeration` |
| `upload_start`     | before `provider.upload`; `bytes` is the size on disk, `remote=` when the target path differs | `SyncEngine.applyUpload` / `MountEngine.uploadFromCache` |
| `upload_done`      | the provider answered (the cloud confirmed the bytes); `ms` is the whole call                 | `SyncEngine.applyUpload` / `MountEngine.uploadFromCache` |
| `upload_failed`    | the upload threw; `ms` until the failure, `error=<class>`                                     | `SyncEngine.applyUpload` / `MountEngine.uploadFromCache` |
| `hash_done`        | the SHA-256 of the sent bytes was recorded; `via=provider` (taken from the send pass, no read) or `via=file` (one more read, `ms` is its time) | `SyncEngine` / `Transfers` |
| `upload_confirmed` | the row of the uploaded file was written                                                      | `SyncEngine.applyUpload` / `MountEngine.uploadFromCache` |
| `upload_progress`  | TRACE only                                                                                    | `SyncEngine.applyUpload` / `MountEngine.uploadFromCache` |
| `download_start`, `download_done`, `download_failed` | provider call begins, returns staged plaintext, or throws | `Transfers` (mirror and mount) |
| `cache_store` | validated download placed in the mount cache | `MountEngine` |
| `hydrate_served`, `hydrate_failed` | cache readiness request returns or throws; `cache_hit` distinguishes warm and cold requests | `MountEngine` |
| `cache_evict` | a cache file was actually deleted; `reason=budget` or `reason=dehydrate` | `MountEngine` / `HydrationImpl` |
| `source_read`, `encrypt_done`, `encrypt_failed` | Internxt's source encryption pass completes or fails | `InternxtProvider` |
| `download_progress` | TRACE ciphertext bytes received, at coarse intervals and the declared size | `InternxtProvider` |
| `upload_skipped` | mount cache content already matches the cloud | `MountEngine` |

Reading a run: `grep 'file_event phase=upload_done'` gives bytes and wall time per file; the sum of `bytes` over `upload_done`
lines against the process's own read and write counters tells the amplification (#393); `hash_done via=file` counts the
files the engine had to read a second time.

### Summarizing a captured run

```sh
python scripts/dev/file-events.py run.log run.log.1
python scripts/dev/file-events.py --per-file run.log
python -m unittest discover -s scripts/dev -p test_file_events.py
```

Supply only the files from the run being measured, once each. The tool emits JSON grouped by phase (and optionally path),
ignores TRACE progress and INFO summaries, and rejects malformed event records. `byte_samples` and `duration_samples`
say how many events supplied a measurement: zero samples means unknown, not zero work. `recovered_retries` sums prior
failed engine attempts on successful transfers only; failure events are counted separately. Mount download retry history
survives successive requests within the process. Histories are bounded and reset on restart, so these are not lifetime totals.

Mount operations write an INFO summary per hydration, upload or eviction, and enumeration writes one per changed poll.
Download, hydration and cache totals use `<phase>_count`, `<phase>_bytes` and `<phase>_ms`; upload summaries retain the
existing fields. A mount summary's `scan` identifies the operation type, not a unique request. Use the log's profile and
scan context to select a run before combining files; `--per-file` groups identical paths across the supplied logs.

Interpret the phases separately:

- `upload_start` and `download_start` carry the expected content size, not completed bytes. Use the corresponding
  `*_done` events for completed transfer totals.

- `download_done` measures the provider call and its plaintext receipt. A subsequent validation or cache placement can
  still fail, producing `hydrate_failed`. Partial failed downloads have no byte measurement at this layer.
- `hydrate_served` means the cache path is ready, with its current full file size. It does not measure the bytes a client
  subsequently reads. A warm hit adds served bytes but no downloaded bytes. Its duration includes lock waiting.
- `source_read` and `encrypt_done` describe the same Internxt streaming pass and overlap. Their time is also inside
  `upload_done`; do not add the durations. Other providers need no encryption pass and do not emit these events.
- Durations are summed operation milliseconds, not elapsed run time: transfers can overlap. Byte counts are content
  bytes, not socket traffic or process disk counters. TLS, metadata, failed partial transfers, provider-internal retries
  and unrelated process I/O explain differences from external samplers. TRACE download progress is cumulative per
  attempt; it is not additional completed bytes.
- `cache_evict` covers budget reclamation and explicit dehydration, not every deletion caused by remote replacement,
  corruption cleanup or unlink. Refused eviction and an already absent file produce no successful eviction event.

The logger remains bounded by log rotation; capture all rotated files before they expire. Remote-change detail remains
capped per pass, with suppression recorded in the summary, so a truncated run cannot supply an exhaustive item audit.

### Writing an event from another module

```kotlin
FileEvents.event(FileEvents.Phase.CACHE_EVICT, path, bytes = freed, ms = tookMs, extra = listOf("reason" to "unpin"))
```

Pass a `FileEventTally` as `tally =` to have the event counted in a pass summary (the sync engine owns one). Keep the rate to a
line per file and phase; byte-level detail goes through `FileEvents.trace`, guarded by `FileEvents.traceEnabled()`.

To see TRACE, run the daemon with its own logback configuration (`-Dlogback.configurationFile=...`) that sets
`<logger name="org.krost.unidrive.FileEvents" level="TRACE"/>`.
