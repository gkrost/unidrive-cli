# Unlock helper protocol

Issue: the platform-neutral seam between the engine and an OS-backed unlock factor
(Windows Hello keyring, macOS Keychain with presence, a Linux TPM-backed credential).
The engine never talks to a platform API: it starts a helper executable and speaks this
protocol over the helper's stdin/stdout. One JSON object each way, one request per
process run.

## Configuration

`[general] unlock_helper = "…"` in config.toml holds the helper's fixed absolute
install path. The engine never looks the helper up on PATH, and never runs it from a
relative path: a configured value that is not absolute is a configuration error, and a
configured path that is missing or not a regular file is an error that names the path
(the helper is not silently skipped — a configured unlock factor that cannot run must
not degrade to a weaker one without the operator knowing).

When no `unlock_helper` is configured, the step does not exist and the resolution chain
is env → systemd credential → console, as before.

## Request

The engine writes exactly one line to the helper's standard input and then closes it:

```json
{"op":"unlock","secret":"vault","keyring":"<path>"}
```

- `op` is `"unlock"` for every op the engine implements today. A second op, `sign`, is proposed below.
- `secret` is `"vault"` (the vault passphrase, `vault.enc`) or `"xtra"` (the X-tra
  passphrase, `xtra.key`). One run asks for one secret.
- `keyring` is the absolute path of the keyring file the helper manages (per install:
  `<config base dir>/keyring.hello`). The engine does not read that file; it only names
  it, so the helper can find its wrapped key material.

The line is UTF-8 and ends with `\n`. The engine strips both passphrase environment
variables from the helper's environment (`childProcessBuilder`).

## Reply

The helper writes exactly one JSON object to its standard output (one line, UTF-8) and
exits 0 after a handled request. Either:

```json
{"secret":"<base64>"}
```

`secret` is the base64 (standard alphabet, padded) of the UTF-8 bytes of the secret.
The engine decodes it to a `CharArray`, scrubs the decoded bytes and the array after
use, and treats the reply as the next step's answer: for `vault` it decrypts
`vault.enc`, for `xtra` it loads `xtra.key`.

Or:

```json
{"error":"<code>"}
```

The codes:

| Code | Meaning | Effect in the engine |
| --- | --- | --- |
| `declined` | The user refused the prompt. | Soft: fall through to the next step of the passphrase chain (the console, when one exists). |
| `unavailable` | The unlock factor cannot run now (no window station, RDP session, hardware missing). | Soft: fall through, like `declined`. |
| `locked_out` | The factor is temporarily locked (too many failed attempts, BitLocker-style cooldown). | Soft: fall through, like `declined`. |
| `witness_mismatch` | The keyring's key does not match the witness the helper holds (re-enrollment, restored machine). | Hard: the chain stops; the refusal names the code. A silent re-enrollment must never release a secret. |
| any other | Helper-specific failure (malformed keyring, internal error). | Hard: the chain stops; the refusal names the code. |

A soft error (`declined` / `unavailable` / `locked_out`) falls through to the console
when one exists. Without a console, the run follows the refusal path it already has:
a CLI command exits with the reason in the message; `daemon run` starts locked (the
locked state reports the reason; `vault.unlock` is then the way in).

A hard error stops the chain even when a console exists: the refusal names the helper's
code, because a witness mismatch or a broken helper is not something a passphrase
prompt should paper over — the operator decides (re-enroll, reinstall, or use the
passphrase path by removing the helper from the config).

A reply that is not valid JSON, or that is neither shape above, is the hard error
`invalid_reply`. A helper that does not answer within 60 seconds is destroyed and the
run reports the hard error `timeout`.

## Proposed: the `sign` op

**Status: proposed, not implemented.** The CLI does not send `sign` yet, and nothing in the engine reads
its reply. The Windows client proposal (the `unidrive-unlock` helper answering `sign`) defines this wire
from its side; this section is the engine's side of it, to be agreed before either relies on it.

**Purpose.** The CLI's guarded destructive commands run without a daemon, so they cannot ask a daemon
for a nonce the way a client of a running daemon does. The remaining scope of cli#798 is `profile remove`,
`trash purge --remote`, `relocate --delete-source`, `xtra export-key` and `vault change-passphrase`: each will
get a presence proof from the helper. `sign` is the helper's half of that: it signs a message with the key
the user's platform credential holds (the same keyring as `unlock`), after the platform's own presence prompt.

Request (one line, same framing as `unlock`):

```json
{"op":"sign","keyring":"<path>","message":"<base64>"}
```

