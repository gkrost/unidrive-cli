# `unidrive`

Multi-platform cloud-sync core. Pure JVM, zero telemetry. Modular SPI for Internxt Drive (zero-knowledge E2EE), Microsoft OneDrive (Graph API), Dropbox and S3-compatible storage, plus a local-directory provider for tests. Optional client-side content encryption (X-tra) works over any of them. Runs as a user-space Linux daemon via systemd; platform tiers consume the engine over IPC — [`unidrive-mount-linux`](https://github.com/gkrost/unidrive-mount-linux) (FUSE) and the Windows client (CfAPI; new, changed, renamed and deleted files sync from the mount).

## Technical Layout

```
.
├── core/
│   ├── app/
│   │   ├── cli/            # CLI entry point and subcommand mapping
│   │   ├── cli-spi/        # CLI extension SPI (`org.krost.unidrive.cli.ext`)
│   │   ├── core/           # Provider SPI (`CloudProvider`, `ProviderFactory`), auth and HTTP helpers
│   │   ├── engine-core/    # Shared engine primitives: state.db, path and scope rules, remote gather
│   │   ├── hydration/      # De/hydration pipeline
│   │   ├── sync/           # Sync engine (ships the MVP), IPC server
│   │   └── sync-tracking/  # Frozen tracking-set engine (`unidrive ts`)
│   └── providers/
│       ├── dropbox/        # Dropbox HTTP API v2 client
│       ├── internxt/       # Zero-knowledge encrypted client
│       ├── localfs/        # No-auth local-directory provider for offline development and tests
│       ├── onedrive/       # Microsoft Graph API client
│       └── s3/             # Amazon S3 and S3-compatible buckets
└── dist/                   # User-space install scripts and systemd units
```

## Build

JDK 25+ and Linux with `systemd --user` for the packaged daemon. Internxt uses the host's native credential store: Linux requires an unlocked Secret Service and `secret-tool` (`libsecret-tools`), Windows uses user-scoped DPAPI, and macOS uses the user's login Keychain (macOS is not supported or tested in CI yet). Unsupported platforms and unavailable or locked stores fail closed without writing plaintext credentials; that is the Internxt rule. OneDrive's and Dropbox's tokens (Dropbox also its pending sign-in) use the same stores on Windows (DPAPI) and macOS (Keychain), and a token file written earlier is protected the first time it is read; on Linux they stay in `token.json` in the profile folder, written atomically and readable by the current user only, not encrypted (see [`SECURITY.md`](SECURITY.md)). With `vault seal-credentials on` these files and the provider secrets in `config.toml` are also sealed under a key kept in the vault (see `vault` below). The Gradle wrapper lives in `core/`.

```bash
cd core && ./gradlew :app:cli:shadowJar    # build fat JAR → core/app/cli/build/libs/unidrive-*.jar
```

Local build time: `core/gradle.properties` turns the Kotlin build-statistics service off and the build cache on, so a fresh worktree at the same or a nearby commit takes most compile tasks FROM-CACHE (test tasks are never cached), and an unchanged tree's compile tasks stay up to date — also because a dirty tree stamps a fixed build instant instead of the clock. Workers are not capped locally; CI pins `-Dorg.gradle.workers.max=2` in its workflow environments for the four-core hosted runners. Two per-machine notes for command-line builds:

- Every other Kotlin build on the machine pays the same statistics cost: put `kotlin.internal.collectFUSMetrics=false` into `~/.gradle/gradle.properties` and delete `~/.gradle/kotlin-profile` if builds hang at the end (Gradle does not clean that folder; only an IDE that uploads the statistics does).
- `./gradlew <task> -PnoCoverage` skips the JaCoCo agent and the coverage reports for a local run. CI never passes the property.

## Packaged distribution (no user-installed JDK)

A platform package ships a `jlink` runtime image next to the fat JAR. The image
is jlinked from the bundled runtime JDK — the current JDK release train — while
the engine's bytecode stays at the compile toolchain level (`jlink` produces a
runtime of its own version, so the image JVM deliberately leads the bytecode
target):

