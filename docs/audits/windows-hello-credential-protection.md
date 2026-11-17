# Windows Hello for unidrive credential protection — research

Question: how can unidrive-windows use Windows Hello (fingerprint / face / PIN) to better secure unidrive's
account credentials and credential storage? This is a research note, not a decision. It maps what exists
today, what Hello actually guarantees (and what it does not), how comparable products use it, and which
integration shape fits the engine/client split.

## 1. What is protected today, and how

Two engine-owned secret layers; the Windows client holds no secrets of its own.

**Layer 1 — per-provider credential files.** Each profile dir holds owner-only files (`token.json`,
`credentials.json`, device-flow files) written by the engine's `CredentialStore`
(`core/app/core/src/main/kotlin/org/krost/unidrive/auth/CredentialStore.kt:32`). On Windows every save
runs through `WindowsDpapiCredentialProtector`
(`core/app/core/src/main/kotlin/org/krost/unidrive/auth/WindowsDpapiCredentialProtector.kt`): FFM downcalls
to `crypt32!CryptProtectData`, user-scoped, ciphertext inline under a `unidrive-dpapi-v1:` marker, entropy =
SHA-256 of the canonical file path (integrity binding, not a secret), memory scrubbed after use. Provider
tokens, once the daemon has them, land DPAPI-protected. No user interaction ever happens.

**Layer 2 — the credential vault.** Optional `<configBaseDir>/vault.enc`
(`core/app/sync/src/main/kotlin/org/krost/unidrive/sync/Vault.kt:23`): `salt ‖ iv ‖ AES-256-GCM`, key derived
with PBKDF2-HMAC-SHA256 (600k iterations). Holds the five sensitive config fields
(`access_key_id`, `secret_access_key`, `password`, `client_id`, `client_secret`). The passphrase reaches the
daemon through `Main.loadVaultData` (`core/app/cli/src/main/kotlin/org/krost/unidrive/cli/Main.kt:261`):
env `UNIDRIVE_VAULT_PASS` → systemd `CREDENTIALS_DIRECTORY/unidrive-vault-pass` → console prompt; a headless
daemon with none of the three refuses to start (exit 78). There is no IPC verb to unlock the vault.

**On Windows specifically:** nothing sets `UNIDRIVE_VAULT_PASS`. The client never does
(`src/Unidrive.Ipc/EngineLaunch.cs` only *mentions* the variable, to keep it out of JFR via
`redact-key=+*vault*`), and the daemon is launched as a child of the host
(`src/Unidrive.Cli/EngineHost.cs`, restarted by `EngineSupervisor`). Consequence: **the vault is effectively
unusable unattended on Windows today** — any `daemon run` whose environment lacks the passphrase exits 78.
A Hello-unlock in the client is not only extra protection; it is also the missing plumbing that would make
the vault usable there at all.

**Client side.** The client stores no secrets (settings/pause stores are plain files, no `ProtectedData`
usage anywhere in `src/`). Sign-in goes through one-shot engine commands
(`src/Unidrive.App/EngineAuth.cs`: `auth login --email-stdin --password-stdin`), so the provider password
transits the client transiently at sign-in and is persisted engine-side into Layer 1.

## 2. Threat model — what DPAPI gives, what Hello adds

DPAPI user-scope guarantees: other local users cannot decrypt the blobs; decryption requires the user's
logon password. What it does **not** do:

- **Same-user malware reads every blob with no prompt.** Any process in the user session that can read
  `token.json` gets the plaintext tokens; no consent, no key material needed. (Demonstrated in the
  Bitwarden-Heist write-up: a Go program listing the Credential Manager returned the "biometric" key
  plaintext — "no PIN or fingerprint prompt required".)
- **Offline theft of disk + knowledge of the logon password defeats DPAPI.** Master-key files under
  `%APPDATA%\Microsoft\Protect` + the password are enough; a domain admin can additionally decrypt
  everyone's blobs via the domain DPAPI backup key, without touching the device, and password changes do
  not revoke that.

What a Hello-bound key (TPM-backed, non-extractable, consent-gated) adds:

- Same-user malware cannot unwrap the secret without triggering a visible, user-approved Hello prompt, and
  cannot extract the wrapping key at all (TPM refuses export; hammering is rate-limited).
- Offline disk theft (incl. domain-backup-key style attacks) is useless without the device + a live user.
- The gate is enforced cryptographically, not by an `if` in our own UI (see the `UserConsentVerifier` trap
  below).

What Hello does **not** add: protection after unlock (the daemon still holds the passphrase/tokens in heap —
the OOM-heap-dump note in `dist/launcher/jvm-flags.txt` stays true), admin-level malware in the session,
biometric spoofing beyond Windows' own liveness, and any protection for files the daemon writes *after*
unlock.

## 3. The APIs, precisely

- `KeyCredentialManager.RequestCreateAsync(name, option)` / `OpenAsync(name)` — creates/opens a per-user
  (in practice, also per-app) **RSA-2048** key; the private key never leaves the TPM/NGC store; only
  `RequestSignAsync`, `RequestDeriveSharedSecretAsync`, `RetrievePublicKey`, `GetAttestationAsync` are
  possible with it. `OpenAsync` is silent; only create/sign/derive prompt. The docs page says signatures
  are RSASSA-PSS/SHA-256 — the spike (§8) proved they are actually **PKCS#1 v1.5/SHA-256** (JDK JCA
  `SHA256withRSA` verifies), which makes them deterministic *by specification*.
- `KeyCredential.RequestSignAsync(challenge)` — triggers a system Hello prompt and signs the challenge.
  This is the primitive others build on: derive the wrap key via HKDF from the signature over a fixed
  challenge, so the secret can only be unwrapped after an approved Hello gesture.
- `KeyCredential.RequestDeriveSharedSecretAsync` — key agreement with the credential key. **Dead on real
  hardware:** the WindowId overload exists in recent projections but fails at runtime with `0x80090029`
  (`NTE_NOT_SUPPORTED`) because NGC keys are RSA sign-only, no ECDH; the legacy IBuffer overload does not
  exist at all. Drop this construction from the design space.
- `UserConsentVerifier.RequestVerificationAsync(prompt)` — **presence check only.** It is a client-side
  boolean the calling app then acts on; malware can patch the app, and the secret it guards is often
  separately reachable. The Clement Notin write-up flags exactly this pattern as the common broken design
  ("if you see a reference to UserConsentVerifier… that is a red flag"). Do not gate secrets on it alone.
- `KeyCredentialProtectionLevel` — creation can insist on `ProtectedByTPMOnly` or accept
  `ProtectedByTPMIfPresent` (software fallback on TPM-less machines, where the key **is** extractable in
  principle). A design must decide its TPM-less policy, not silently accept the fallback.
- Consent caching: `KeyCredentialCacheConfiguration` + the `WindowId`-scoped overloads
  (`RequestSignForWindowAsync`) control how often the prompt reappears — the lever against prompt fatigue.
- **DPAPI-NG is not a shortcut:** `NCryptCreateProtectionDescriptor` rules are SID/WEBCREDENTIALS/
  certificate/named only; there is no Hello/Passport protector rule. Binding data to Hello means
  `KeyCredentialManager` (or the Passport KSP via NCrypt) directly.
- Scoping reality: for unpackaged Win32 apps the boundary is the **user**, not the app — any same-user
  process that knows the credential name can `OpenAsync` and *request* operations; the user must still
  approve each prompt, and the key still cannot be extracted. Full-trust MSIX behaves the same (verified
  by an independent Q&A experiment); claims of per-package isolation were never reproduced and should not
  be relied on. Consequence: the credential name is not a secret, and the anti-exfiltration property is the
  point — not process isolation.
- .NET access: the client targets `net10.0-windows10.0.22621.0` (`Directory.Build.props`, per-project
  TFMs), so the WinRT projections are available with no extra package; `Windows.Security.Credentials` is
  directly callable from the WPF app.

## 4. How the reference products do it