- `op` is `"sign"`.
- `keyring` is the absolute path of the keyring file, as for `unlock`.
- `message` is the base64 (standard alphabet, padded) of the bytes to sign. These are the presence-proof
  message bytes; their format and the algorithm the signature uses are defined in
  `docs/dev/specs/ipc-authentication.md` section 4a (Signed message), not here. The helper signs them as given
  and does not interpret them.

Reply, on success:

```json
{"signature":"<base64>"}
```

`signature` is the base64 (standard alphabet, padded) of the raw signature bytes. A client that puts a
signature on the wire as base64url re-encodes it (section 4a).

Example exchange:

```
-> {"op":"sign","keyring":"/home/u/.config/unidrive/keyring.hello","message":"dW5pZHJpdmUtcHJlc2VuY2UtdjF8..."}
<- {"signature":"MEUCIQD..."}
```

Errors use the shape and the codes of the reply table above, with the same effect classes and nothing new:
`declined` (the user refused the presence prompt), `unavailable`, `locked_out` and `witness_mismatch` mean
what they mean for `unlock`; a malformed request or keyring, or an internal failure, is a helper-specific
code (the "any other" row). The existing set expresses every case, so no code is added.

The effect in the engine differs from `unlock` in one way, and that is a proposal too: there is no
passphrase chain to fall through to. A guarded command that cannot get a proof is refused, whatever the
class of the code, with the code named in the message. A guarded command never runs without its proof
because the helper was soft-unavailable. Under the policy `off` the CLI does not call `sign` at all.

The same engine-side rules as `unlock` apply: a reply that is not valid JSON or is neither shape is
`invalid_reply`, and no answer within 60 seconds is `timeout`.

## The X-tra passphrase in the vault

The X-tra passphrase is a vault secret (Windows Hello design, section 2). `unidrive vault xtra-set`
stores it in `vault.enc` as the reserved entry `.xtra` (field `passphrase`; `.` is not valid in a
profile name, so no profile collides with it) and `vault xtra-clear` drops it. The X-tra passphrase
chain is: environment variable `UNIDRIVE_XTRA_PASS`, systemd credential `unidrive-xtra-pass`, the
**vault's `.xtra` entry**, the unlock helper (`secret` = `xtra`), the console. The vault step reads the
in-memory vault data and never prompts: a daemon that was started locked and received `vault.unlock` needs
no second unlock for an `xtra_encryption` profile, and `vault.lock` takes the passphrase out of reach
again. A one-shot CLI process (including `xtra import-key` and `import-age`) follows the same chain: it
reads the vault first when the vault's passphrase is available without a prompt (environment, systemd
credential, unlock helper), and otherwise skips the vault step.

**Locked start and the marker.** Whether the vault holds `.xtra` is readable only after an unlock, but a
locked daemon must decide at its start whether an `xtra_encryption` profile (no environment or credential
source for the X-tra passphrase) waits for `vault.unlock`. It waits only when the vault is *known* to carry
`.xtra`: an empty, non-secret marker file `vault.enc.xtra` beside `vault.enc`. Every vault write
(`xtra-set`, `xtra-clear`, `encrypt`, `decrypt`, `change-passphrase`, `forget`, `profile remove`) and every
vault read (so each unlock) brings the marker in line with what the vault holds; deleting the vault deletes
the marker. Marker repair checks that the encrypted bytes are still the ones just read, while holding
the same file lock as vault replacement and deletion. A helper-only install (no marker) starts unlocked and asks the helper at the first content
operation, as before; a vault written before the marker existed gets it on its first unlock. Under
`require_vault = true` the exit-78 rule is unchanged and independent of the marker.

A daemon with `vault.enc` advertises the capability `vault-xtra:v1` (contract level 14) next to
`vault-unlock:v1`: this engine releases a vaulted X-tra passphrase on `vault.unlock`; whether the vault holds
one is known only after the unlock.

**Commands.** `vault xtra-set` proves the passphrase against the X-tra key files beside `config.toml` before
storing it and refuses when none opens. `xtra change-passphrase*` update the vault's `.xtra` entry in the same
run when `vault.enc` exists and its passphrase is available without a prompt; otherwise they print the
reminder to run `vault xtra-set` and exit non-zero. `vault decrypt` keeps the entry in the vault (it has no
config section to go back into).

## Out of scope here

- What the helper does with the keyring (enrollment, wrapping, witness) is the client
  platform's business (Windows Hello design, phase P2). The engine only requires this
  wire.
- The `vault.unlock` / `vault.lock` IPC verbs (a client delivers the unlocked secret to
  a running daemon) are separate: see `docs/dev/specs/ipc-authentication.md` for the
  transport and the verb classes.
