# IPC authentication — Spec

**Status:** Implemented in the engine (IPC protocol version 2). This is the contract every IPC client
implements: the engine's own CLI, the Windows client, the UI, the Linux co-daemon and the MCP bridge.

**Touches:**
- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcAuth.kt` — token files, handshake, verb classes (server side).
- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcAuthClient.kt` — the engine's client side.
- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt` — one session per connection; the gate before the handler lookup; the handshake timeout.
- `core/app/core/src/main/kotlin/org/krost/unidrive/io/OwnerOnlyGrants.kt` — read-only check that only the current user can reach a path.
- `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/DaemonRuntime.kt`, `SyncCommand.kt` — issue the tokens at start.
- `scripts/golden/unicode-tree/ipc-auth.ps1` — the same client side for the PowerShell probes.

## 1. What it does

Every connection to a profile's daemon socket proves that it can read a secret the daemon wrote into
the profile's config folder at start, before the daemon serves it. The daemon proves the same back, so a
client also knows it is talking to the daemon of that profile. Each secret carries a scope: `full`
(every verb) or `read` (listing, status, subscriptions, reads). Secrets never travel on the wire; both
sides show an HMAC over nonces from both sides instead.

## 2. Token files

- Two files in the profile's config folder (the folder that holds `credentials.json`; `-c` /
  `UNIDRIVE_CONFIG_DIR` / the platform default, then the profile name): `ipc.token` (scope `full`) and
  `ipc.read.token` (scope `read`).
- Each file is one line: base64url (RFC 4648 section 5, no padding) of 32 bytes from a `SecureRandom`,
  43 ASCII characters, no newline. Readers ignore surrounding whitespace. The HMAC key is the
  **decoded** 32 bytes, never the text.
- **Lifecycle.** `daemon run` and `sync` write new tokens at every start, after they hold the profile
  lock and before the socket listens. The profile folder is made owner-only first; each token goes into
  a temporary file in that folder, which is restricted and checked **before** the token is written into
  it, then renamed over the target atomically. A reader never sees half a token, and a token never sits
  in a file with wider permissions. A start that the profile lock refuses (another daemon or sync
  already serves the profile) never touches the token files, so the running daemon's clients keep working.
- **Permissions, verified.** After writing, the folder and both files are checked by reading their
  grants: on Windows every DACL entry that grants access names the current user, SYSTEM or
  Administrators (the owner is not compared: an elevated process makes Administrators the owner of what
  it creates); on POSIX the file is `0600` and the folder `0700` (no group or other bits).
- **Startup refusal.** When the files cannot be created, restricted or verified, the daemon does not
  open its socket. It logs ONE error line that starts exactly with `IPC startup refused:` followed by the
  reason and the path, writes the same line to stderr, and exits with code **78**. A daemon never runs
  without its IPC.
- **Readers re-read the file at every connect** (and every reconnect). A restarted daemon has new
  tokens; the old ones no longer authenticate.

## 3. Handshake

Before authentication a connection may send only `hello`, `hello.proof` and `daemon.status`:

| Request | Reply before authentication |
|---|---|
| `daemon.status` | `{"ok":true,"protocol_version":2,"engine_version":"...","auth_required":true}` |
| any other verb, or no verb | `{"ok":false,"error":"auth_required"}` |

A connection that has not authenticated within **5 s** is closed. After authentication `daemon.status`
gets the full reply.

1. Client: `{"verb":"hello","protocol":2,"scope":"full"|"read","client":"<name>","nonce":"<16 random bytes, base64url>"}`.
   `client` is 1 to 64 printable ASCII characters. Server: `{"ok":true,"step":1,"snonce":"<16 random bytes, base64url>"}`.
   The server keeps (scope, client nonce, server nonce) for this connection only. The server nonce is
   single use: the next `hello.proof` spends it, whatever its outcome.
2. Client: `{"verb":"hello.proof","proof":"<clientProof>"}`. The server takes the token of the scope the
   `hello` asked for and compares in constant time. Success:
   `{"ok":true,"scope":"<scope>","protocol_version":2,"proof":"<serverProof>"}`.
3. The client compares `serverProof` in constant time and closes the connection when it does not match.

```
clientProof = HMAC-SHA256(key, UTF8("unidrive-ipc-v2|client|" + scope + "|" + byteLen(profile) + ":" + profile
                                    + "|" + clientNonce + "|" + serverNonce))
serverProof = HMAC-SHA256(key, UTF8("unidrive-ipc-v2|server|" + scope + "|" + byteLen(profile) + ":" + profile
                                    + "|" + clientNonce + "|" + serverNonce + "|" + clientProof))