- **Bitwarden (before):** encrypted copy of the derived key in Windows Credential Manager (`CredWriteW`)
  = DPAPI only. The "Windows Hello" label was cosmetic. Same-user malware read it without a prompt; a
  remote attacker with domain admin pulled `data.json` + the DPAPI blobs + the domain backup key over SMB
  and decrypted the vault offline. Microsoft MSRC ruled the DPAPI behavior "as intended".
- **Bitwarden (after):** two files (`…_user_biometric`, `…_user_biometric_witness`) — the payload wrapped
  by an actual `KeyCredentialManager` credential, the witness tying the wrapped blob to the exact
  credential/account so a swapped or re-created Hello credential is detected instead of trusted. Users
  must still enter the master password once per app start by default. Lesson: keep the passphrase as the
  recovery factor; treat Hello as an accelerator, not the sole key.
- **KeePassWinHello / WinHelloUnlock:** wrap the composite master key with a key derived from a Hello
  signature; store the wrapped blob in Credential Manager optionally; explicit security note to users:
  *never approve a Hello prompt you did not initiate* (a same-user process can trigger prompts — confused
  deputy), and a validity window after which the master password is required again.

## 5. Fit for unidrive — three shapes

The engine-side hard rule decides the split: no platform APIs or user interaction in engine code. A JVM
process cannot call WinRT anyway (a native bridge would be a new platform surface). So Hello lives in the
client; the engine learns nothing new.

**Option A — client-held, Hello-wrapped vault passphrase, injected via the existing env channel
(recommended first step).**
The client, on enabling the feature: generate a random unlock key, derive a wrap key from a Hello
signature over a fixed challenge (HKDF), store `{wrapped passphrase, credential name, witness}` in the
engine profile dir under a filename added to `StoragePermissions.CREDENTIAL_FILES` (reuses the existing
owner-only/SDDL machinery and logout cleanup), and keep the passphrase itself as the recovery factor. The
enrollment-time determinism self-check (two signs, compare) is **required** — it guards the wrap on
unknown hardware. At daemon spawn (`EngineHost`/`EngineLaunch`) the client Hello-unlocks, unwraps, and
sets `UNIDRIVE_VAULT_PASS` in the child environment; `ChildProcess` already strips it from grandchildren
and JFR is redacted. Engine changes: small, not zero — `vault init/encrypt/change-passphrase` are
console-only (`System.console().readPassword`), so either the owner runs `vault init` once in a console or
the engine grows a stdin variant (the `auth login --password-stdin` pattern) for client-driven setup and
re-keying. This also fixes the "vault unusable on Windows" gap, and `Main.loadVaultData`'s exit-78
behavior becomes the correct fail-closed backstop when Hello is declined or unavailable.
Sub-decisions: wrap key = signature-derived (settled — §8; derive is dead); TPM-less policy (refuse vs
warn — no protection-level API exists, so the extended `RequestCreateAsync` overload's `algorithm`
parameter is the lever); witness file following the Bitwarden shape; `DeleteAsync` on feature-off;
re-enrollment flow when the Hello credential is suspended/re-created (PIN resets, firmware updates) —
recovered via the passphrase, which argues for keeping `vault change-passphrase` semantics untouched.

**Option B — a `vault.unlock` IPC verb (optional second step, engine change).**
Today the passphrase can only ride the environment at spawn. If the daemon should be able to *acquire* it
at runtime — e.g. after an autospawn from a CLI command whose environment lacks it — add a WRITE/ADMIN
verb in `DaemonRuntime`'s verb registry + `IpcAuth` classification, with corpus entries in
`core/app/cli/src/test/resources/ipc-contract/` (`IpcContractCorpusTest`), the client answering via Hello.
Without it, Option A already covers the client-owned lifecycle; the verb only widens who may unlock.

**Option C — engine-side Hello protector for Layer 1 (rejected for now).**
A `WindowsHelloCredentialProtector` next to `WindowsDpapiCredentialProtector` cannot reach WinRT from the
JVM; delegating the unwrap to the client over IPC makes every token read a UI-adjacent round trip and drags
platform interaction into the engine. The DPAPI risk it would mitigate (same-user malware reading
`token.json` silently) is better mitigated once vaulted fields live behind the Option-A key, and provider
tokens stay DPAPI until there is a concrete need.

