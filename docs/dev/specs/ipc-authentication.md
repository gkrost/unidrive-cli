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
| read | `hydration.close_read`, `daemon.status`, `hydration.list`, `hydration.stat`, `hydration.locate`, `hydration.handle_status`, `hydration.last_synced`, `hydration.subscribe`, `sync.subscribe`, `hydration.open_read`, `history.list`, `history.subscribe`, `metrics.query`, `ignore.explain`, `view.page`, `scope.get` |
| write | `hydration.hydrate`, `hydration.dehydrate`, `hydration.create`, `hydration.open_write`, `hydration.open_write_begin`, `hydration.close_handle`, `hydration.mkdir`, `hydration.unlink`, `hydration.rmdir`, `hydration.rename`, `hydration.cancel`, `sync.enumerate`, `hydration.matches_local`, `hydration.refuse_name`, `share.create`, `history.retry`, `history.report` |
| admin | `daemon.shutdown`, `daemon.pause`, `daemon.resume`, `refresh.run`, `auth.logout`, `auth.begin`, `auth.complete`, `auth.login`, `auth.check`, `vault.unlock`, `vault.lock`, `scope.set`, `presence.enroll`, `presence.challenge` |

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
`hydration.stat`, `hydration.locate`, `hydration.handle_status`, `hydration.last_synced`, `sync.subscribe`, `metrics.query`, `ignore.explain`) answer from state the daemon already holds.
Two may ask the provider for a view's list (ipc-write-protocol.md section 4.2): `hydration.list` of a view's folder and
`view.page`. Both are bounded by the view's cache: one request in flight per view, and no new request within its time to live
for a list no longer than one already asked for (`view.page` rounds its size up to a multiple of 50, at most 1,000 references),
so a read connection cannot make the daemon ask the provider more than a few times a minute. Neither downloads content.
`scope.get` with `list_excluded:true` lists the cloud root once and keeps the answer 10 s (ipc-write-protocol.md section 3.10).

**`hydration.matches_local` is a write-class verb although it changes nothing** (archive#1219). It reads a local file the caller names and answers whether that file holds the bytes of a cloud item (`same`, `differs`, `unknown`). On a `read` connection that would be an oracle over any file the user can read ("is this file the cloud copy of X?"), so it needs scope `full`, like the mount's own calls. The reply carries a method token, never a hash.

`IpcContractCorpusTest` keeps the table equal to the contract corpus: every corpus verb has a class and
the table holds no verb the daemon does not register.

## 4a. Presence proofs (contract level 14)

gkrost/unidrive-cli#798. A daemon can ask
for a **presence proof** before it runs a dangerous verb: a signature, made by the user's platform credential (the
Windows Hello keyring of the tray or the unlock helper), over a nonce the daemon issued for exactly that request. The
daemon only verifies; it never prompts and never talks to a platform API.

**Policy.** `[general] require_presence` in config.toml, read at daemon start:

| Value | Guarded verbs |
|---|---|
| `off` (default, also when unset) | none: the daemon behaves as before |
| `destructive` | `auth.logout` |
| `admin` | every admin-class verb of section 4 except `presence.enroll` and `presence.challenge` |

Any other value is a configuration error: `daemon run` exits 78 and names the value (a typo is never read as `off`).
`admin` is an opt-in strict tier. It also refuses the CLI's own guarded requests with `presence_required`, because the CLI cannot sign a proof yet:

- `profile remove --stop` sends `daemon.shutdown`, so under `admin` it fails until the daemon is stopped another way or the policy is lowered.
- `unidrive daemon stop` does not turn that refusal into a kill. It prints that the daemon requires a presence proof for `daemon.shutdown` and exits 1; `daemon stop --force` terminates the process without its shutdown hooks.
- `unidrive auth logout` against a running daemon fails with `presence_required` under `destructive` and `admin`: the sign-out is `auth.logout`. The sign-in steps (`auth login`, `auth begin`, `auth complete`, `auth check`) fail the same way under `admin`. Each says that the daemon needs a presence proof for that verb: do it from the app that can sign, or stop the daemon and run the command on the stopped profile.
The `destructive` row is `IpcAuth.PRESENCE_DESTRUCTIVE_VERBS`. Of the design's destructive list, only `auth.logout` is an
IPC verb today. `profile remove`, `trash purge --remote`, `relocate --delete-source`, `xtra export-key` and
`vault change-passphrase` are CLI commands that run without the daemon. The client's drain of its delete hold
(the marked `hydration.unlink` / `hydration.rmdir` requests, one per item) is a series of requests, not one. The daemon
guards none of these yet.

**Anchor.** The RSA public key (at least 2048 bits) the daemon verifies against, one per profile:
`presence.anchor` beside the IPC tokens, one line holding the base64url of its X.509 SubjectPublicKeyInfo (DER),
written owner-only like the tokens. A file that holds no such key counts as no anchor. While an anchor exists,
`daemon.status` lists the capability `user-presence:v1` (also in the locked shape). That says an anchor is enrolled and no more.
Whether a proof is needed is the `presence` member of `daemon.status`, in both shapes, always present:
`"presence":{"policy":"off"|"destructive"|"admin","enrolled":true|false}`. A client reads it before it sends a guarded verb,
and shows that the protection is on.

