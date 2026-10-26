# `unidrive`

Multi-platform cloud-sync core. Pure JVM, zero telemetry. Modular SPI for Internxt Drive (zero-knowledge E2EE) and Microsoft OneDrive (Graph API). Runs as a user-space Linux daemon via systemd; platform tiers consume the engine over IPC — [`unidrive-mount-linux`](https://github.com/gkrost/unidrive-mount-linux) (FUSE) and the Windows client (CfAPI; new, changed, renamed and deleted files sync from the mount).

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

JDK 21+ and Linux with `systemd --user` for the packaged daemon. Internxt uses the host's native credential store: Linux requires an unlocked Secret Service and `secret-tool` (`libsecret-tools`), Windows uses user-scoped DPAPI, and macOS uses the user's login Keychain (macOS is not supported or tested in CI yet). Unsupported platforms and unavailable or locked stores fail closed without writing plaintext credentials. OneDrive's token uses the same stores on Windows (DPAPI) and macOS (Keychain), and a token file written earlier is protected the first time it is read; on Linux it stays an owner-only file (`token.json`, mode 600), and so does Dropbox's on every platform. The Gradle wrapper lives in `core/`.

```bash
cd core && ./gradlew :app:cli:shadowJar    # build fat JAR → core/app/cli/build/libs/unidrive-*.jar
```

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
`check` (see `RuntimeModulesTest`), and CI runs the whole gate a second time
with the engine executing on the bundled runtime JDK. Launchers honour
`UNIDRIVE_XMX` (bare size, e.g. `512m`, `2g`; default `2g`) and pin
`-Djdk.net.unixdomain.tmpdir` to the host's temp directory. Launchers and the
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
- `auth` — OAuth/token provisioning: the interactive sign-in, `begin` and `complete` (a sign-in in two steps, for front-ends), `login` (Internxt, without a console) and `check` (`--json`: asks the provider with one call whether the profile's stored sign-in is still accepted, with no daemon, scan, sync or prompt; [spec](docs/dev/specs/cli-auth-check.md)). A sign-in as another user than the one the profile's sync state belongs to is refused (`account_changed`) before anything is stored ([spec](docs/dev/specs/cli-account-name.md))
- `profile` — multi-profile management (`add`, `list`, `remove`)
- `backup` — backup profiles (`add`, `list`)
- `logout` — sign out: remove stored credentials and revoke the token where the provider has a revoke call (Dropbox; for the others only the local credentials go, and the output says so); the sync state is kept (`--delete-state` removes it), and a running daemon performs the sign-out over IPC (`--json` for front-ends)

### Sync
- `sync` — run the sync engine (`SyncEngine`, the engine the MVP ships); the frozen tracking-set engine is reachable only as `ts`
- `refresh` — update `state.db` with remote changes through the running daemon (`--reset` re-enumerates the whole cloud tree)
- `apply` — drain pending transfers left by a `refresh`, without fetching remote changes again
- `status` — show alignment, transfers, exceptions
- `scope` — show or change the sync scope (`sync_path`) of a profile: `show`, `set <paths...>`, `clear`. `set` and `clear` edit config.toml, apply the scope transition to `state.db` (rows outside the new scope stop being tracked; files stay), replace the scoped history with the new scope, and print what the next run does; after `clear` the next bidirectional sync reconciles the whole drive without `--full-tree`. Refused while a daemon or sync holds the profile; `--json` for front-ends
- `conflicts` — show recent conflicts; restore or clear conflict backups (`list`, `restore`, `clear`)
- `verify` — report-only audit of the sync root, `state.db` and a live remote listing (exit 0 converged, 1 divergence, 2 could not audit)
- `doctor` — read-only diagnostics: drift, staleness, destructive activity, scope, hydration
- `log` — show recent sync activity

### Daemon & Mount
- `daemon` — per-profile daemon: `run` (foreground until SIGTERM), `status`, `stop`
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
- `relocate` — migrate data between cloud providers
- `vault` — credential vault (`init`, `encrypt`, `decrypt`, `change-passphrase`)
- `sweep` — detect and rehydrate zero-byte stub files (`--null-bytes` scan; `--dry-run` and `--rehydrate` modify it)

### Tracking-set engine (experimental, frozen)
- `ts` — `sync`, `claim`, `unclaim`, `status` (see `core/app/sync-tracking/README.md`)

## Key source files

- `ProviderFactory.kt` — SPI provider registration framework
- `SyncEngine.kt` — the sync engine behind `sync` and the daemon
- `TrackingEngine.kt` — the frozen tracking-set engine, reachable only as `unidrive ts ...`
- `HydrationImpl.kt` — physical payload de/reconstruction lifecycle
- `MountEngine.kt` — mount operations (cache, hydration verbs, mirror hand-off) since the engine split

## Issue references

Comments and documents cite `archive#N`. That is issue or pull request N of the engine's issue tracker before this repository
was created from the release tree; that tracker is archived and private. Issues still open at the release were filed again here,
each starting with "Moved from archive#N". A plain `#N` in a comment or document written after the release is an issue or pull
request of this repository.

## Contact

Questions about the open-source engine, licensing of contributions and security reports that cannot go through
GitHub's private reporting form: **oss@unidrive.krost.org**. This address is for the open-source project only.
