# Review: `windows-hello-credential-protection.md`

Reviewed 2026-11-16 against `unidrive-cli` working tree (audit file untracked) and `unidrive-windows` @ `eae85fe8`.

## Rating: 7 / 10

| Dimension | Score | Note |
|---|---|---|
| Research depth (APIs, prior art) | 9 | Strong: names the `UserConsentVerifier` trap, DPAPI-NG has no Hello protector, user-not-app scoping, Bitwarden before/after. |
| Accuracy of code claims | 7 | Most claims verified (below). One imprecise claim (`ChildProcess`), one probably wrong crypto claim (PSS). |
| Completeness of the asset inventory | 5 | Misses plaintext `config.toml` secrets and the X-tra passphrase, the two things most exposed on Windows today. |
| Fit of the proposed design to the real process topology | 5 | Option A puts the Hello prompt in a process that has no window and in a library that cannot call WinRT. |
| Actionability | 8 | Clear options, residual risks and owner questions; easy to turn into issues once the gaps are closed. |

## Claims verified

| Claim | Result | Evidence |
|---|---|---|
| `CredentialStore` at `CredentialStore.kt:32`, protector on save | OK | class at L32, `protector?.protect(id, …)` at L149 |
| DPAPI via FFM `CryptProtectData`, `unidrive-dpapi-v1:` prefix, SHA-256 entropy, scrubbing | OK | `WindowsDpapiCredentialProtector.kt` L18-46, L151, L154, L167 |
| Entropy = SHA-256 of canonical path | OK | id from `toRealPath()` (`CredentialStore.kt` L174-183) |
| Vault: salt‖iv‖AES-256-GCM, PBKDF2-SHA256 600k, five fields | OK | `Vault.kt` L23-52 |
| `loadVaultData` at `Main.kt:261`, env → systemd cred → console, exit 78 | OK | `Main.kt` L261-287 |
| Client never sets `UNIDRIVE_VAULT_PASS`; `EngineLaunch` only mentions it for JFR | OK | `EngineLaunch.cs` L61-62, L155 |
| Client stores no secrets, no `ProtectedData` in `src/` | OK | no hit in `*.cs` (only in `bin/` build output) |
| Sign-in via `auth login --email-stdin --password-stdin` | OK | `EngineAuth.cs` L269 |
| TFM `net10.0-windows10.0.22621.0` | Partly | true for App/Cli/Shell/…; **`Unidrive.Ipc` is plain `net10.0`** (`Directory.Build.props` L3), and `EngineLaunch.cs` lives there |
| "`ChildProcess` already strips it from grandchildren" | Imprecise | `childProcessBuilder` is used only for third-party helpers (browser, `secret-tool`, `notify-send`, `fsutil`). First-party spawns (`DetachedSpawn.kt` L26, `MountCommand.kt` L215) inherit the passphrase on purpose (`ChildProcess.kt` L3-7). |
| `KeyCredential` signs RSASSA-PSS/SHA-256 | Likely wrong, verify | Microsoft's Hello sample verifies with `RSASignaturePadding.Pkcs1`. See M2. |
| External sources (Bitwarden Heist, MS Q&A 5912130, Notin) | Not verified | Not fetched in this review |

## MUST (fix before this becomes an issue or a design)