**Signed message.** A client signs, with `SHA256withRSA` (RSASSA-PKCS1-v1_5 over SHA-256), the UTF-8 bytes of

    unidrive-presence-v1|<profile byte length>:<profile>|<verb>|<params_hash>|<nonce>

`<verb>` is the verb the proof is for. `<params_hash>` is the base64url (unpadded) SHA-256 of the canonical text of the
request's params: the request object without the members the proof does not bind (for a guarded verb `verb` and
`presence`; for `presence.enroll` see below). The canonical text has no whitespace and sorts the object members by name
(UTF-16 code units, as RFC 8785), recursively. A string is its decoded value, written with the five short escapes
(`\"`, `\\`, `\b`, `\f`, `\n`, `\r`, `\t`) and `\u00xx` (lowercase hex) for the other characters below U+0020; everything
else is as is: `/`, DEL (U+007F), U+2028 and U+2029, and every non-ASCII character are written unescaped, in UTF-8. A client whose
JSON encoder escapes non-ASCII or U+2028/U+2029 must switch that off for this text, or its hash differs and the proof is
refused with `presence_invalid`. A repeated member keeps its last value. Numbers keep their literal, so a client writes
integers without a fraction or exponent. A request that differs in any bound member has another hash, and its proof is
refused.

Under `admin` the bound members include secrets: `vault.unlock`'s `passphrase`, `auth.login`'s `password` and `totp`. The
client hashes the request with the secret in it and hands only that digest to the signing helper; the daemon's nonce is in
the signed message, so the digest inside a PKCS#1 v1.5 signature gives nothing away.

**`presence.challenge`** `{"verb":"presence.challenge","target":"<verb>","params_hash":"<hash>"}` returns
`{"ok":true,"nonce":"<base64url of 32 random bytes>","expires_in_ms":60000}`. The nonce is single use and bound to
`target` and `params_hash`. At most 32 nonces are outstanding per daemon, and a new one drops the oldest. `target` must
match `[a-z][a-z0-9_.]{0,63}` (else `invalid_target`), and `params_hash` must decode to 32 bytes (else
`invalid_params_hash`). The daemon issues nonces with or without an anchor, so the enrollment uses them too.

**A guarded verb** carries `"presence":{"nonce":"<nonce>","signature":"<base64url>"}`. Before its handler runs, after
the scope check, the daemon refuses it fail-closed:

| Refusal | When |
|---|---|
| `{"ok":false,"error":"presence_required","verb":"…","enrolled":false\|true}` | no `presence` member (or no string `nonce` / `signature` in it), or no anchor (`enrolled:false`) |
| `{"ok":false,"error":"presence_invalid","verb":"…"}` | the nonce is unknown or spent, or it was issued for another verb or other params, or the signature does not verify under the anchor |
| `{"ok":false,"error":"presence_expired","verb":"…"}` | the nonce was issued more than 60 s ago |

Every check spends the nonce: a refused proof cannot be retried, and a replayed one is `presence_invalid`. With policy
`off`, a `presence` member is ignored.

**`presence.enroll`** stores or replaces the anchor:
`{"verb":"presence.enroll","public_key":"<spki>","nonce":"…","signature":"…","passphrase":"…"}` for the first one, and
`{…,"anchor_signature":"…"}` instead of `passphrase` for a rotation. The nonce comes from `presence.challenge` with
target `presence.enroll` over the params without `verb`, `nonce`, `signature`, `anchor_signature` and `passphrase`
(today: `{"public_key":"…"}`). `signature` is made by the **new** key and proves possession. `anchor_signature` is made
by the **current** anchor over the same message. In order:

| Reply | When |
|---|---|
| `presence_disabled` | the policy is `off`: the verb answers before it checks anything, and asks for no passphrase |
| `invalid_public_key` | `public_key` is missing, or not an RSA SubjectPublicKeyInfo of at least 2048 bits |
| `missing_proof` | no string `nonce` or `signature` |
| `presence_invalid` / `presence_expired` (with `"verb":"presence.enroll"`) | the possession proof does not hold, as for a guarded verb (the nonce is spent from here on) |
| `passphrase_required` | no anchor yet, and no `passphrase` |
| `vault_absent` | no anchor yet, and the profile has no `vault.enc`: a first enrollment needs the vault passphrase |
| `vault_bad_passphrase` | no anchor yet, and the passphrase does not open `vault.enc` (after 250 ms) |
| `presence_required` (with `"enrolled":true`) | an anchor exists, and no `anchor_signature`: the passphrase alone never replaces an anchor |
| `presence_invalid` | an anchor exists, and `anchor_signature` does not verify under it |
| `anchor_write_failed` | the anchor file cannot be written owner-only |
| `{"ok":true,"rotated":false\|true,"anchor":"<base64url SHA-256 of the SPKI>"}` | stored; it takes effect at once |

