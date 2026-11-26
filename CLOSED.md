# Closed

Things that were done before this branch started. Append new entries when an item is done; the open work is in the issue tracker.

## Slim-branch consolidation

- Strip non-MVP providers + build targets; trim dependent CLI tests
- Remove MCP module and all references
- Remove CI policing; `./gradlew check` is the only gate
- Delete Windows-only scripts; Linux-only surface
- Delete Docker test harnesses for removed providers
- Scrub MCP references from dist scripts
- Absorb CONTRIBUTING.md and CLAUDE.md into AGENTS.md
- Co-locate provider docs with provider modules
- Trim surviving ADRs, drop seven obsolete ones
- Rewrite README as Linux-power-user marketing front

## Drained from BACKLOG

- `--fast-bootstrap` adopts a matching top-level remote folder (registers the state.db row with the existing `remoteId`) instead of issuing a `mkdir` that 409s; gated to the bootstrap apply path so normal sync semantics are unchanged (BACKLOG: High)
- `RefreshRpcHandler` reserves the in-flight slot via `compareAndSet`-before-launch (placeholder swap), with launch-failure cleanup so the invariant-I6 guard can't stick; added named tests for the busy, provider_error, and shutdown reply paths (docs/dev/specs/unidrive-daemon-design.md, `RefreshRpcHandler` / invariant I6)
- Apply-phase action ordering respects parent-folder dependencies via Kahn's algorithm in `ApplyOrder.kt` (BACKLOG: High)
- Tracking-set cursor-resume no longer forces `exists=true` for `PendingDeleteLocal` rows, fixing stale-local crash-recovery (BACKLOG: Critical)
- Default-ignore-list surface (c): tracking-set `scanLocal` honours `effectiveExcludePatterns` via `TsContext` (BACKLOG: High)
- Create-collision blind-PUT-overwrite fixed on both providers via `existingRemoteId` guard and rename-on-conflict fallbacks (BACKLOG: UD-366)
- Positional `<profile>` argument on `daemon run|status|stop`, `sync`, `refresh` — works both as `-p` and positional
- `hydration.dehydrate` on unknown path returns `Failed` instead of silent `Ok`
- Hydration SPI Phase 1 — `app:hydration` module with openForRead/Write, closeHandle, hydrate/dehydrate, events flow
- Internxt permanent-failure quarantine on download 404; quarantined rows skipped until fresh delta clears flag
- Audit code/comment drift on hydration filtering — two comments rewritten, eight verified accurate
- Unhydrated folder rows no longer turn into DeleteRemote — post-detectMoves filter in Reconciler drops them
- Repo-root docs realigned to bounded doc surface; boosters analysis moved to docs/audits/
- MDC empty `build` slot renders as `--------` instead of `???????`, matching `scan` convention
- Dirty-build WARN ignores mode-only changes; dirtiness from `git diff --numstat` + untracked files
- `unidrive mount` IPC lifecycle deadlock resolved by per-profile daemon process model
- Streaming reconciliation (process pages as they arrive) across 6 commits + CLI/TOML config
- Internxt `encryptVersion` — investigation found no `02-rsa` usage; added fail-fast guard for unknown versions
- Internxt retry coverage on `putEncryptedShard`, `putEncryptedShardFromFile`, `downloadFileStreaming`
- Internxt 429 on Drive DELETE mutations routes through `retryOnTransient`
- Internxt delta path-collapse — `buildFolderPath` returns null on missing ancestor; delta filters dropped items
- Internxt state-db duplicate-`remote_id` cleanup via one-shot SQL window-function migration
- Internxt listing `status` filter selectable per call via optional parameter (default `"ALL"`)
- Internxt `Retry-After` HTTP header parsing — RFC 7231 delta-seconds and IMF-fixdate support
- Internxt JWT pre-expiry refresh margin (1 day) to avoid mid-stream 401s
- Internxt `NonCancellable` wrap on `refreshToken` verified (already covered by `RefreshableTokenLatch`)
- Internxt in-flight dedup extended to `getFileMeta`, `listFiles`, `listFolders`
- Internxt hard-coded client-header values (`clientName`/`clientVersion`/`desktopHeader`) now configurable
- Internxt request prioritization — two-lane Foreground/Background via coroutine context
- Internxt `HttpRetryBudget` wiring — per-host budgets for Drive REST and Bridge upload
- Internxt parallel listing pagination — fans out 2 page fetches per stream
- Internxt 401 → automatic refresh-and-replay on Drive REST surface
- state.db redesign: tombstones, uuid identity, parent_uuid — drop-and-rescan upgrade
- Mount-write clobbered by SyncEngine resolved by per-profile mode mutex per design spec
- Internxt resumable scan with persistent checkpoint in `scan_staging` table
- Internxt cross-session resumable scan — incomplete scans survive clean exit
- Internxt best-effort cursor advance on incomplete scan — promotes cursor unconditionally
- Internxt WebSocket change feed (socket.io wake-signal via `NotificationsClient`)
- Internxt `finishUpload` idempotency via 409 reconcile with listing-based collision detection
- Internxt local chunk-tombstone for upload resume via `UploadTombstoneStore`
- OneDrive `If-Match`/`@odata.etag` on `moveItem` + `conflictBehavior=replace` parity on uploadSimple
- OneDrive delta soft-vs-hard delete semantics with age-gap WARN
- OneDrive upload-session expiry validation — self-pruning store, probe resilience, two-attempt loop
- OneDrive `fileSystemInfo` round-trip — local mtime/ctime preserved through upload/download
- Internxt destructive-overwrite warning — opt-in `keep_overwritten=true` renames prior cloud file before replace
- Internxt `--watch` cadence collapses to `max_poll_interval` while WebSocket is healthy
- `unidrive status --all` enumerates profiles in config declaration order
- `unidrive status` is side-effect-free — gates quota probe on `CredentialHealth.Ok`
- IPC write-timeout drops Phase 2 mount clients — fixed with dedicated 4-thread transport pool
- IPC transport plan doc fixed from non-existent `:app:sync:installDist` to `:app:cli:deploy`
- Phase 2 co-daemon `Broken pipe` mid-IPC resolved by explicit subscriber-set on broadcast path
- `unidrive refresh --reset` restored via daemon RPC verb extension after Task 11 refactor
- Daemon constructed `SyncEngine` with wrong sync root (`dbPath.parent` vs configured `sync_root`) — fixed
- Tracking-set engine: Internxt provider verified end-to-end — structural assertions hold on 196k-file profile
- Tracking-set engine: OneDrive provider verified end-to-end — structural assertions hold on 3,232-file profile
- `unidrive ts` CLI unreachable — fixed via `runtimeOnly` dependency to avoid classpath cycle
- Tracking-set engine: per-profile delta-cursor persistence — incremental path safe from deletion-cascade
- Internxt delta-page ancestor-uuid drop self-healing via bounded re-fetch
- Internxt deleted-marker investigation — trashing bumps `updatedAt`; `/files` status-flip lag is the real blocker
- OneDrive delta-endpoint 410-Gone self-heals into full re-enumeration in tracking engine
- `ts` CLI never authenticated provider — fixed by adding `authenticateAndLog()` to sync/claim commands
- Tracking-set Internxt trash→reap live-verify — trashing bumps `updatedAt`; flat `/files` listing lags ≥60 min