**Option D — daemon-verified Hello proofs (user-presence auth at the IPC layer).**
A different question than secret storage: prove *which human* consented to a sensitive request, not just
*which process* sent it. The client signs a **daemon-supplied nonce** and the daemon verifies against a
Hello public key it recorded itself at enrollment (recording prevents key swap); sensitive verbs carry
`{nonce, signature}` and are verified **fail-closed**. Engine surface is platform-neutral — RSA verify is
plain JCA (`SHA256withRSA`; §8) — and rides the daemon capabilities JSON, so Linux/no-Hello machines keep
working. Composes with the per-start IPC tokens (process ≠ user). A cheaper client-only variant (prompt
before *sending* a sensitive verb) is a UX gate, not a security boundary: same-user malware sends the verb
itself. Engine touch points: JCA verify + anchor file (like the IPC tokens), enrollment nonce flow
(handshake extension or verb in `DaemonRuntime`'s registry + `IpcAuth` classification), proof field on
sensitive verbs, `IpcContractCorpusTest` entries, capabilities advertisement.
Enrollment-trust hardening (the first-anchor race): **first enrollment requires the vault passphrase as a
co-factor** (the recovery factor witnesses the anchor; same-user malware has neither), **rotation requires
a valid proof under the current anchor**, and nonces are single-use, short-TTL, and bound to
`verb ‖ params-hash` so a captured proof cannot be replayed or forwarded to another verb.

## 6. Residual risks to state explicitly in the design

- Post-unlock residency: passphrase/tokens in daemon heap; JFR redaction and the heap-dump note remain the
  mitigations; Hello changes nothing here.
- Confused deputy: any same-user process can request a Hello prompt; user education + the witness check
  (never trust a re-created credential silently) are the countermeasures. The KeePass note — "never approve
  a prompt you did not initiate" — belongs in the client's settings copy.
- TPM-less machines: software-NGC keys are extractable in principle; the design needs an explicit
  ProtectedByTPMIfPresent stance (attestation via `GetAttestationAsync` can record what it got).
- Prompt cadence: daemon spawn at logon + `EngineSupervisor` restarts + sign-ins; the
  `KeyCredentialCacheConfiguration` lever decides how often the user sees Hello. Too few prompts and the
  protection decays toward DPAPI; too many and the feature gets switched off.
- Fallback: `IsSupportedAsync` false (no Hello enrolled) → keep the existing passphrase-only behavior;
  never downgrade silently. Statuses to handle explicitly: `UserPrefersPassword` (user picks password at
  the prompt → passphrase path, not an error) and `SecurityDeviceLocked` (TPM anti-hammering → fail
  closed, passphrase fallback, tell the user why).
- Acceptance item: ~~the spike ran unpackaged~~ — **resolved by the MSIX spike**: user-scoped regardless of
  package identity; the client's packaged build behaves like the unpackaged spike, no identity-change
  re-enrollment needed. What the packaged context adds empirically: the witness check is mandatory (a
  same-user process can rotate the shared credential via `ReplaceExisting`), and `DeleteAsync` from another
  same-user process silently removes the credential (DoS on Hello unlock → passphrase fallback, detected via
  `OpenAsync` `NotFound`).

## 7. Owner questions before any of this is filed as an issue

1. Is the vault actually wanted on Windows (it is currently unusable unattended there), or is Layer-1
   DPAPI considered sufficient and Hello is only desired for the vault?
2. Option A alone, or A + B (the verb)?
3. TPM-less policy: refuse Hello-wrap, or allow with a recorded downgrade?
4. Prompt cadence: once per daemon spawn (logon), or also on every sign-in/auth event?
5. Recovery UX: Bitwarden-style "passphrase once per app start" fallback, or passphrase only as
   re-enrollment recovery?

## 8. Spike results (this machine, both rounds on the #1402 thread)

Rig: `dev/git/spike-hello` outside the repos (csproj + Program.cs + JcaVerify.java, `net10.0-windows10.0.26100.0`,
JDK 27 for the verify side), Windows 10.0.26300 with Hello enrolled. Credentials, blobs and Temp copies
deleted after each round. Full tables live in the two comments on windows#1402.

| Unknown | Result |
| --- | --- |
| Hello available / enrolled | `IsSupportedAsync=true`, `UserConsentVerifier=Available`; `OpenAsync` silent |
| Derive-based wrap | Dead: `0x80090029 NTE_NOT_SUPPORTED` (NGC = RSA sign-only, no ECDH); legacy overload absent |
| Signature scheme | **PKCS#1 v1.5/SHA-256, not PSS** — JDK 27 JCA `SHA256withRSA` verifies; deterministic by spec → sign-as-KDF is stable |
| Wrap/unwrap end-to-end | enroll → HKDF(sig) → AES-256-GCM → blob; unlock OK from separate processes |
| Attestation | `NotSupported` on this machine (no protection-level signal available) |
| `KeyCredentialProtectionLevel` | Not in the real projection; the extended `RequestCreateAsync(..., algorithm, cacheConfiguration, ...)` overload is the likely TPM-policy/cadence lever |
| App scoping | Confirmed user-scoped: a differently-named same-user exe opened the credential by name and unwrapped after approval (`deputy-exit=0`) → confused-deputy countermeasures mandatory |
| Prompt cadence | One prompt per sign operation (no cache configured); `KeyCredentialCacheOption/Configuration` exist for tuning |
| Engine verify (Option D) | Plain JCA `SHA256withRSA` verifies the Hello signature — no PSS parameter guessing |
| Packaged context (MSIX) | **User-scoped, no PFN isolation**: unpackaged processes open, rotate (`ReplaceExisting`), and silently delete packaged-created credentials; no identity-change lockout; packaged signatures verify identically; identity attaches only via the execution alias |

## Sources

- [KeyCredentialManager class](https://learn.microsoft.com/en-us/uwp/api/windows.security.credentials.keycredentialmanager)
- [KeyCredential.RequestDeriveSharedSecretAsync](https://learn.microsoft.com/en-us/uwp/api/windows.security.credentials.keycredential.requestderivesharedsecretasync)
- [KeyCredential class (sign / sign-for-window / attestation)](https://learn.microsoft.com/en-us/uwp/api/windows.security.credentials.keycredential)
- [UserConsentVerifier.RequestVerificationAsync](https://learn.microsoft.com/en-us/uwp/api/windows.security.credentials.ui.userconsentverifier.requestverificationasync)
- [CNG DPAPI (DPAPI-NG protector rules)](https://learn.microsoft.com/en-us/windows/win32/seccng/cng-dpapi)
- [NCryptCreateProtectionDescriptor](https://learn.microsoft.com/en-us/windows/win32/api/ncryptprotect/nf-ncryptprotect-ncryptcreateprotectiondescriptor)
- [Windows Hello – Windows apps docs](https://learn.microsoft.com/en-us/windows/apps/develop/security/windows-hello)
- [RedTeam Pentesting: Bitwarden Heist](https://blog.redteam-pentesting.de/2024/bitwarden-heist)
- [HackerOne: Bitwarden biometric key storage](https://hackerone.com/reports/1874155)
- [Clement Notin: When Windows Hello fails at securely authenticating users](https://clement.notin.org/blog/2019/12/17/When-Windows-Hello-fails-at-securely-authenticating-users-and-protecting-credentials)
- [Microsoft Q&A: What is the security boundary of KeyCredentialManager?](https://learn.microsoft.com/en-us/answers/questions/5912130/what-is-the-security-boundary-of-windows-hello-key)
- [Helge Klein: checking Windows Hello key storage (TPM vs software)](https://helgeklein.com/blog/checking-windows-hello-for-business-whfb-key-storage-tpm-hardware-or-software)
- [KeePassWinHello](https://github.com/sirAndros/KeePassWinHello) · [WinHelloUnlock](https://github.com/angelelz/winhellounlock)
- [C#/WinRT (WinRT from .NET desktop)](https://learn.microsoft.com/en-us/windows/apps/develop/platform/csharp-winrt)