```bash
cd core && ./gradlew :app:cli:shadowJar :app:cli:runtimeImage
# → core/app/cli/build/runtime-image/  (bin/java + legal/, ~50 MB)
#   core/app/cli/build/runtime-image/bin/java -jar core/app/cli/build/libs/unidrive-*.jar --version
```

The image's JDK module set is pinned — a new dependency that changes it fails
`check` (see `RuntimeModulesTest`, which compares the set with what `jdeps` reports),
and CI runs the whole gate a second time with the tests executing on the runtime
JDK's release (the `check-runtime-jdk` job, a full Zulu JDK on Linux; on every push
to `main`, and on a pull request that changes the build; a manual run brings it to any
branch). The
`runtime-image` CI job builds the image on Linux and Windows and boots the
packaged launcher once against it. Launchers honour
`UNIDRIVE_XMX` (bare size, e.g. `512m`, `2g`; default `2g`) and pin
`-Djdk.net.unixdomain.tmpdir` to the host's temp directory. The Linux launcher
picks the runtime itself (`UNIDRIVE_JAVA` overrides; Java 25+ required, below
25 refused). The Windows launcher honours `UNIDRIVE_JAVA`, prefers the bundled
runtime image a platform package ships (a `runtime-image` folder next to the
launcher, else next to the fat jar), falls back to
`java` on `PATH`, and refuses Java below 25. Launchers and the
CLI's auto-spawn write post-mortem diagnostics — fatal-crash `hs_err` logs, OOM
heap dumps, the daemon's bounded GC log — into `%LOCALAPPDATA%\unidrive\diagnostics`
(Windows) or `~/.local/share/unidrive/diagnostics` (Linux), overridable with
`UNIDRIVE_DIAG_DIR`; pruning stays manual. The bundled runtime
follows the JDK release train: each feature release ships as a normal app
update, with the LTS marks as the long-haul targets.

## Install (user-space, no root)

```bash
cd dist/
./install.sh
```

The Linux distribution stores Internxt credentials in the Linux Secret Service. Install `libsecret-tools` (Debian/Ubuntu: `sudo apt install libsecret-tools`) and ensure the user keyring is unlocked and reachable from the systemd user session before authenticating or starting Internxt sync. UniDrive fails closed when the Secret Service is missing or locked; it does not fall back to plaintext. On Windows the supported backend is current-user DPAPI; on macOS it is the user's unlocked login Keychain. Both fail closed if their native protection service cannot be used. Other platforms are unsupported for Internxt credential storage.

Installs to:
- `~/.local/bin/unidrive`
- `~/.local/lib/unidrive/unidrive-<version>.jar`
- `~/.config/systemd/user/unidrive.service`

Start daemon:
```bash
systemctl --user daemon-reload
systemctl --user enable --now unidrive.service
```

The installed unit runs `unidrive autostart`: mirror profiles use `sync --watch`, while mount profiles use `daemon run`. This keeps the background process aligned with the selected profile's fixed mode.

On Windows the tray client owns autostart: it starts each account at sign-in. The engine writes no logon entry there: `./gradlew :app:cli:deploy` removes the Startup-folder `unidrive.vbs` and the `unidrive-watch.cmd` restart loop an earlier deploy wrote, each only when its content is the one deploy wrote. `unidrive autostart` stays for the Linux units.

Daemon log: `~/.local/share/unidrive/unidrive.log`. Quick triage: `scripts/dev/log-watch.sh --summary`.

## Commands

`unidrive` uses a git-inspired subcommand design.