```

- `key` is the decoded token of the scope; both proofs are sent as base64url without padding.
- `profile` is the profile name exactly as in `config.toml` (no Unicode normalisation); `byteLen` is the
  number of its UTF-8 bytes in decimal. The length prefix keeps the message unambiguous for any name.
- `clientNonce`, `serverNonce` and `clientProof` are the base64url texts as sent.

**Failures.** A malformed `hello` (wrong protocol, unknown scope, missing or invalid client name or
nonce), a `hello.proof` without a pending `hello`, or a proof that does not match: the server waits
250 ms and replies `{"ok":false,"error":"auth_failed"}`. The third failure on a connection closes it
after the reply. A failed handshake does not authenticate anything; the client starts over with a new
`hello` (and gets a new server nonce).

## 4. Verb classes

Classified by the exact wire string; a verb that is not listed is `admin` (default deny).

| Class | Verbs |
|---|---|
| read | `daemon.status`, `hydration.list`, `hydration.last_synced`, `hydration.subscribe`, `sync.subscribe`, `hydration.open_read`, `history.list`, `history.subscribe`, `metrics.query`, `ignore.explain`, `view.page` |
| write | `hydration.hydrate`, `hydration.dehydrate`, `hydration.create`, `hydration.open_write`, `hydration.open_write_begin`, `hydration.close_handle`, `hydration.mkdir`, `hydration.unlink`, `hydration.rmdir`, `hydration.rename`, `hydration.cancel`, `sync.enumerate`, `hydration.matches_local`, `hydration.refuse_name` |
| admin | `daemon.shutdown`, `daemon.pause`, `daemon.resume`, `refresh.run`, `auth.logout`, `auth.begin`, `auth.complete`, `auth.login`, `auth.check` |

Scope `full` may call every verb; scope `read` only the read class. A refused request gets
`{"ok":false,"error":"forbidden","scope":"read"}` and the connection stays open. For a `read`
connection the request must also carry exactly one top-level `verb` member, equal to the verb the
server dispatches on; anything else is `forbidden`, so a handler that reads the verb itself never acts
on another verb than the one checked. `hello` and `hello.proof` after authentication are ordinary
(unregistered) verbs.

**Observing is not working.** A `hydration.subscribe` on a `read` connection is accepted with the same reply
(`{"ok":true}`) and delivers events, but it never starts the enumerate that a `full` connection's subscribe
triggers after its reply: an observer (a status UI) cannot make the daemon scan the remote. Of the read
class, only `hydration.open_read` does work: it downloads and caches the file's content, so a front-end that
only observes must not call it. The other read verbs (`daemon.status`, `hydration.list`,
`hydration.last_synced`, `sync.subscribe`, `metrics.query`, `ignore.explain`) answer from state the daemon already holds.
Two may ask the provider for a view's list (ipc-write-protocol.md section 4.2): `hydration.list` of a view's folder and
`view.page`. Both are bounded by the view's cache: one request in flight per view, and no new request within its time to live
for a list no longer than one already asked for (`view.page` rounds its size up to a multiple of 50, at most 1,000 references),
so a read connection cannot make the daemon ask the provider more than a few times a minute. Neither downloads content.

**`hydration.matches_local` is a write-class verb although it changes nothing** (archive#1219). It reads a local file the caller names and answers whether that file holds the bytes of a cloud item (`same`, `differs`, `unknown`). On a `read` connection that would be an oracle over any file the user can read ("is this file the cloud copy of X?"), so it needs scope `full`, like the mount's own calls. The reply carries a method token, never a hash.

`IpcContractCorpusTest` keeps the table equal to the contract corpus: every corpus verb has a class and
the table holds no verb the daemon does not register.

## 5. Client behaviour

- A token file for the profile exists: the handshake is mandatory and fails closed. A refused handshake
  is terminal — surface it as `auth_failed` with a clear message (for example "the daemon rejected the
  IPC token; restart the mount"), not as a timeout or a retry loop. A connection the daemon closes fails
  at once.
- No token file: fail closed and send nothing on the socket. Every daemon issues its token files at start,
  so a missing file means the socket is not the daemon of this profile (or an engine without IPC
  authentication). There is no unauthenticated protocol-1 fallback in the engine's own client (the CLI) and
  the Windows client. A socket nobody listens on is still reported as unreachable, not as an
  authentication failure.
- A token file exists but the daemon answers `hello` with `unknown_verb`: fail closed (restart the daemon).
- Use the least scope that does the job: the engine's `ls` and `daemon status` and the probes connect with
  `read`; `daemon stop` and `refresh` with `full`.

The Linux co-daemon and the MCP bridge still carry the protocol-1 fallback; they drop it in a later
release (archive#612). Every daemon of this engine is protocol-2 only, on every platform.

## 5a. Sign-in for front-ends: begin / complete (archive#866)

A front-end signs a profile in across two processes with `auth begin` and `auth complete` (the CLI verbs;
the MCP `auth_begin` / `auth_complete` tools drive the same factory methods). OneDrive answers a device
code and the complete side polls. Dropbox has no device-code flow, so it uses the **no-redirect code
flow**:

- `auth begin` prints (JSON) the `handle`, the Dropbox `authorize_url` — same shape as the browser flow
  but **without** `redirect_uri`, with a PKCE S256 challenge — and an `expires_in` (600 s). The PKCE
  verifier is kept in the profile folder as `pending_dropbox_flow.json` (owner-only through the profile
  folder's tightening; it holds the verifier and the expiry, no secret beyond it).
- `auth complete --handle <h> --code-stdin` reads the code the user pasted **from standard input** (a
  one-time secret is never an argument), exchanges it without `redirect_uri`, stores `token.json` and
  deletes the pending flow. A wrong or spent code stores nothing and leaves the flow in place until it
  expires, so the same handle can take a freshly shown code. A second successful complete is refused
  (unknown handle); an expired flow answers `expired`.
- The engine never logs the code, the verifier or the tokens; front-ends print the `authorize_url` only.

Both commands take the app key from the profile's `client_id` first, then from `UNIDRIVE_DROPBOX_CLIENT_ID`,
the same lookup the provider itself uses. The CLI hands the profile's config to begin and complete without
its passwords and secrets; a profile with neither is refused (`begin_failed`) before any flow is kept.

## 6. Error tokens

| Token | When |
|---|---|
| `auth_required` | any verb other than `hello`, `hello.proof`, `daemon.status` before authentication |
| `auth_failed` | a refused handshake step (after a 250 ms wait; the third closes the connection) |
| `forbidden` | a verb outside the connection's scope (with `"scope":"read"`) |

## 7. Test vectors

Every implementation asserts exactly these. Profile `p1`; token bytes `00 01 … 1f`; client nonce bytes
`00 … 0f`; server nonce bytes `10 … 1f`.

| Value | base64url |
|---|---|
| token | `AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8` |
| clientNonce | `AAECAwQFBgcICQoLDA0ODw` |
| serverNonce | `EBESExQVFhcYGRobHB0eHw` |
| scope `full`: clientProof | `2TL-rOrEbuDB9pZ34fpxlSRakSFB9X49f3Kn7_plriA` |
| scope `full`: serverProof | `qwZ4elXK7shenllL3frkDuObSaAhaSuBDra9Q94EuNo` |
| scope `read` (same key bytes): clientProof | `GtoBU2BmkePKA90_tQJ5YMN4BNI6dGrDqeOSG04Mz7U` |
| scope `read`: serverProof | `OZm49FG0lEPBerNevnY86dcxbxuAS4jC3rwgK4Gtc8o` |
| profile `caf` + U+00E9 (5 UTF-8 bytes), scope `full`: clientProof | `oVzRRJv7YFxwABUzPVE56lJmegMwKj_bEsfdATEzT7A` |

The session transcripts under `core/app/cli/src/test/resources/ipc-contract/auth/` replay these
vectors and the refusal shapes over a live socket (one connection per file).

## 8. Contract tests

- `IpcAuthTest` — vectors, the per-connection state machine, verb classes, token files (permissions,
  rotation, refusal).
- `IpcAuthServerTest` — the server over a socket (timeout, closing after three failures, replayed proof,
  rotation, read scope) and the engine client against a reference server written from this text.
- `IpcContractCorpusTest` — class table versus corpus; the `auth/` transcripts.
- `DaemonRuntimeTest`, `DaemonStopTest` — the daemon writes owner-only tokens before it listens, serves
  only authenticated connections, rotates its tokens, refuses to start without them (no socket, lock
  released, one `IPC startup refused:` line); `daemon stop` without the full token does not stop it.
- `OwnerOnlyGrantsTest` — the read-only permission check.

## 9. Rollout

- `IPC_PROTOCOL_VERSION` is 2. A client that does not know the handshake gets `auth_required` for every
  verb, so the clients that talk to the daemon — the Linux co-daemon above all — ship with or before the
  engine that speaks protocol 2.
- The Linux co-daemon is started by `unidrive mount` with the socket path only; it finds the token in the
  profile's config folder (or a later argument agreed with that repository).
