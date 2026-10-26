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

What the code on the default branch does, so that a report can say what it changes. Open hardening work is tracked
in [#83](https://github.com/gkrost/unidrive-cli/issues/83).

- **Tokens and keys at rest.** Internxt credentials go to the host's native store (Linux Secret Service through
  `secret-tool`, Windows DPAPI for the current user, macOS login Keychain); an unavailable or locked store is a failure,
  never a plaintext file. OneDrive's token and pending sign-in are protected the same way on Windows (DPAPI) and macOS
  (Keychain) by default, and a `token.json` written earlier is protected the first time it is read; a front-end that
  embeds the engine can pass its own `CredentialProtector`. On Linux, and for Dropbox on every platform, the token stays
  in `token.json` in the profile folder: written atomically, owner-only (`0600` in a `0700` folder on POSIX, an
  owner-only ACL on Windows), not encrypted (a headless Linux daemon may have no Secret Service). S3 keys are in the profile's `config.toml` or are read from the environment. Provider notes:
  [`core/providers/onedrive`](core/providers/onedrive/README.md), [`core/providers/internxt`](core/providers/internxt/README.md).
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
  ([#33](https://github.com/gkrost/unidrive-cli/issues/33)).
- **Build and repository.** The release build verifies every dependency checksum strictly; normal builds verify
  leniently. `main` requires a pull request and the `check` and `windows-security` status checks. The state of the
  repository's GitHub settings is in [`docs/dev/ops/security-posture.md`](docs/dev/ops/security-posture.md).

## Supported versions

UniDrive has not yet cut a stable release. Security fixes are applied to the
default branch; there are no separately maintained release branches to
backport to. Once releases begin, this section will record which are supported.

## What to expect

After a report is received, we aim to acknowledge it, confirm the issue, and
work on a fix in coordination with the reporter. Credit is offered to reporters
who wish to be named once a fix is published.