### Session & Identity
- `auth` — OAuth/token provisioning: the interactive sign-in, `begin` and `complete` (a sign-in in two steps, for front-ends), `login` (Internxt, without a console; the password, and with `--email-stdin` / `--totp-stdin` the e-mail and the two-factor code, on standard input; [spec](docs/dev/specs/ipc-authentication.md)) and `check` (`--json`: asks the provider with one call whether the profile's stored sign-in is still accepted, with no daemon, scan, sync or prompt; [spec](docs/dev/specs/cli-auth-check.md)). A sign-in as another user than the one the profile's sync state belongs to is refused (`account_changed`) before anything is stored ([spec](docs/dev/specs/cli-account-name.md))
- `profile` — multi-profile management (`add`, `list`, `remove`, `default`, `set`, `reset-state`)
  - `remove` deletes the profile's config section, profile folder, IPC tokens and socket, and its hydration and
    ignore-rule caches. It keeps the caches (and prints their path) when the hydration cache holds edits that never
    reached the cloud. If the removed profile was the default and exactly one profile is left, that one becomes the
    default; otherwise `default_profile` is cleared. Refused while a daemon holds the profile (`--stop` asks it to stop
    first) and while uploads are on their way (`--force-pending`). The sync root and mount root are never deleted.
  - `reset-state` shrinks a stopped profile's state after its account was emptied or changed: it drops the `DELETED`
    tombstones, rewrites `state.db`, and deletes `scan-staging.db` and `telemetry.db`. Offline: no daemon, no cloud call.
- `backup` — backup profiles (`add`, `list`)
- `logout` — sign out: remove stored credentials and revoke the token where the provider has a revoke call (Dropbox; for the others only the local credentials go, and the output says so); the sync state is kept (`--delete-state` removes it), and a running daemon performs the sign-out over IPC (`--json` for front-ends)

### Sync
- `sync` — run the sync engine (`SyncEngine`, the engine the MVP ships); the frozen tracking-set engine is reachable only as `ts`
- `refresh` — update `state.db` with remote changes through the running daemon (`--reset` re-enumerates the whole cloud tree)
- `apply` — drain pending transfers left by a `refresh`, without fetching remote changes again
- `status` — show alignment, transfers, exceptions
- `pending` — `list` and `drop` the uploads waiting in a profile's queue
- `rm` — remove a remote path ([spec](docs/dev/specs/cli-rm-command.md))
- `dedupe` — list the duplicate remote items a profile keeps as `name (dup-xxxxxxxx).ext` and what a keep rule (`--keep newest|largest|id:<id>`) would do with each set; a dry run that changes nothing, and with `--apply` the twins the rule does not keep go to the provider's trash by their ids ([spec](docs/dev/specs/cli-dedupe-command.md))
- `autostart` — what the installed systemd unit runs: `sync --watch` for a mirror profile, `daemon run` for a mount profile
- `scope` — show or change the sync scope (`sync_path`) of a profile: `show`, `set <paths...>`, `clear`. `set` and `clear` edit config.toml, apply the scope transition to `state.db` (rows outside the new scope stop being tracked; files stay), replace the scoped history with the new scope, and print what the next run does; after `clear` the next bidirectional sync reconciles the whole drive without `--full-tree`. Refused while a daemon or sync holds the profile; `--json` for front-ends
- `conflicts` — show recent conflicts; restore or clear conflict backups (`list`, `restore`, `clear`)
- `verify` — report-only audit of the sync root, `state.db` and a live remote listing (exit 0 converged, 1 divergence, 2 could not audit)
- `doctor` — read-only diagnostics: drift, staleness, destructive activity, scope, hydration
- `log` — show recent sync activity

### Daemon & Mount
- `daemon` — per-profile daemon: `run` (foreground until SIGTERM), `status`, `stop`. A headless start with a `vault.enc` and no
  source for its passphrase starts locked when the profile reads fields from the vault (or exits 78 with
  `[general] require_vault`). A locked daemon waits for `vault.unlock` over IPC, which releases the vault's secrets to
  it (the X-tra passphrase and a stored Internxt password among them). With a stored Internxt password the daemon signs an expired
  Internxt session in again by itself; an account with a two-factor code reports `totp_required` instead.
  `[general] require_presence` (`off` by default, `destructive`, `admin`) makes the daemon ask for a presence proof, a
  signature by the user's platform credential (Windows Hello), before guarded verbs run
  ([spec](docs/dev/specs/ipc-authentication.md), section 4a). The CLI cannot sign yet, so under `admin` its own guarded
  requests (`profile remove --stop`, for example) are refused with `presence_required`.
- `mount` — start the Linux FUSE co-daemon for a profile ([`unidrive-mount-linux`](https://github.com/gkrost/unidrive-mount-linux)); needs a running `daemon run`
- `migrate` — one-time conversion of a legacy profile to a fixed mirror or mount mode ([independent-profiles](docs/adr/independent-profiles.md)): `inventory` (read-only dry run), `convert --mode mount|mirror`, and `upload-quarantined` for the files a mount conversion set aside

### Storage
- `ls` — list a remote folder (no recursion; `--live` forces a provider query)
- `get` — download file content (hydrate)
- `pin / unpin` — add or remove an eager-download rule (glob pattern)
- `free` — dehydrate to sparse markers
- `quota` — show storage quota
- `share` — generate a shareable link for a file or folder
- `trash` — trash emulation (`list`, `restore`, `purge`)
- `versions` — versioned files (`list`, `restore`, `purge`)
- `relocate` — migrate data between cloud providers (`--from`, `--to`, `--delete-source`; the delete pass removes only what the migration transferred, by id, and is refused after any failed or size-only-skipped file)
- `vault` — credential vault (`init`, `encrypt`, `decrypt`, `change-passphrase`, `forget`, `seal-credentials`,
  `xtra-set`, `xtra-clear`, `internxt-password-set`, `internxt-password-clear`). Every subcommand takes `--json`: it
  prints the result, or the refusal with its error token, as one JSON object and never prompts. Passphrases and
  passwords come from standard input (`--passphrase-stdin`, `--xtra-passphrase-stdin`, `--password-stdin`) or a masked
  prompt, never from an argument.
  - `xtra-set` keeps the X-tra passphrase in the vault, so `vault.unlock` releases it to a daemon that has no other
    source (the Windows tray starts daemons without a console or environment). `xtra-clear` drops it.
  - `internxt-password-set` keeps an Internxt profile's password in the vault (opt-in), for the daemon's own re-sign-in
    on an expired session. No command reads it back. `internxt-password-clear` turns the option off again.
  - `forget` drops the vault entry of a profile that is no longer configured.
  - `vault seal-credentials on` seals the Internxt mnemonic (`credentials.json`) and the OneDrive and Dropbox OAuth
    tokens (`token.json`, and a pending `auth begin` sign-in) under a key kept in the vault, inside the platform
    protection (DPAPI, Keychain, Secret Service; on Linux the OneDrive and Dropbox tokens have no platform store, so the
    seal sits over the owner-only file). **Recovery:** if the vault is lost (deleted, or its
    passphrase forgotten), the sealed files cannot be read from disk any more. Sign in again with
    `unidrive auth login` (Internxt, e-mail + password) or `unidrive auth begin` / `auth complete` (OneDrive, Dropbox);
    the sign-in replaces the sealed file. `unidrive auth` without a subcommand reads the stored token first and refuses
    a sealed one while the vault is locked.
    `unidrive auth login`, `auth begin` and `auth complete` never read the vault and ask for no vault passphrase, so
    every sign-in through them (a first one or a re-login) writes the file with the platform protection only; it is sealed the next time a process that has unlocked
    the vault reads it (with sealing on, after `vault init` and `vault seal-credentials on`).
  - The provider secret fields kept in `config.toml` (S3 `access_key_id` / `secret_access_key`, `password`,
    `client_id`, `client_secret`) are sealed the same way, field by field, at the daemon's next start or
    `vault.unlock` (on Linux over the plain field, which has no platform store). While the vault is locked such a
    profile stays locked and its fields are refused as sealed. If the vault is lost, put those fields into
    `config.toml` again as plaintext (they are protected again on the next start). `vault encrypt` moves them out of
    `config.toml` into `vault.enc` altogether.
- `sweep` — detect and rehydrate zero-byte stub files (`--null-bytes` scan; `--dry-run` and `--rehydrate` modify it)

### Client-side encryption (X-tra)
- `xtra` — the key of profiles with `xtra_encryption = true`: `init`, `export-key`, `import-key`, `change-passphrase`,
  and the same for the other content algorithms (`init-age`, `export-age`, `import-age`, `change-passphrase-age`,
  `init-tink`, `change-passphrase-tink`). `xtra_algorithm` picks `aes-gcm` (the default), `age-v1` (files an offline
  `age` tool decrypts from the identity alone) or `tink-aes-gcm`. File content is encrypted before it reaches the
  provider; names, sizes and the folder tree are not. See [`config.example.toml`](docs/config-schema/config.example.toml).

### Front-ends and `--json`
A command run with `--json` never prompts and always answers with one JSON object, a refusal included, so a front-end
such as the Windows client can read it. While a daemon runs, `status --pending` and `quota` take their answer from it
over IPC (`daemon.status`).

### Tracking-set engine (experimental, frozen)
- `ts` — `sync`, `claim`, `unclaim`, `status` (see `core/app/sync-tracking/README.md`)

## Provider behaviour

- A broken OneDrive or Internxt download resumes from the bytes already staged (a `Range` request), and the result is
  checked whole against the provider's hash. A changed remote version starts over from zero.
- OneDrive folds some names onto others (case, Unicode NFKC, zero-width characters, emoji variants). A name that would
  land on an existing sibling is refused, with the sibling named and the row blocked, instead of being stored as a
  renamed `name 1` copy.
- S3 keys with a `.` or `..` path segment are left out of listings (with a warning) and are never signed. A folder that holds only such keys is not listed.
- On a mount the NFC spelling of a name wins: a client that writes the NFC spelling of a file it first used in NFD form
  replaces the earlier twin, and once the NFC upload lands the NFD object is deleted (to the trash where the provider
  has one). A twin holding bytes the cloud has not seen is not replaced; the write is refused with `unsynced_bytes`.

If OneDrive Personal refused large uploads with “Invalid request” before the engine was updated, an unchanged file
can still be deferred on restart for up to six hours. After updating, use the mount client's upload-queue retry to
resubmit the cached file without changing it. A new client `hydration.open_write` submission clears the old refusal
for that file. The transfer-history retry keeps the refusal in force until it expires.

## Pipeline log measurements

`python scripts/dev/file-events.py run.log` summarizes captured per-file DEBUG events by phase; add `--per-file` for paths.
It keeps download bytes, cache serves and eviction bytes separate, and reports summed operation durations and recovered
engine retries. See [the event format and measurement limits](docs/logging.md#summarizing-a-captured-run).

## Key source files

- `ProviderFactory.kt` — SPI provider registration framework
- `SyncEngine.kt` — the sync engine behind `sync` and the daemon
- `TrackingEngine.kt` — the frozen tracking-set engine, reachable only as `unidrive ts ...`
- `HydrationImpl.kt` — physical payload de/reconstruction lifecycle
- `MountEngine.kt` — mount operations (cache, hydration verbs, mirror hand-off) since the engine split

## Project state

What is open is in the [issue tracker](https://github.com/gkrost/unidrive-cli/issues): priority labels `P1`–`P3`, kind labels `bug`, `enhancement`, `security`, `documentation` and `epic`, and `ready for development` / `post-MVP` for the state. Releases are on the [releases page](https://github.com/gkrost/unidrive-cli/releases) (the latest is v0.0.3, a preview). [`CLOSED.md`](CLOSED.md) is the ledger of what is done, and [`BOOSTERS.md`](BOOSTERS.md) maps the provider APIs the engine uses and misses.

## Issue references

Comments and documents cite `archive#N`. That is issue or pull request N of the engine's issue tracker before this repository
was created from the release tree; that tracker is archived and private. Issues still open at the release were filed again here,
each starting with "Moved from archive#N". A plain `#N` in a comment or document written after the release is an issue or pull
request of this repository.

## Contact

Questions about the open-source engine, licensing of contributions and security reports that cannot go through
GitHub's private reporting form: **oss@unidrive.krost.org**. This address is for the open-source project only.