**M1. Put the Hello prompt in the right process.** Option A says the client unlocks "at daemon spawn (`EngineHost`/`EngineLaunch`)". But:
- `EngineHost`/`EngineSupervisor` run in `unidrive-win run` (`Unidrive.Cli`, console Exe). The packaged tray starts that host *without a console* (`Program.cs` windows#607). A windowless background process is a bad place for a Hello prompt: `RequestSignForWindowAsync` needs an HWND, and a prompt that is not in the foreground gets missed or blocked by focus rules.
- `EngineLaunch.cs` is in `Unidrive.Ipc` (`net10.0`, no Windows TFM), so it cannot call `Windows.Security.Credentials` at all.
- So the design has to say which process prompts (the tray, `unidrive-tray`), how the unwrapped secret gets to `unidrive-win run` (env of the host? a pipe?), and that it then sits in the host's memory and environment too, not only the daemon's.

**M2. Fix the key-derivation premise.** "Derive the wrap key via HKDF from the signature over a fixed challenge" only works if the signature is **deterministic**. PKCS#1 v1.5 is; RSASSA-PSS (which §3 claims) uses a random salt and would give a new key every time, so the vault could never be unwrapped again. Check which padding `RequestSignAsync` really uses, state the determinism requirement outright, and add a test that signs twice and compares. If the platform guarantee is weak, prefer `RequestDeriveSharedSecretAsync` (see S1).

**M3. Complete the asset inventory (§1).** Two exposed secrets are missing:
- **Plaintext `config.toml` fields.** Any of the five sensitive fields not moved to the vault is read straight from `config.toml` (`Main.kt` L323: `rp.secret_access_key ?: vaultCreds[…]`). The sync module applies no `CredentialProtector`. Because the vault is unusable on Windows, S3/WebDAV/SFTP secrets there are probably *plaintext*, which is weaker than the DPAPI files the threat model analyses. This changes the priority: the first Windows gain is "secrets get any protection at all", before "DPAPI → Hello".
- **X-tra passphrase** (`UNIDRIVE_XTRA_PASS` / `unidrive-xtra-pass`, `Main.kt` L439-448). It is resolved like the vault passphrase and has the same Windows gap. Either bring it into scope or say why it is out.

**M4. State the real security gain without overselling it.** On Windows (as on Linux with `/proc/<pid>/environ`), the environment of a running process can be read by any process of the same user. With Option A the passphrase sits in the env of the daemon (and probably `unidrive-win run`) for the whole session. Against same-user malware, the gain over DPAPI is limited to **times when no daemon is running** and to **offline/backup-key attacks**. §2 says "Same-user malware cannot unwrap the secret without … a Hello prompt". That is true for the at-rest blob, but the design hands the plaintext to a readable location right after unlock. Say this in §2 and §6.

**M5. Clear up the Option A key hierarchy.** "Generate a random unlock key, derive a wrap key from a Hello signature … store `{wrapped passphrase, …}`" says nothing about what the random unlock key is for. Pick one: (a) the Hello key wraps the user's passphrase, or (b) the Hello key wraps a random key that in turn wraps the passphrase (useful for re-enrollment without re-wrapping). Then write down the on-disk format, as `Vault.kt` does.

## SHOULD

**S1. Check API availability against the TFM.** The client targets SDK `10.0.22621.0`. I believe `RequestDeriveSharedSecretAsync`, `RequestSignForWindowAsync` and `KeyCredentialCacheConfiguration` are newer than 22621. If so, they need a TFM bump, a runtime `ApiInformation.IsMethodPresent` gate, and a minimum-OS statement. "Directly callable … no extra package" (§3) may not hold for the APIs the design depends on.

**S2. Multi-account prompt cadence.** One `vault.enc` per config base dir, but the tray starts one host and one daemon per account at sign-in (windows#511), and `EngineSupervisor` restarts each one. Spell out whether N accounts mean N prompts, or one unlock cached in the tray, and what the cached copy's lifetime and storage are. This is the deciding factor for owner question 4.

**S3. Name the environments where Hello is unavailable:** RDP sessions (Hello is usually not offered remotely), Windows Sandbox/VMs without a vTPM, and Hello for Business policy that turns off convenience PINs. Each should map to the fallback path in §6.

**S4. Fix the `ChildProcess` sentence** and check the first-party spawns. `MountCommand.kt` L215 `ProcessBuilder(argv).inheritIO()` passes the full environment on; confirm that `argv` is always first-party. Side finding outside this doc's scope: `childProcessBuilder` strips only `UNIDRIVE_VAULT_PASS`, **not `UNIDRIVE_XTRA_PASS`**, so the X-tra passphrase reaches the browser and keyring helpers (the same exposure as issue#83 E). This deserves its own issue.

**S5. Add metadata and pin references:** date, author, status (research / proposed / decided), and the commit SHAs of both repos. Line-number citations (`Main.kt:261`, `Vault.kt:23`) will go stale otherwise.

**S6. Testability.** Say how Option A is tested without real biometrics: an `IHelloKeyCredential` seam with a fake, plus the M2 determinism test, plus a witness-mismatch test (credential re-created gives a refusal, not trust).

## COULD

**C1.** Have the client generate a random, high-entropy vault passphrase when the feature is turned on, and show it once as a recovery code. This removes weak human passphrases (min. length is only 8) from the Windows path.

**C2.** Record the protection level (`TPM` vs software NGC) next to the wrapped blob and show it in settings. That makes the TPM-less policy (§6, Q3) visible instead of only logged.

**C3.** Add a small diagram of the processes (tray → `unidrive-win run` → JVM daemon → helpers) and mark where the secret lives at each step. It would have made M1 and M4 obvious.

**C4.** §2: "decryption requires the user's logon password" is a simplification. With Hello-PIN-only or Microsoft-account sign-in, the DPAPI master key is unlocked by other credential paths. One sentence to qualify it is enough.

**C5.** Add an owner question: should Layer-1 tokens on Windows follow later (Option C) once the vault path exists, or is that explicitly never?