## Drained from BACKLOG (issue closed as completed)

- OneDrive chunked-upload resume corrupts a file changed between attempts — session keyed on path only (silent data-loss)
- Hydration open-set is a non-thread-safe map iterated across connections — dehydrate races an open writer (data-loss + CME)
- OneDrive download returns 404 itemNotFound for a file present in the listing → mount hydrate-on-read EIO (cloud-only files unreadable)
- `applyDeleteRemote` swallows ALL provider errors and marks the row TRASHED anyway — stranded remote + delete never retried
- OneDrive delta conflates a soft `removed` (permission revocation) with a hard delete → reaps a still-valid local copy (data-risk)
- Tracking-set adopt-on-content-match silently degrades to size-only for Internxt (null hash) — different-content same-size files adopted as identical
- Mount doesn't survive daemon restart — operations return EIO until mount is manually remounted
- FUSE mount: `create`/`mknod`/`fsync` not implemented — all file-creation paths fail with ENOSYS
- Legacy SyncEngine trips the deletion safeguard on the internxt_alice_example profile, daemon stays failed
- OneDrive 410 Gone resync handling
- OneDrive `file.hashes` in local change detector
- OneDrive `If-Match` precondition on `createUploadSession`
- OneDrive refresh `downloadUrl` on `assertNotHtml`
- XDG-user-dir locale aliasing across `Pictures`/`Bilder`/`Imágenes`/`Images`/…
- `status` and `status --all` enumerate different profile sources (orphan-profile-dir divergence)
- `status` / `status --all` don't reflect the tracking-set engine
- OneDrive delta loses sight of a path the engine itself just wrote ("not in delta, marking deleted" loop)
- `IpcServer` broadcast channel (capacity 256) silently drops sync/progress events when a subscriber lags
- Hydration `unknown path` maps to two different errnos depending on the verb (EIO vs ENOENT)
- Bulk directory creation issues one synchronous remote round-trip per directory — large-tree copies into the mount are slow and abort wholesale on any failure
- Tracking-set live-test runtime is impractical for routine CI
- Tracking-set engine: migration / coexistence with legacy `state.db`
- Pre-reconcile empty-sync_root guard must differentiate "rehydrate intent" from "wrong sync_root"
- Typed `FolderNotEmpty` provider exception for `HydrationImpl.rmdir` (namespace-verbs R2)
- `mkdir` parent-missing maps to ENOENT instead of EIO (namespace-verbs R3)
- Cache-file eviction on `unlink` / `rmdir` (namespace-verbs R5)
- `unidrive daemon status` should refuse / warn when lock-holder mode is not `daemon`
- Daemon doesn't auto-poll for remote changes — mount view stays stale until operator runs `refresh`
- `unidrive ls` (live query) and the FUSE mount (state.db) disagree during the stale window
- `unidrive status` no-default-profile fallback shows misleading "Local Filesystem" row
- Build-time test that `logback.xml` is XML-well-formed
- Build-time test that `BuildInfo.DIRTY` correctly handles mode-only diffs
- Hydrated-but-locally-missing rows in `--download-only` mode should re-download, not silently loop
- Tracking-set live test: JWT/OAuth-refresh path not structurally exercised
- Tracking-set live test: throttling / 429-storm handling path not exercised
- `RefreshRpcHandler` exception-message JSON escape covers quotes only
- `unidrive ts <sub>` ignores the global `-p` option
- Stale `EXPERIMENTAL — not yet verified against real Internxt/OneDrive` warning in `ts`
- Suggested-command output uses `<path>` placeholders instead of real values (not copy-pasteable)
- Path normalization (NFC) across sync
- Provider SPI hardening with two providers
- `.lock.pid` rendered as `mode (no-mode)` for legacy pid-only sidecars — now named `legacy (pre-mode-mutex)` in the `daemon status`/`daemon stop` refusal
- Pending-upload predicate (`remoteId == null && isHydrated == true`) was enforced informally across many call sites
- `uploadFromCache` read the local watermark after the upload — redundant re-upload
- Hydration-cache file was deleted inside the reap DB transaction — filesystem operation in a `db.batch{}`
- Gradle daemon poison: `java.io.EOFException` from `SerializableTestResultStore` with 0-byte results
- `unidrive refresh` against a profile with a pre-existing delta cursor returned only the incremental delta, not a full enumeration
- Reach the 5+5+2 live-integration smoke target (OneDrive 9, Internxt 10, sync 2+ test methods, all behind `UNIDRIVE_INTEGRATION_TESTS`)
- The Linux systray UI calls `sync.subscribe` after connecting (was: the event stream needed an explicit client-side subscribe; drained from BACKLOG: Low)
- Tracked data protected during reconciliation — an adoption/refresh can no longer clobber rows a concurrent apply is acting on (reconciliation guard, `fix: protect tracked data during reconciliation`; BACKLOG: Critical)
- Socket-path name hashing and the hydration cache root share `IpcServer.canonicalDiskName` — verbatim for short profile names, 8-hex SHA-1 for long ones, with migration of an existing long cache directory (BACKLOG: Medium)
- `unidrive sync` as a daemon client — superseded, not planned: a profile is a mirror or a mount (`docs/adr/independent-profiles.md`), so the daemon-decomposition phase-2 conversion has no subject (BACKLOG: Medium)
- The daemon auto-spawns on the first client connection (`DaemonAutospawn.ensureDaemonRunning`), designed for both profile modes (daemon-decomposition phase 3; BACKLOG: Medium)
- The enumeration merge holds `StateDatabase.batch{}` in bounded chunks, so a full enumeration no longer stalls a mount `ls` behind the `@Synchronized` lock (BACKLOG: Low)
- Mount `rm` label vs disposition: TRASHED/DELETED are documented as encoding which side noticed the deletion (remote delta cascade vs engine-side), not recoverability — the schema header and `markDeleted` state it (BACKLOG: Low)
- Internxt multipart upload with per-part resume and configurable concurrency (archive#578, engine PR archive#1225; drained from BACKLOG: Deferred; `daemon.status` reports `upload_concurrency`, null for a provider that does not upload in parts)
- OneDrive `HttpRetryBudget` per-provider override (archive#131, drained from BACKLOG: Medium)
- Local-dev redeploy and co-daemon observability gaps, `scripts/dev/redeploy-local.sh` (archive#150, drained from BACKLOG: Low)
- Regression test for ts-CLI provider authentication (archive#168, drained from BACKLOG: Low)
- Retry budget per operation: closed as not planned (archive#172, drained from BACKLOG: Cross-cutting)

## Closed in this repository (issue closed as completed)

Issue and pull request numbers are of gkrost/unidrive-cli.

- Release build: a reproducible shadow jar (fixed flags) and a CycloneDX SBOM attached to the release (#19, PR #93)
- `profile remove` / `logout` count only uploads that can still happen as `uploads_pending`, not `local:` rows the ignore rules keep local (#86, PR #89)
- localfs: a `root_path` is checked against every sync root (#104); S3: a listing asks for url-encoded keys, so one key with an XML-invalid character no longer fails the whole listing (#105; both PR #106)
- `InFlightDedup`: a caller that loses the `putIfAbsent` race returns (its lazy child is cancelled), and a joiner that sees the starter's cancellation while its own job is active takes the key again (#77, PR #102)
- Hydration: a short download is accepted as the new remote size only when the provider's metadata confirms that size; a cancelled read stays a cancellation (history row `interrupted`) (#80, PR #109)
- Mount: an upload that used all its attempts is offered again in the same process, five rounds 5, 15, 30, 60 and 60 minutes after the previous one (#70, PR #112)
- `relocate`: two same-size files with different hashes are a conflict, not the same file (#62, PR #636)
- `relocate`: each transfer gets its own scratch file, not one named by the leaf (#63, PR #647)
- `relocate` is switched on again: `RELOCATE_ENABLED = true`, the full command path is tested localfs to localfs (delete scope, failed transfer, same-size different bytes, same-named files, hostile names) (#653)
- S3: a move onto an existing destination no longer silently overwrites it (#176, PR #510)
- Mount: a local folder delete cannot remove a child another device added moments before — the write journal orders the delete after the merge (#350, PR #383)
- Mirror: a remote rename into a locally-deleted folder no longer deletes the folder — a move is a delete-blocker (#365, PR #372)
- ci: the Pages deploy on main no longer answers 404 (#366, PR #382)
- detekt findings in `MigrateConvertCommand` no longer paint every PR's checks red (#373, closed once fixed and baselined)
- state.db runs `PRAGMA optimize` before close (#384, PR #396)
- A profile with no credentials in its folder reports needs-sign-in as a state, not a plain start error (#387)
- The reference-comparison bench findings, closed after their follow-ups landed: the hydration cache never evicts (#391), hydration 3–4× slower with 1.8× payload (#392), uploads re-read the source 3–4× (#393), no per-file logging (#394); follow-ups #404, #415, #416, #417
- The hydration cache ships enabled, and a clean copy survives its read handle closing (#404, PR #406)
- Internxt: tombstone cleanup no longer deletes another upload's resume file (#412, PR #422)
- Internxt: the bridge fileId/bucket persists, so a download skips the Drive meta call after a restart (#414, PR #424)
- IPC: `hydration.stat` answers one row's state without listing the folder (#415, PR #421)
- Reply lines log JSON response sizes and the local queueing time separately (#416)
- OneDrive: a pin asks for the download URL the listing already carried, not a serial metadata GET per file (#417)
- test(relocate): the deepest-first delete-pass `ArrayIndexOutOfBoundsException` did not reproduce (#419, PR #457)
- Docs and comments realigned with the code (#429, PR #430)
- Tracking engine: the delta cursor is saved only after the plan is applied; write-guard and per-tombstone scan gaps closed (#433, PRs #467, #471, #472; verified on main)
- Internxt download: a connection dropped mid-body is retried, not taken for a short stored object (#434)
- `HttpRetryBudget` halves concurrency once per throttle storm, not on every throttle (#435, last item PR #546)
- Daemon: the version snapshot and the refused-upload hash run off the pass's event loop, so the serve scope no longer blocks on them (#436, PR #644)
- S3 delete: a vanished file no longer falls through to a recursive prefix delete (#447, PR #466)
- Sync: a file restored from the cloud recycle bin is refilled in the same pass (#448, PR #465)
- Streaming reconcile no longer re-plans every new local upload once per remote page (#449)
- Internxt finish-reconcile no longer adopts another upload's bucket entry (#450, PR #469)
- Sync audit addendum: excluded cloud files lost with a deleted folder, download over a mid-transfer edit, fail-open folder guard, 6 more (#451, PR #515)
- Hydration audit addendum: local-only rename strands the file on failure, events collector stall, dehydrate race, `name_clash` race, 3 more (#452, closed when its items landed)
- ci: a Maven Central 429 no longer fails the ignore-oracle job and its report step (#458, PR #459)
- `daemon.status` carries per-verb request counters (#460, PR #461)
- Remote trash: empty or selectively purge — Internxt trash counts toward quota (#500, PR #506)
- build: the distribution tasks order after `copyCoroutineDebugAgent` (#504, PR #505)
- JFR redaction covers `UNIDRIVE_VAULT_PASS` and a `--vault-pass` argument (#522, PR #536)
- telemetry: `close()` no longer drops the Stop op on a full queue (#530, PR #558)
- Concurrency: no sleep under the StateDatabase monitor; a failed lock call is not contention; the IPC transport pool closes (#531, PR #555)
- Pacer: bytes actually moved on a monotonic clock; Pass 2 on a bounded worker pool (#562, PR #565)
- HTTP: one shared OkHttp engine for every provider — CIO opened a connection per request (#566, PR #589; stale keep-alive PR #601, DNS/audit PR #624)
- Perf: the cold-JVM profile measured, scripts and group-commit landed (#567, PRs #592, #604, #623)
- S3 tests: request-pattern tests on a logging stub keep connection-per-request down (#568, PRs #579, #626; the pattern test runs via #643)
- S3 download: one GET, no HEAD before it (#569, PR #603)
- S3 single-part PUT carries Content-MD5 from the existing proof read (#570, PR #628)
- RequestId plugin: its lines and ids are built off the request hot path (#571, PR #614)
- CLI one-shots arm a per-install startup cache beside the jar (#572, PR #619)
- S3 create: decide create vs overwrite from the sync's own listing — the HEAD guard stays the default, a listing-backed write is opt-in (#573, PR #613)
- HTTP/2 for the data plane — closed as not planned (#577)
- Replay: a replayed create that meets its own bytes at the target settles as done (#580, PR #586)
- A corrupt state.db is told from an older schema; the mount daemon moves it aside and rebuilds (#581, PR #590)
- localfs: lone-surrogate names and symlinked folders are skipped with one warning (#582, #583, PR #591)
- A case-only rename within a second leaves one spelling in the cloud (#584, PR #588)
- Hydration: `unlink` of never-uploaded rows owes no cloud probe; `list` states a pending row's cache copy (#585, PR #618)
- S3: a replayed create over its own multipart / SSE-KMS object settles (#587, PR #602)
- Internxt: an already-landed upload is adopted (#594, PR #598)
- localfs: a symbolic link to a file is not re-uploaded every pass (#595, PR #600)
- telemetry: a flush answers only after the terminal snapshots of a full queue set aside are written (#597, PR #627)
- TransferHistoryTest full-queue flake: the asserts name their counters (#611, PR #627)
- IPC: the daemon serves `share.create`, and `daemon.status` says so while the provider shares (#615, PR #622)
- OneDrive work or school: the sign-in follows the profile's `authority_url`, and the begin reply carries the admin-consent URL (#617, PR #621)
- Views: every configured view is filled once at start, and a successful poll keeps the lists fresh (#631, PR #633)
- Hydration: a `locate` verb answers where the items with the given remote ids are now (#632, PR #638)

### Closed 2026-11-13 to 2026-11-26

Windows Hello, the vault and credentials:
- The unlock-helper step in the passphrase chain, vault stdin variants, the `vault.unlock` / `vault.lock` verbs and a locked daemon (#796, PR #800; the X-tra passphrase in the vault, PRs #942, #947)
- The X-tra passphrase no longer reaches child processes (#785, PR #788); `config.toml` provider secrets are protected at rest on Windows (#786, PR #792); a secret protected on another computer is refused, not sent as plaintext (#896, PR #898)
- `vault xtra-set` / `xtra-clear --json` with stable error tokens (#973, PR #985)
- IPC hardening from the 2026-11-23 audit: co-daemon passphrase env, handle cap, OAuth loopback, logout atomicity, unlock helper (#927, PR #928)

X-tra client-side encryption (revived, #710):
- age-v1 with its dependency metadata (#801, PR #820), key material owned by the content strategy (#802), ranged reads on age-v1 (#803, PR #823), CLI verbs for the age identity (#804, PR #807), `xtra_algorithm` in the config schema (#805), Tink as `tink-aes-gcm` (#834, PR #837)
- Algorithm-mismatch refusals are remembered for the run (#822, PR #832); a decrypt is staged and keeps the destination's permissions (#722, PR #727; #732, PR #735)

IPC and daemon:
- `hydration.open_read` names the etag of the served row (#707); `hydration.list` carries `changed_by` and `changed_at_ms` (#849, PR #864)
- `history.list` order, the conflict-safe `history.retry` and the host-reported `history.report` (#848, PRs #872, #937), with `count` and `first_paths` (#954, PR #956)
- `scope.get` / `scope.set` with a live scope swap (#860, PR #936); whole-sweep progress of a released delete hold (#851, PR #873)
- A request whose peer disconnects stops its provider work (#760); the IPC sockets left the temp folder (#794); `status --pending` and `quota` answer from `daemon.status` (#706, PR #918); fewer IPC round trips and bounded CLI waits (#442)
- `daemon run` applies `max_bandwidth_kbps` to mount transfers (#715, PR #720); transfer budget, upload queue depth and cache grace per profile (#72, PR #856)
- No lost log lines when processes share the log (#897, PR #904); the audit log warns once when it cannot write (#780, PR #789)

Hydration and mount:
- `hydration.rmdir` names the `not_empty` reason and child and refuses over items indexed after the listing (#717, PR #737); a stale row no longer blocks a folder the cloud emptied (#824, PR #829); a child the listing never shows no longer refuses for days (#853, PR #858); `rm -rf` of a just-emptied folder (#635)
- A size-based hydration time budget and per-provider maximum file sizes (#508, PR #920)
- A mount-side delete burst no longer starves transfers: one trash call per listed delete, batches of 50, a coalescing window (#831, PRs #833, #885, #889)
- A wiped hydration cache keeps local-only rule files (#775, PR #894); download paths keep an existing file aside, and the conflict log is guarded (#768, #779, PR #808)
- `verify` audits mount profiles and `ls` names the daemon view (#825, PR #828); hydration robustness items (#438)

Sync engine and state:
- The remaining findings of the 2026-11-14 audit #742-#749 (#841, PRs #844, #846); what is still open is #845
- A file replaced by a folder of the same name syncs (#723, PR #728); an incomplete enumeration runs no kind-change delete (#731, PR #734); conflict names are length-limited (#724, PR #725); a mirror folder rename (#713); the null-remote-id placeholder row documented and pinned (#782)
- Tombstone retention, `profile reset-state` and staging reclaim (#863, PRs #865, #869, #871)
- A `parent_gone` upload refusal is final until the user retries (#951, PR #955)
- The upload snapshot no longer resolves every pending path per refresh (#763, PR #764)
- Ignore rules: a cloud-only rule file is downloaded within the pass (#68), an older local rule file (#642), more default junk names (#757, PR #811), a lazy directory stat (#770, PR #811)
- Audit items: sync engine (#439), core IO and HTTP (#440), providers (#441), core and engine-core addendum (#454), providers addendum (#453); the StateDatabase monitor time (#53) and the dialect-seam spike (#610)

Providers:
- OneDrive: `$select` on the full enumeration (#11, PR #915); a download asks for its URL directly (#946, PR #967); a name the drive folds onto a sibling is refused naming it (#950, PR #979); the delta folder cache after an ancestor rename (#835); per-tenant concurrency (#13); the 507 refusal (#16); the Cyberduck comparison items (#42); the Personal Vault (#405)
- Internxt: upload concurrency 4 (#413, PR #923); a transient 502 on the file create is retried (#793, PR #809); `close()` during a start (#532, PR #809); the "prior file none" 409 loop (#665); a tree outside the drive root stays out of the scan loop (#862, PR #866); the security limits a compatible client inherits, documented (#648, PR #815); strict name compatibility (#29); Backups and Favorites as read-only folders (#51, #52, #888)
- S3: connection reuse, no HEAD before every GET, Content-MD5 on single PUT (#563); configurable transfer concurrency (#576); the MinIO suite (#50)
- Dropbox: sign-in for front-ends (#48); the redirect URI is printed before the browser sign-in (#142, PR #881)
- `relocate` creates a missing `--target-path` (#826, PR #827) and no longer hides a `createFolder` 409 (#767); `profile add` for S3 without a console (#65)

Build, tests and tools:
- JVM baseline 25 (#443); local Gradle time (#726, PR #738); the CDS archive after a deploy (#705, PR #729); `redeploy-local.sh` evicts the startup caches (#733, PR #736); the dependency submission workflow (#357)
- Tests: temp folders cleaned up (#790, PR #791); timing-sensitive tests (#739, #769, #781, #830); Windows-only failures (#816, #817, #847 PR #854); the shared fake's upload (#187); the Dropbox content-hash vector (#190, PR #880); localfs drift and Unicode tests (#31, PR #879); tracking-set 429 backoff (#18)
- `golden.py run` reports failed rows promptly, line-buffered (#953, PR #958); `trash --remote --json` (#716, PR #718); the 2026-11-08 code-quality sweep (#535)
- dist: `install.sh` no longer fetches the Linux co-daemon (#6, PR #914); the mount unit's pre-start unmount (#39); the storm-breaker live check (#312)

## Merged without closing their issue

Part of a larger issue; what is left is in the issue tracker.

- `relocate` refused to run while its data-loss defects were open (`RELOCATE_ENABLED = false`; #56, #61, #62, #63, PR #94); all four are fixed (#56, #61 closed; #62 PR #636; #63 PR #647) and the command is on again (#653)
- OneDrive delta: items without `parentReference.path` get their folder's path, and `deleteById` / `moveById` use the id (#82, PR #103); the percent-encoded path of an item is decoded (#58 V3, PR #96); a download is timed from its declared size (#58 V6, PR #113); a chunked create that meets its own empty leftover completes into it (#69, PR #88)
- Security hardening: an Internxt download whose shard hash cannot be checked, or whose shard is served over http, is refused (#83, PR #110); the OneDrive bearer token goes only to the Graph origin (#83, PR #116)
- Mount: a read keeps a cache copy edited while the daemon was stopped (PR #101); a finished download does not replace the copy of an edit that began while it ran (PR #107); a same-size content change ends the hydration of the row (PR #114); an upload waiting for a queue place follows a rename of its folder (#76, PR #90); a daemon stop during an upload loses no data, and the upload limits are logged at start (#72, PR #117)
- Hydration verbs refuse a second Unicode spelling of a name a client already used: `name_clash` (#24, PR #119)
- Internxt: a file is listed and found under the name it was uploaded with (#54 P1, PR #120); an upload whose source changed length while it was read is retried, not created (P2, PR #121); the folder cache drops what a poll says changed, and a batch create is paired by name (P4, PR #122); the create after a `keep_overwritten` archive rename answers a 409 like any create (P5, PR #123); a mid-stream download failure restarts at byte 0, documented (#41, PR #118)
- Internxt: a completed multipart part is persisted to the resume tombstone even when a sibling part fails, which was the root cause of the flaky `InternxtMultipartUploadTest` restart-resume test (PR #131)
- Docs: the IPC and daemon specs name the tokens, limits and budgets the engine really has (PR #108); the retry table no longer says every client retries a network error (#78, PR #130); the workflow comments name `check` and `windows-security` as the required gates (PR #87); the plan for starting the public history at the release commit (PR #92)
- Hydration subscribe: `handle_status` answers how a handle's transfer ended, across a reconnect (part of #437, PR #551); a seq on every hydration event and a `since_seq` replay on subscribe (part of #437, PR #645)
- Sync: a cancel in the streaming gather's root listing stops the pass (part of #439, PR #560); Pass 1 commits by time, so a crash keeps the rows of the actions it did (part of #439, PR #564)
- The mount daemon logs its transfer budget, upload queue depth and cache grace at start (part of #72, PR #553)
- CLI: autospawn exit detection, `lock.pid` race, status aggregates, share auth hint (part of #455, PR #552)
- Shared JSON-parse helper, volatile Main caches, `fatal()` exit guards (part of #533, PR #549)
- Internxt: the shard-commit retry waits the one-second throttle floor (part of #534, PR #556)
- ci: `check-windows` and `check-runtime-jdk` off the default PR path; the CI build cache on, tests never cached (part of #596, PR #599)
- Mount delete: tracked kind for the Internxt folder lookup (part of #634, PR #639); a folder a listing or delta pass already proved climbs no refusal ancestors (part of #634, PR #641)
- jlink drops `jdk.crypto.ec`, the empty module SunEC left in `java.base` when it moved (part of #456, PR #649)
- Windows Hello P3: the Internxt mnemonic (PR #943), the OneDrive and Dropbox tokens and pending sign-ins (PR #957) and the `config.toml` secret fields (PR #965) sealed under the vault key (#797; the provider audit #859)
- Windows Hello P4: presence proofs for guarded IPC verbs, `require_presence` (#798, PR #944); the unlock helper's `sign` op proposed (PR #969)
- Windows Hello P5: the vault keeps the Internxt password and the engine signs an expired session in again (#861, PR #959)
- The 2026-11-14 audit remainder (#845): `--json` never prompts (PRs #970, #986), streaming gather and resume (PRs #971, #974, #994, #995), download resume (PRs #977, #996), state.db opens and the `pending_upload` column (PRs #978, #982, #990, #1002), the subscriber cap (PRs #984, #999), `profile remove` caches (PR #987), S3 dot-segment keys (PR #988)
- NFC and NFD twins (#948): listed rows stay readable under another spelling (PR #980); the NFC spelling wins and the NFD object is deleted once the NFC upload lands (PR #998)
- OneDrive folds (#950 follow-up): a failed or empty listing after a 409 is retried, shared renames list their own drive, refused renames block the row (PR #992)
- `rm -r -y` no longer refuses its own run's data (#952, PR #960)
- Rows the daemon lists but cannot resolve are named: `unresolvable_row`, a doctor check (#40, PR #903); the storm pause and dedup wait are bounded (#74, PR #900); a large OneDrive upload completes only into an item its own session reserved (#69, PR #902)
