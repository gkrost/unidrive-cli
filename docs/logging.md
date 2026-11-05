# Engine log

The engine writes `unidrive.log` (Windows: `%LOCALAPPDATA%\unidrive\unidrive.log`; Linux: `~/.local/share/unidrive/unidrive.log`),
rolled at 10 MB per file, five files, 50 MB in all (`core/app/cli/src/main/resources/logback.xml`). The logger
`org.krost.unidrive` is at DEBUG; the console shows WARN and above. Every line passes through `UrlRedaction`
(`RedactingMessageConverter`), which keeps scheme, host and path of a URL and drops its query, so a pre-authenticated URL
that an exception quotes does not reach the file. Every line carries `[build] [profile] [scan]`; a grep of `scan=<id>` or
`[<id>]` gives the slice of one sync pass.

## Per-file events (gkrost/unidrive-cli#394)

One shared helper, `org.krost.unidrive.engine.FileEvents` (`core/app/engine-core`), writes the events of a file's way through
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
| `remote_change`    | the cloud reports a file that is new (`state=new`), differs from the row (`changed`) or is gone (`deleted`) | `SyncEngine.logRemoteChanges` |
| `upload_start`     | before `provider.upload`; `bytes` is the size on disk, `remote=` when the target path differs | `SyncEngine.applyUpload` |
| `upload_done`      | the provider answered (the cloud confirmed the bytes); `ms` is the whole call                 | `SyncEngine.applyUpload` |
| `upload_failed`    | the upload threw; `ms` until the failure, `error=<class>`                                     | `SyncEngine.applyUpload` |
| `hash_done`        | the SHA-256 of the sent bytes was recorded; `via=provider` (taken from the send pass, no read) or `via=file` (one more read, `ms` is its time) | `SyncEngine` / `Transfers` |
| `upload_confirmed` | the row of the uploaded file was written                                                      | `SyncEngine.applyUpload` |
| `upload_progress`  | TRACE only                                                                                    | `SyncEngine.applyUpload` |
| `download_done`, `hydrate_served`, `cache_evict` | named for the download, hydration and cache code; written with the same helper | see below |

Reading a run: `grep 'file_event phase=upload_done'` gives bytes and wall time per file; the sum of `bytes` over `upload_done`
lines against the process's own read and write counters tells the amplification (#393); `hash_done via=file` counts the
files the engine had to read a second time.

### Writing an event from another module

```kotlin
FileEvents.event(FileEvents.Phase.CACHE_EVICT, path, bytes = freed, ms = tookMs, extra = listOf("reason" to "unpin"))
```

Pass a `FileEventTally` as `tally =` to have the event counted in a pass summary (the sync engine owns one). Keep the rate to a
line per file and phase; byte-level detail goes through `FileEvents.trace`, guarded by `FileEvents.traceEnabled()`.

To see TRACE, run the daemon with its own logback configuration (`-Dlogback.configurationFile=...`) that sets
`<logger name="org.krost.unidrive.FileEvents" level="TRACE"/>`.
