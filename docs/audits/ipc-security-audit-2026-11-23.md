# IPC audit 2026-11-23 — security, safety, correctness, robustness

**Status:** complete for the coverage stated in section 1.

**Audited commits:** unidrive-cli `main` @ `447f4b3e` (equal to `origin/main` at delivery). CI is green at the commit the audit
started from (`build`, `CodeQL Advanced`). `main` moved twice while the
audit ran (`3459ce28` → `55614fb0` → `447f4b3e`, 46 commits in all). None of them changes a finding: the transport,
auth, socket, handler, OAuth, lock, spawn and process files below are byte-identical across all three; what moved is
`DeleteGuards.kt` (N1), and in the last step `DaemonCommand.kt`, `DaemonRuntime.kt`, `HydrationImpl.kt`
(additive: `download_stall_timeout` in `daemon.status`, contract level 10), the download stall watchdog (#508)
and a new client of `daemon.status` (`DaemonStatusReads.kt`, #706). Line numbers refer to `447f4b3e`.
Read-only audit: no code was changed, no build was run, no daemon was started.

**Scope (the IPC channels of the engine):**

1. The daemon's NDJSON socket (AF_UNIX): server side (`:app:sync` `Ipc*`), the engine's own client
   (`IpcAuthClient`, `IpcLineClient`, `:app:cli` `IpcRequest`), the verb handlers, the token files, the socket folder.
2. The OAuth loopback callback server (`OAuthCallbackServer`).
3. The vault unlock helper (stdin/stdout JSON).
4. What surrounds the channels: profile lock and pid sidecar, autospawn / stop / mount spawn, deploy stopper,
   stale-mount detector.

Out of scope: the Rust FUSE client (its findings were moved to unidrive-mount-linux, section 6), provider HTTP clients, the sync engine's reconciliation logic, the Windows client and the MCP bridge
(other repositories).

## 0. Summary

No Critical or High finding in what was read. The security core of the channel — the handshake, the verb scoping,
the token files, the profile lock, the stop path — is sound (section 10). What the audit found is a set of
lower-severity hardening gaps; P1 and the loopback listener are worth fixing first.

| # | Severity (security / robustness) | One line |
|---|---|---|
| OA1 | Low / Low | OAuth loopback: any local process or web page can abort a pending sign-in with one request; attacker text reaches the terminal (ordering is deliberate and pinned by tests) |
| OA2 | Low / — | OAuth loopback: request line read without a length cap |
| S1 | Low / — | `/run/user/<uid>` socket folder is trusted by path, never checked (fallback and override folders are) |
| S2 | Low / Low | Rebind path uses an existing socket folder without re-checking it; watcher tests existence, not identity |
| P1 | Low-Medium / — | `unidrive mount` hands the vault / X-tra passphrase env vars to the co-daemon, which never reads them |
| S4 | Low / Low | Client slots can be held by unauthenticated (≤ 5 s) and idle-exempt (unbounded) connections; the documented rate limiter is not wired |
| H1 | Low / Low | No cap on open handles per connection; READ scope can open read handles but cannot close them |
| H5 | — / Low | `auth.logout`: a mid-way exception leaves a half-done sign-out |
| S3 | — / Low | `sync.subscribe` fan-out is head-of-line blocking (bounded by the write timeout) |
| S5 | — / Low | Socket file and pid sidecar are deleted without compare-and-delete |
| U1 | — / Low | Unlock helper: secret hygiene claims more than it does; unbounded reply read; interrupted flag lost |
| H3 | Info | Two escaping standards for replies (kotlinx `toString` vs `ndjsonString`) |
| P2 | Info | `setsid` for the detached daemon is resolved through `PATH` |
| S6 | Info | `TokenBucket` has no caller |
| W1 | Info (design) | No peer-credential check; no written threat model for the IPC |
| X1 | Info (doc) | Spec §5 still describes a protocol-1 fallback in the Linux co-daemon |
| N1 | Note | Delete guards `bulk_reap_guard` / `top_level_guard` default to off since #911 (landed during the audit) |

Deliberate, documented, no action: H2, H4, H6, OA3 (see their sections).

## 1. Coverage — what was read how

Read in full, line by line:

- `IpcServer.kt`, `IpcAuth.kt`, `IpcAuthClient.kt`, `IpcLineClient.kt`, `IpcReadSelector.kt`,
  `IpcSocketDir.kt`, `IpcSocketPaths.kt`, `IpcSocketWatcher.kt`, `IpcNonblockingWrite.kt`, `ProcessLock.kt`,
  `Ndjson.kt`, `OwnerOnly.kt`, `OwnerOnlyGrants.kt`, `OwnerOnlyAtomicWrite.kt`, `ChildProcess.kt`,
  `DaemonRuntime.kt`, `PreProviderIpc.kt`, `DaemonCommand.kt`, `IpcRequest.kt`, `IpcReplies.kt`,
  `UnlockHelper.kt`, `DetachedSpawn.kt`, `DeployedProcessStopper.kt`, `OAuthCallbackServer.kt`,
  `AuthLoginRpcHandler.kt`, `AuthLogoutRpcHandler.kt`, `VaultRpcHandler.kt`, `ShareRpcHandler.kt`,
  `HydrationRequest.kt`, `HydrationIpcHandler.kt` (lines 243–1104), `IpcProgressReporter.kt`, `TokenBucket.kt`,
  `DeleteGuards.kt` (diff).

Read in part (named ranges): `MountCommand.kt` 120–241, `HydrationImpl.kt` 300–352, 596–682, 763–800, 1972–2030,
4244–4258, `SyncCommand.kt` 462–486 and 640–660.

Sampled by search only (function outlines, interpolation scan, limit and deletion greps — not read line by line):
`HistoryRpcHandler.kt`, `EnumerateRpcHandler.kt`, `IgnoreRpcHandler.kt`, `MetricsRpcHandler.kt`,
`PauseRpcHandler.kt`, `RefreshRpcHandler.kt`, `DaemonAutospawn.kt`, `StaleMountDetector.kt`, `ProfileRemove.kt`,
`DaemonVaultStart.kt`. Not assessed: `AuthCheckRpcHandler`, `DaemonHydrationLink` beyond
`dispatchEvent`, provider-side OAuth callers (`TokenManager.kt`, `DropboxAuth.kt`).

Specs read: `docs/dev/specs/ipc-authentication.md`, `docs/dev/specs/unlock-helper-protocol.md`,
`docs/dev/ops/security-posture.md`.

Line numbers refer to `447f4b3e`. `DaemonStatusReads.kt` (added by #706 after the read-through: `status --pending`
and plain `quota` read `daemon.status` when a daemon runs) was looked at by search only — strict kotlinx parsing
behind `runCatching`, typed number readers, the existing READ-scope, 10 s `requestDaemonStatus`.
A finding is stated only where the code path was traced; where a
conclusion rests on a comment or a test rather than on read code, it says so.

## 2. Server transport

### S1 — `/run/user/<uid>` is used as the socket folder without any check

`core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcSocketPaths.kt:77-82` and `:98-105`:

```kotlin
val runDir = Path.of("/run/user/$uid")
if (!Files.isDirectory(runDir)) IpcSocketDir.ensurePosixFallbackDir(tmpDir, uid)
return resolveAndMeta(dir, profileName, dir)
```

- The fallback folder (`IpcSocketDir.ensurePosixFolder`: real directory, not a link, owned by this uid, mode 700)
  and the `UNIDRIVE_IPC_DIR` folder (`ensureOverrideDir`) are checked on every use. The `/run/user/<uid>` branch
  is not: `Files.isDirectory` follows links and asks nothing about owner or mode.
- Precondition for harm: `/run/user/<uid>` exists but is reachable by others (a session manager or container that
  creates it with wider grants, an administrator's bind mount). Then the socket folder is shared and the only
  barrier left is the socket file's mode, which is set *after* `bind` (`IpcServer.kt:344-349`,
  `IpcSocketWatcher.kt:82-84`): the file exists with the process umask for a moment.
- On sg5 the folder is `drwx------ gernot:gernot` and both sockets `srwx------` (checked with `stat`), so nothing
  is wrong there today.
- Fix: run `IpcSocketDir.fallbackDirProblem` on `/run/user/<uid>` too, and fall back to the private temp folder
  (or refuse) when it fails.

### S2 — the rebind restores a socket folder without checking it

`IpcSocketPaths.kt:111-115`:

```kotlin
fun restoreSocketDirectory(socketPath: Path) {
    val dir = socketPath.parent ?: return
    if (Files.isDirectory(dir)) return
    ...
}
```

- `IpcSocketWatcher.rebind` (`IpcSocketWatcher.kt:77-91`) runs this every time it finds the socket file gone
  (checked every 5 s, `SOCKET_WATCH_INTERVAL_MS`).
- Scenario: a temp cleanup deletes `<tmpdir>/unidrive-ipc-<uid>` (the #794 scenario, the reason the watcher
  exists). Before the next tick another local user recreates a folder of that name — it is in the temp dir and
  nothing of ours is there any more. The daemon finds a directory, returns from line 3, and binds its socket into
  a folder the other user owns. They can unlink and replace the socket file at will, so every later client connects
  to a listener of theirs. The watcher does not notice: it tests `Files.exists(socketPath)` (`:45`), not that the
  file is the inode this daemon bound.
- What the attacker gains: a fake daemon. Not the token (it never travels), but every request a client sends
  after its handshake, answered however they like — or relayed transparently (W1). Combined with S1 the same
  holds for `/run/user/<uid>`.
- Fix: call the folder check in `restoreSocketDirectory` also when the folder exists, and have the watcher compare
  the bound file's identity (`unix:ino`, `unix:dev`) instead of its existence.

### S3 — `sync.subscribe` events are written to all subscribers on one coroutine

`IpcServer.kt:473-495` (the broadcast loop) and `:956-974` (`writeNonBlocking`).

- The writes run one after another. A peer that takes no bytes is given `writeTimeoutMs` (5 s by default,
  `UNIDRIVE_IPC_WRITE_TIMEOUT_MS`) before the write throws. Until then no other subscriber gets the event, the
  256-slot channel fills, and what does not fit is dropped (`emit`, `:217-225`).
- Bounded: the kernel buffer (~200 KiB) has to fill first and the stalled subscriber is dropped after one
  timeout. A suspended client (laptop sleep, a debugger) or one that subscribes and never reads — READ scope is
  enough, `sync.subscribe` is a read verb — can repeat it by reconnecting.
- The hydration event stream does not have this shape: one writer coroutine and a bounded queue per subscriber
  (`HydrationIpcHandler.kt:436-474`). The sync stream should do the same.

### S4 — connection slots, idle exemption, and a rate limiter that is not wired

- `IpcServer.kt:371` counts every connection, authenticated or not, against `maxClients` (32). An unauthenticated
  connection is closed after the 5 s handshake timeout, so 32 of them make every real client get `too_many_clients`
  for up to 5 s, repeatable.
- `IpcServer.kt:721` with `DaemonRuntime.kt:617`: a connection that ran one verb of `HydrationIpcHandler.VERBS`
  is never closed for being idle — that list includes the read verbs. Subscribers are exempt too (`:844-848`). So
  slots can be held for good by any client, READ scope included. The engine's own one-shot commands close their
  channel (`IpcRequest.kt`, `.use`), so they do not leak.
- `TokenBucket.kt` documents itself as the rate limiter for per-connection dispatch (UD-106). It has no caller in
  main code (`grep`: only `TokenBucketTest`). Nothing rate-limits a connection; what bounds a peer is the 64 KiB
  line cap, three handshake failures per connection, the handshake timeout, one request at a time per
  connection, and the client cap. Either wire it or delete it (S6).
- A refusal blocks the accept loop's thread for up to 200 ms (`refuse` → `writeWhileWritable`), and one of the
  four transport threads sits in `accept()` for good.
- All of this needs a connection to the socket, which is in an owner-only folder: the same user. Hence Low.
- Fix: a much smaller separate cap for unauthenticated connections; a per-connection request-rate limit if the
  table-free verbs are ever asked for.

### S5 — probe, then delete (socket file and pid sidecar)

- `IpcServer.reclaimStaleSocket` (`IpcServer.kt:566-578`) and `IpcSocketFiles.removeStaleSocketFiles`
  (`IpcSocketPaths.kt:155-170`) connect to the socket file and delete it when the connect fails. A socket bound
  between the failed connect and the delete is unlinked. `catch (_: java.net.SocketException)` also treats every
  other connect error as "stale".
- Mitigations that make this narrow: the profile lock is taken before the socket (`DaemonRuntime.lifecycle`,
  `:266-272`), so two daemons cannot race here, and the watcher binds the file again within 5 s.
- `DaemonStopCommand` (`DaemonCommand.kt:450-457`) reads the pid sidecar, finds that holder dead, and then
  `Files.deleteIfExists(pidFile)` without checking that the file still holds the pid it read. A daemon that
  started in between and re-stamped the sidecar loses it: `daemon status` and `daemon stop` then say "no daemon
  running" for a live daemon (it still holds the profile lock, so nothing starts twice; it can only be stopped
  by pid). Narrow window.
- Fix: read-compare-delete for the sidecar; bind the socket at a private temporary name and `rename` it into place.

## 3. Process and spawn

### P1 — the mount's co-daemon inherits the vault passphrase variables it never reads

`core/app/cli/src/main/kotlin/org/krost/unidrive/cli/MountCommand.kt:214-217`:

```kotlin
val pb = ProcessBuilder(argv).inheritIO()
applyDefaultCoDaemonLogLevel(pb.environment())
val proc = pb.start()
```

- The environment is inherited whole, so `UNIDRIVE_VAULT_PASS` and `UNIDRIVE_XTRA_PASS` reach the Rust co-daemon.
  It reads neither: `grep -rniE 'vault|passphrase|UNIDRIVE_VAULT|XTRA_PASS' mount/src` is empty; its env reads are
  `XDG_CACHE_HOME`, `HOME`, `XDG_STATE_HOME`, `JOURNAL_STREAM`.
- The project already treats this as an exposure: `ChildProcess.kt` strips both variables from every third-party
  child because other software on the machine can read the environment (issue#83 E, #785). Its comment lists "the
  autospawned daemon, the co-daemon" as first-party children that legitimately inherit them — true for the daemon
  (it decrypts the vault), not for the co-daemon.
- The co-daemon lives as long as the mount; `/proc/<pid>/environ` is readable by every process of the same uid.
- Fix: `pb.environment().stripPassphrases()` in `superviseProcess` (the extension is public in `ChildProcess.kt`).

### P2 — `setsid` is found on `PATH` (Info)

`DetachedSpawn.kt:48-49`: the detached daemon is started as `<setsid> <command>` with `setsid` resolved from the
invoking user's `PATH`. An absolute location (`/usr/bin/setsid`, keeping the existing "start as before" fallback)
would not depend on it. Same user only.

Checked and sound here: autospawn resolves `java` from `java.home` (`DaemonAutospawn.kt:89`) and the jar from its
own location, waits for the socket for at most 60 s, and passes the token *file path* to the co-daemon, never the
token. `DeployedProcessStopper` keeps the verified `ProcessHandle` from the identity check and signals only that
handle, so a recycled pid is never signalled; a non-daemon holder (a `sync`) is destroyed without a graceful
attempt — acceptable for `deploy`, worth knowing.

## 4. Handler layer

### H1 — no cap on open handles; READ scope can open read handles but not close them

- `HydrationImpl.registerHandle` (`HydrationImpl.kt:321-331`) adds to `openSets[connectionId][handleId]` with no
  size check. An open handle pins its cache file against eviction and reclaim (`:2215`, `:2724`, `:3412`).
- `hydration.close_handle` is in the write class (`IpcAuth.kt:217`), `hydration.open_read` in the read class. A
  read-scope client therefore cannot release what it opens; its handles go only when its connection closes
  (`onConnectionClosed`, `:4248-4256`).
- Scenario: a front-end that observes with READ scope and calls `open_read` with fresh handle ids (the spec warns
  that only this read verb does work) grows the map without bound and keeps those paths cached for good.
- Fix: a per-connection handle cap (refuse with a stable token past it), and allow `close_handle` for a handle
  the same connection opened — e.g. by splitting the verb or by letting the handler check ownership.

### H5 — `auth.logout`: a mid-way exception leaves a half-done sign-out (robustness, Low)

`AuthLogoutRpcHandler.kt:55-105`: `pendingRows()` (`:67`), `forgetCredentials()` (`:100`) and the file deletions
(`:103`) run outside any catch. An exception there surfaces as `handler_threw` and can leave the daemon with
credentials forgotten in memory but files still on disk, or the reverse. `AuthLoginRpcHandler.step` (`:83-104`)
does catch everything and answers by type only; logout should do the same. Nothing secret reaches the log on this
path (the exceptions are SQL, keyring and file errors).

### H3 — two escaping standards for replies (Info)

`ndjsonString` (`io/Ndjson.kt`) is the documented escaper and escapes NEL, U+2028 and U+2029 as well as what JSON
requires. Replies built with kotlinx `buildJsonObject {}.toString()` do not get the extra three: the `paths` of
`uploads_pending` (`AuthLogoutRpcHandler.kt:76`), the sign-in replies (`AuthLoginRpcHandler.kt`,
`block(...).toString()`), and several `daemon.status` members (`enumerationJson`, `uploadsJson`, `cacheJson`,
`credentialHealthJson`, `authJson`, `DaemonRuntime.kt:785-839`). Framing by `\n` is unaffected, so this is a
consistency note: a client that ends lines at U+2028 (the reason the escaper gives) would see those three raw.
Every hand-built reply and event in the hydration handler, the share handler and the vault handler uses the
escaper; a scan for raw `"...":"$var"` interpolation over the CLI, sync and hydration sources found only
server-generated values (UUID job ids, enum tokens, constants).

Deliberate, documented, no action:

- **H2** `closeHandle` (`HydrationImpl.kt:1989-2009`) closes a client-named handle on another connection when that
  id is unique across connections (archive#438, a pooled client). Both ends need FULL scope.
- **H4** `ShareRpcHandler` passes `path` to the provider without the logical-path validation every hydration verb
  applies (`HydrationIpcHandler.kt:904-905`, `:964-973`). FULL scope only.
- **H6** `hydration.cancel` without `handle_id` cancels by path across all connections. FULL scope only.

## 5. OAuth loopback callback server

### OA1 — any local party can abort a pending sign-in; the text it sends reaches the terminal

`core/app/core/src/main/kotlin/org/krost/unidrive/auth/OAuthCallbackServer.kt:262-285`:

```kotlin
val error = param("error")
if (error != null) throw AuthenticationException("$providerLabel returned error: $error ${param("error_description") ?: ""}")
val receivedState = param("state") ?: throw AuthenticationException("Missing OAuth state parameter — possible CSRF attack")
if (receivedState != expectedState) throw AuthenticationException("OAuth state mismatch — possible CSRF attack")
```

- The `error` parameter is honoured before the state is looked at, and a wrong or missing state ends the whole
  wait (`next()` hands over the first callback-shaped request, `:104`; the exception closes the listener,
  `:131-133`). The listener is on `127.0.0.1` and the OneDrive port is fixed at 8080
  (`OneDriveConfig.kt:26`). Every local process and every web page in the user's browser can send one GET to it.
- Consequence: a sign-in can be aborted for its whole five-minute window by `GET /?error=x`. What cannot happen:
  a code is accepted or injected without the state. The code is protected (state compared before any code is
  read, PKCE as well).
- The `error` and `error_description` values are URL-decoded attacker text. They are escaped in the HTML page
  (`escapeHtml`, pinned by `OAuthCallbackServerTest`), but the exception message carries them unfiltered to
  whatever prints it — the terminal, the log. Control characters are not stripped.
- The ordering is deliberate ("load-bearing", the comment at `:251-254`) and pinned by two tests
  (`OAuthCallbackServerTest.kt:61`, `:99`). The trade-off is the maintainer's: surfacing `access_denied` as
  itself versus letting any local party end the wait. If the latter matters: require a matching state also for
  error callbacks, or answer a non-matching request with the failure page but keep listening until the deadline.
  In both cases strip control characters from the reflected text.
- `state` is `UUID.randomUUID()` (SecureRandom) — adequate.

### OA2 — request line read without a length cap

`OAuthCallbackServer.kt:183-184`: `client.getInputStream().bufferedReader().readLine()` with a 10 s read timeout
and nothing else. A local process can send a line of any length for ten seconds at loopback speed and exhaust the
heap of the signing-in process. A real redirect is a few hundred bytes. Fix: read at most 16 KiB.

### OA3 (Info, no action) — the state compare is `!=`, not constant-time. Irrelevant on loopback with a
122-bit random value.

Checked and sound here: bound to `127.0.0.1` only; a bind failure is reported and fails closed; a request that is
not callback-shaped gets a 404 and does not end the wait; each connection is read on its own virtual thread with
the read timeout; the response is escaped; the request line (with the code) is never logged — the only message
that includes it is the "no code" error, which is reached after the state matched and so carries no code.

## 6. Rust mount client (moved)

The findings on the unidrive-mount-linux client (R1–R6) live in that repository:
`docs/audits/ipc-client-audit-2026-11-23.md` (local branch `audit/ipc-client-2026-11-23`, not pushed), tracked as
gkrost/unidrive-mount-linux#135 and gkrost/unidrive-mount-linux#101.

## 7. Vault unlock helper

### U1 — secret hygiene and two small robustness gaps (robustness, Low)

`core/app/cli/src/main/kotlin/org/krost/unidrive/cli/UnlockHelper.kt`:

- `:109-112` says "scrub the byte copy so only one heap copy survives". Two more do: the `String` made by
  `String(bytes, UTF_8)` (immutable) and the reply text itself (`decodeToString()`; `replyBytes` still holds it)
  with the base64 secret in it. Build the `CharArray` from the bytes without a `String` in between, or correct
  the comment.
- `:73-75`: `readAllBytes()` of the helper's stdout has no cap. The helper is a configured, trusted binary, but a
  broken one can exhaust the heap. A passphrase reply is a few hundred bytes.
- `:79`: `catch (_: Exception) { ByteArray(0) }` also catches `InterruptedException` and drops the interrupted
  flag. Restore it.
- On a read timeout the `supplyAsync` task is not cancelled; it ends when the pipe closes. A helper that left a
  grandchild holding its stdout parks one common-pool thread for good.

Checked and sound here: helper path from config, absolute and a regular file, never `PATH`; both passphrase
variables stripped from the helper's environment; the request names the keyring path, not a secret; the reply is
parsed strictly and base64-decoded; soft and hard error codes as specified; a helper is destroyed after 60 s.

## 8. Design note and documents

### W1 — what the handshake does and does not authenticate

- No side checks the peer's credentials (no `SO_PEERCRED` or equivalent anywhere; `grep` over both repositories).
- The server proof proves that the peer knows the token, not that it is the daemon at the other end of *this*
  connection: a process that can put its own socket file at the path forwards both handshake steps to the real
  daemon and relays everything after it, and the client's proof check passes.
- The socket folder is owner-only and the token is `0600`, so who can do that is the same user, who can read
  `ipc.token` anyway. Not an escalation on a standard Linux desktop; it becomes one together with S1 or S2. The
  client also reads the token file without checking its owner or mode (`IpcAuthClient.readKeyOrNull`); it relies
  on the folder, which the daemon makes owner-only at every start (`IpcAuth.issue`).
- There is no written threat model for the IPC — `docs/dev/ops/security-posture.md` is the GitHub configuration
  audit. A short paragraph in `docs/dev/specs/ipc-authentication.md` ("the boundary is the OS user; same-uid
  processes are trusted; the folder is the boundary, not the handshake") would make this a decision.

### X1 — spec §5 is stale

`docs/dev/specs/ipc-authentication.md:134-135` says the Linux co-daemon still carries the protocol-1 fallback.
mount-linux removed it (`9c7cb2a`, gkrost/unidrive-mount-linux#133; verified in `ipc.rs:152-172`). Issue #33 tracks the rest.

## 9. Adjacent observation (landed during the audit, not an IPC finding)

### N1 — delete guards default to off

`core/app/cli/src/main/kotlin/org/krost/unidrive/cli/DeleteGuards.kt:14-15` (since #911, in `447f4b3e`):
`ignoreTopLevelGuard = true`, `bulkReapGuard = false`; `max_delete_percentage` (50), `max_delete_absolute` (50)
and `max_delete_per_subtree_percent` (80) are unchanged. The IPC verbs that delete in the cloud
(`hydration.unlink` with `permanent`, `hydration.rmdir` with `recursive` and `discard_local`) are FULL scope, so
the client that drives them is the mount or the owner's CLI. Not judged here — the owner's decision; it is listed
so that the safety picture next to the channel is complete.

## 10. Checked and found sound

**Authentication gate** (`IpcAuth.kt`, `IpcServer.dispatchRequest`)

- Fail closed: a connection without a session is refused and closed; before the handshake only `hello`,
  `hello.proof` and a minimal `daemon.status` are answered; an unlisted verb is `admin`, so READ scope cannot
  reach it by default.
- The verb the gate checks is the verb the handler gets: `parseVerb` (`IpcServer.kt:1050-1060`) requires exactly
  one top-level `verb` member by a hand-written scanner and a strict kotlinx parse; for READ scope
  `singleTopLevelVerb` (`IpcAuth.kt:379-411`) re-reads it with a second scanner; both count escaped spellings and
  ignore nested and value strings. A line the strict parser rejects never reaches a handler. The hydration
  handler parses with the strict default `Json` as well (`HydrationRequest.kt`), depth-capped at 64.
- No re-handshake on an authenticated connection; the scope is bound into both HMAC messages and each scope has
  its own key, so a READ token cannot produce a FULL proof.
- Replay: the server nonce is 16 bytes from `SecureRandom`, fresh per `hello`, spent by the next `hello.proof`
  whatever the outcome. Message unambiguous: length-prefixed profile, canonical base64url nonces, domain labels.
- `MessageDigest.isEqual` on both sides; three failures close a connection, 250 ms per failure; keys are 256
  random bits; the handshake timeout is checked at every turn of the reader loop and rechecked every 250 ms.

**Token files** (`IpcAuth.issue`, `OwnerOnlyAtomicWrite`, `OwnerOnly`, `OwnerOnlyGrants`)

Written only by the profile-lock holder, before the socket listens; the profile folder is made owner-only and
refused otherwise; each token goes to a temp file created `rw-------`, restricted and verified before the first
byte, forced to disk, renamed atomically, verified again. A refusal is one `IPC startup refused:` line and exit
78, with no socket. `grantProblem` reads with `NOFOLLOW_LINKS`, so a linked token file is refused.

**Transport** (`IpcServer.kt`, `IpcReadSelector.kt`, `IpcLineClient.kt`)

Line cap 64 KiB per request line and for the line-less tail; depth cap 64 before kotlinx builds a tree; bytes
decoded per whole line. One in-flight request per connection; every write to a connection is under its
`writeMutex` in `withContext(NonCancellable)`, so no torn line and no half-written reply after a cancelled writer.
Every well-framed line gets a reply; a reply that cannot be written closes the connection; an unanswerable
request logs member names and length only, never the line. `handler_threw` carries the exception text to FULL
connections only. The read selector holds at most one waiter per channel.

**Daemon wiring** (`DaemonRuntime.kt`, `PreProviderIpc.kt`, `DaemonCommand.kt`, `ProcessLock.kt`)

Lock → tokens → handlers → socket, in that order, also on a locked start (pre-provider verb set only). The profile
lock is exclusive per profile and released by the OS on death, so `sync` and `daemon`, or two daemons, never serve
one socket; the sidecar is written only by the holder and deleted only while the lock is still held. `daemon stop`
signals a process only after `isLockHolder` confirmed it can have written the sidecar (start time not later than
the sidecar plus 2 s, command a java/unidrive one); IPC shutdown first, destroy as the fallback; without
`--force` it refuses while a live FUSE mount uses the socket. Every admin and write verb is registered under
exactly the names in `IpcAuth.VERB_CLASSES`; `hydration.subscribe` of a READ connection does not start an
enumerate. `daemon.status` job ids are server-generated UUIDs.

**Verb handlers**

Every logical path of every hydration verb passes one validator (`HydrationIpcHandler.kt:904-905`, `:964-973`):
no `.` or `..` segment, no control character, no empty inner segment, no segment over 255 UTF-16 units; the
client-supplied cache path of `open_write` must resolve inside the profile's cache folder on real paths
(`HydrationImpl.kt:782`). Numeric fields are digit-checked, length-capped and range-checked
(`HydrationRequest.count`, `wholeNumber`; `view.page` limit 1..1000; `hydration.locate` at most 5000 ids; the
history reply is capped at 512 KiB). Every string in a hand-built reply or event goes through `ndjsonString`,
and error text is URL-redacted first (`errorEsc`). Handles are keyed by connection and released when the
connection closes; a repeat subscribe tears the previous writer down; the subscriber queue is bounded and reports
`lost`. The write verbs `create`, `open_write`, `mkdir`, `rename` claim a name spelling before they run and roll
the claim back when the verb fails or is cancelled (`claimed`, `:939-953`).

**Secrets over IPC** (`AuthLoginRpcHandler`, `VaultRpcHandler`)

Bodies parsed with the strict parser, so a malformed body is a refusal and never a decoding exception quoting the
input; only the verb and the refusal token or exception type are logged; the passphrase is copied into a
`CharArray` that is scrubbed in `finally`; the wrong-passphrase delay is spent outside the transition mutex;
sign-in and sign-out share one mutex; `auth.logout` never touches sync state and refuses with `uploads_pending`
while uploads can still land.

**JSON escaping** (`io/Ndjson.kt`): kotlinx's `JsonPrimitive.toString` plus NEL, U+2028, U+2029 as `\u` escapes.

## 11. Test coverage of the findings

Pinned by existing tests (keep green when fixing): the server's `too_many_clients`
(`IpcServerConnectionLimitsTest`); the OAuth ordering and HTML escaping (`OAuthCallbackServerTest`); path
validation, NFC, JSON escaping and wire shapes of the hydration handler (`HydrationIpcHandler*Test`).

Not pinned by any test found: S2 (no test mentions `restoreSocketDirectory`), S3, S4's idle exemption as a
slot-exhaustion case, H1 (there is no cap), H5, OA2, U1.

## 12. Suggested order of work

1. **P1** (one line): `stripPassphrases()` on the co-daemon's environment.
2. **S2 + S1**: one folder check in `restoreSocketDirectory` and on `/run/user/<uid>`; identity instead of
   existence in the watcher.
3. **H1 + S4**: a per-connection handle cap; READ scope allowed to close its own read handles; a small separate
   cap for unauthenticated connections; delete or wire `TokenBucket`.
4. **OA2**, then **OA1** if the trade-off is revisited (two tests change).
5. The rest (S3, S5, H5, U1, P2, H3) as they come up; X1 and the W1 paragraph with the next edit of
   the spec.
