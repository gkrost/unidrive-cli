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

- `op` is always `"unlock"`.
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

## Out of scope here

- What the helper does with the keyring (enrollment, wrapping, witness) is the client
  platform's business (Windows Hello design, phase P2). The engine only requires this
  wire.
- The `vault.unlock` / `vault.lock` IPC verbs (a client delivers the unlocked secret to
  a running daemon) are separate: see `docs/dev/specs/ipc-authentication.md` for the
  transport and the verb classes.