A client enrolls after `require_presence` is set to `destructive` or `admin` and the daemon has been restarted. The verbs stay
registered under `off` (the bind-last guarantee), so a daemon that never asked for presence proofs does not check the vault
passphrase for a client.

A lost credential (a reset PIN, a new machine) cannot rotate: stop the daemon, delete `presence.anchor`, and enroll
again with the passphrase.

**Decisions.** What the control covers, so that the gaps below are decisions and not oversights:

- It holds against the use of a copied IPC token alone. A same-user process that can also change the profile's own files (the config switch, the anchor) is outside it; see Limits. `daemon.shutdown` is not guarded under `destructive`.
- The gate hashes the request as the JSON parser reads it, and a repeated member keeps its last value. The handlers of the
  guarded verbs parse the same way, and the dispatcher already refuses a repeated `verb`, so the proof and the handler agree
  on one request. Refusing a repeated member on guarded requests would make that independent of the handlers; it is not done.
- A proof under `admin` can cover a request with a secret in it (see the canonical text above); the signing helper sees only
  its digest.

**Limits.** Any full-scope connection can evict another client's outstanding nonce by issuing 32 challenges; the evicted proof is
refused with `presence_invalid` and the client retries with a new challenge. That is availability only. A proof shows that a human approved a prompt, not which program asked for it. A same-user process can
start the helper too, and it can also rewrite `presence.anchor` or `require_presence` in the profile's own files. The
named-operation prompt is the defence. This is not a sandbox.

**Test vectors.** Profile `p1`. The params `{ "force" : true }` canonicalise to `{"force":true}`. The empty params `{}`
hash to `RBNvo1WzZ4oRRq0W9-hknpT7T8If536DEMBg9hyq_4o`. With nonce bytes `01 01 … 01` (32 bytes,
`AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE`), `auth.logout` with empty params signs
`unidrive-presence-v1|2:p1|auth.logout|RBNvo1WzZ4oRRq0W9-hknpT7T8If536DEMBg9hyq_4o|AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE`.
Canonical text of the request params (`PresenceProofsTest.canonical_text_pins_numbers_escapes_repeats_and_member_order`):

| Params | Canonical text |
|---|---|
| `{"n":[1.0,1e3,-0,123456789012345678901234567890,0.000001]}` | unchanged: numbers keep their literal |
| `{"t":"\u00e9","u":"é"}` | `{"t":"é","u":"é"}` |
| `{"s":"é\u0001\/\"\\\u007f\u2028"}` | `é`, `\u0001`, `/`, `\"`, `\\`, then DEL and U+2028 as is |
| `{"s":"\b\f\n\r\t\u000b\u001f"}` | `\b\f\n\r\t\u000b\u001f` |
| `{"a":1,"a":2}` | `{"a":2}` |
| `{"\uff5e":3,"\ud83d\ude00":2,"s":1}` | members in the order `s`, U+1F600, U+FF5E (UTF-16 code units; code point order would swap the last two) |

`sessions/presence-destructive.ndjson` in the corpus replays a whole enrollment, the guarded `auth.logout` and a
rotation with fixed public keys and signatures.

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

## 5b. Sign-in for front-ends: login (Internxt)

`auth login` signs an Internxt profile in with the e-mail, the password and, when the account has one, the current
two-factor code. The password is never an argument: `--password-stdin` is required. A front-end can pass the e-mail and
the one-time code on stdin as well (`--email-stdin`, `--totp-stdin`), so they don't appear in the process list.
Standard input then holds one line each, in this order: e-mail, password, code (only the lines whose flag is given).
Piped input is UTF-8. A line ends at a line feed (a carriage return before it is dropped); the e-mail and the code are trimmed, the password is kept as typed.
Interactive input uses the console's character reader and encoding for every field.
`--email` with `--email-stdin`, or `--totp` with `--totp-stdin`, is refused (`invalid_value`); no e-mail at all is
`missing_email`. On a terminal the prompts go to stderr and the password and the code are read without echo.

## 6. Error tokens

| Token | When |
|---|---|
| `auth_required` | any verb other than `hello`, `hello.proof`, `daemon.status` before authentication |
| `auth_failed` | a refused handshake step (after a 250 ms wait; the third closes the connection) |
| `forbidden` | a verb outside the connection's scope (with `"scope":"read"`) |
| `presence_required`, `presence_invalid`, `presence_expired` | a guarded verb without a valid presence proof (section 4a) |
| `handler_threw` | the verb's handler failed (with `"verb"`); only a `full` connection also gets `"message"`, the exception text with URL queries redacted. The daemon log has the detail. |

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
- `IpcContractCorpusTest` — class table versus corpus; the `auth/` transcripts; `sessions/presence-destructive.ndjson`.
- `PresenceProofsTest` — the policy table, the bound single-use nonces, the anchor rules, the capability, the vectors.
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

### Handle lifetime (contract level 11)

Each connection can hold 1,024 distinct handle IDs, including opens in progress. A new ID beyond that limit returns `too_many_handles`. Release a read handle with `hydration.close_read` and its `handle_id` on the original connection. This idempotent READ verb ignores unknown IDs and write handles. FULL clients can still use `hydration.close_handle` with pooled connections.
