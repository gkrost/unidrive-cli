# Security Policy

## Reporting a vulnerability

Please report security vulnerabilities privately through GitHub's
**[Report a vulnerability](https://github.com/gkrost/unidrive-cli/security/advisories/new)**
form (the **Security** tab → *Report a vulnerability*). Private vulnerability
reporting is enabled on this repository, so the report and any discussion stay
confidential until a fix is published.

If you cannot use that form, write to **oss@unidrive.krost.org** (open-source topics
only). Do not put secrets in the mail; ask for a way to share details if you need one.

Do **not** open a public issue for a suspected vulnerability, and do not include
credentials, tokens, or other secrets in a report — describe how to reproduce
the problem instead.

## Scope

UniDrive is a cloud-sync engine that authenticates to third-party providers
(Internxt, OneDrive, Dropbox, S3-compatible storage) on the user's behalf and
stores their data locally and in the cloud. Reports that are especially in scope:

- Disclosure or mishandling of provider credentials, OAuth tokens, or refresh
  tokens.
- Data-integrity or data-loss defects in the sync/hydration engine.
- Weaknesses in local-cache handling, the IPC surface between the daemon and
  its clients, or the encryption path for end-to-end-encrypted providers.

## How credentials and content are handled today

What the code on the default branch does, so that a report can say what it changes. Open hardening work carries the
[`security`](https://github.com/gkrost/unidrive-cli/issues?q=is%3Aopen+label%3Asecurity) label.

- **Tokens and keys at rest.** Internxt credentials go to the host's native store (Linux Secret Service through
  `secret-tool`, Windows DPAPI for the current user, macOS login Keychain); an unavailable or locked store is a failure,
  never a plaintext file. OneDrive's and Dropbox's tokens (Dropbox also its pending sign-in) are protected the same way
  on Windows (DPAPI) and macOS (Keychain) by default, and a `token.json` written earlier is protected the first time it
  is read; a front-end that embeds the engine can pass its own `CredentialProtector`. On Linux the token stays
  in `token.json` in the profile folder: written atomically, owner-only (`0600` in a `0700` folder on POSIX, an
  owner-only ACL on Windows), not encrypted (a headless Linux daemon may have no Secret Service). The provider secret fields of `config.toml` (S3
  `access_key_id` / `secret_access_key`, `password`, `client_id`, `client_secret`) are protected field by field with
  DPAPI on Windows and the Keychain on macOS, and stay plain on Linux; S3 keys can also come from the environment. A
  field protected on another computer is refused, never sent as a credential. Provider notes:
  [`core/providers/onedrive`](core/providers/onedrive/README.md), [`core/providers/internxt`](core/providers/internxt/README.md).
- **The vault and the seal.** `vault.enc` is a passphrase-encrypted file for secrets a headless daemon needs. With
  `vault seal-credentials on`, the files above (the Internxt mnemonic, the OneDrive and Dropbox tokens and pending
  sign-ins) and the `config.toml` secret fields are sealed with AES-GCM under a key kept in the vault, inside the
  platform protection (on Linux over the owner-only file or the plain field). A sealed file cannot be read while the
  vault is locked, and is lost with the vault: the recovery is a new sign-in (README, `vault`). The vault can also keep
  the X-tra passphrase and, opt-in, an Internxt password for the daemon's own re-sign-in; no command reads the password
  back. The passphrase reaches the engine on standard input or through the unlock helper
  ([spec](docs/dev/specs/unlock-helper-protocol.md)), never as an argument, and child processes the engine starts do not
  inherit `UNIDRIVE_VAULT_PASS` or `UNIDRIVE_XTRA_PASS`.
- **Client-side encryption (X-tra).** A profile with `xtra_encryption = true` encrypts file content before upload
  (`aes-gcm-sym`, the default for new keys, `aes-gcm`, `age-v1` or `tink-aes-gcm`); names, sizes and the folder tree
  stay visible to the provider. Only the symmetric algorithms (`aes-gcm-sym`, `tink-aes-gcm`) are quantum-resistant:
  `aes-gcm` wraps each file key with RSA-2048 and `age-v1` with X25519, which a large quantum computer can break. A file of
  another algorithm is refused before any key is asked for, and a decrypt is staged, so a corrupt or truncated download
  does not replace the previous file.
- **Where the bearer token goes.** OneDrive requests that carry the token are refused unless the URL has the scheme,
  host and port of the Graph endpoint, so a paging or delta link taken from a response body cannot send the token to
  another host. The pre-signed download URL is fetched without the token.
- **Integrity of Internxt downloads.** The content is AES-CTR with no authentication tag; the hash the network recorded
  for the stored shard is the only check, and a mismatch refuses the content. A recorded hash in an unknown shape (blank,
  another length, not hex) cannot be checked, and the download is refused before the shard is requested. The profile key
  `allow_unverified_shard_hash = true` opts a profile out, with one warning per object and run. A shard URL that is not
  `https` is refused. A plaintext hash recorded at upload and compared at download is not built yet.
- **The daemon's IPC.** Every daemon requires an authenticated handshake over two owner-only token files in the profile's
  config folder; the daemon refuses to start when it cannot create and verify them
  ([spec](docs/dev/specs/ipc-authentication.md)). The engine's own client and the Windows client fail closed without a
  token file. The Linux co-daemon and the MCP bridge still carry a temporary protocol-1 fallback: a client that finds no token
  file talks without the handshake, with a logged warning (every daemon of this engine requires the handshake, so
  this only helps against an older engine). Removing it waits until after the MVP release
  ([#33](https://github.com/gkrost/unidrive-cli/issues/33)). With `[general] require_presence` set to `destructive` or
  `admin`, the daemon also asks for a presence proof before guarded verbs: a signature by the user's platform
  credential (Windows Hello) over a single-use nonce bound to the verb and its parameters, valid for 60 s. The first
  enrollment of the signing key needs the vault passphrase, and a key rotation a proof under the current key. The
  default is `off`.
- **Build and repository.** The release build verifies every dependency checksum strictly; normal builds verify
  leniently. `main` requires a pull request and the `check` and `windows-security` status checks. The state of the
  repository's GitHub settings is in [`docs/dev/ops/security-posture.md`](docs/dev/ops/security-posture.md).

## Supported versions

UniDrive has published preview releases only (0.0.x; the latest is
[v0.0.3](https://github.com/gkrost/unidrive-cli/releases/tag/v0.0.3)). Security fixes are applied to the
default branch and ship with the next release; there are no separately maintained release branches to
backport to. Once a stable release is out, this section will record which versions are supported.

## What to expect

After a report is received, we aim to acknowledge it, confirm the issue, and
work on a fix in coordination with the reporter. Credit is offered to reporters
who wish to be named once a fix is published.
