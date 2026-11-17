package org.krost.unidrive.cli

import org.krost.unidrive.ProviderRegistry
import org.krost.unidrive.sync.ConfigSecrets
import org.krost.unidrive.sync.ProfileInfo
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.Vault
import org.krost.unidrive.io.VAULT_PASSPHRASE_ENV
import java.nio.file.Files
import java.nio.file.Path

/**
 * The daemon-start vault resolution and the vault seam behind the `vault.unlock` / `vault.lock`
 * verbs (issue#796), as extensions of [Main] so the command layer keeps calling them on the
 * instance: the chain runs once per start ([Main.resolveDaemonVaultStart]), a locked daemon's
 * re-resolution throws [VaultLockedException] instead of exiting, and [Main.daemonVault] carries
 * the gate the lifecycle serves. The passphrase-chain members live in PassphraseChain.kt; the
 * vault data and the locked flag stay on [Main] (`vaultDataCache`, `daemonVaultLocked`) because the
 * CLI commands read them through [Main.loadVaultData] and [Main.mergeVaultCreds].
 */

/** The vault passphrase through the chain (env → systemd credential → unlock helper → console, issue#796). */
internal fun Main.vaultPassphraseResolution(): PassphraseResolution =
    resolvePassphrase(
        secretKind = "vault",
        envName = VAULT_PASSPHRASE_ENV,
        credentialName = "unidrive-vault-pass",
        configDir = baseConfigDir,
        helperConfig = rawGeneral().unlock_helper,
        prompt = "Vault passphrase: ",
    )

/**
 * One vault resolution for a daemon start (issue#796, decision D6): the chain runs here, once,
 * before the credential health reads the vault. A headless run with `vault.enc` and no
 * answering source starts locked when the profile reads vaulted fields — or exits 78 when
 * `[general] require_vault` keeps the systemd semantics — and runs normally when the profile
 * needs no vaulted fields at all.
 */
internal fun Main.resolveDaemonVaultStart(
    profile: ProfileInfo,
    general: org.krost.unidrive.sync.RawGeneral,
): DaemonVaultStart {
    val vault = Vault(baseConfigDir.resolve("vault.enc"))
    if (!vault.exists()) {
        return DaemonVaultStart.Ready(emptyMap<String, Map<String, String>>().also { vaultDataCache = it })
    }
    return when (val resolution = vaultPassphraseResolutionFor(general)) {
        is PassphraseResolution.Secret ->
            DaemonVaultStart.Ready(readVaultForDaemon(vault, resolution.chars))
        is PassphraseResolution.Refused ->
            if (resolution.consoleWasPresent) {
                System.err.println("Error: could not read passphrase.")
                kotlin.system.exitProcess(1)
            } else if (general.require_vault == true) {
                // The operator keeps the shipped systemd semantics: a start without a
                // passphrase is a clean-stop refusal (exit 78), not a locked daemon.
                System.err.println("Error: vault.enc exists but no passphrase is available (${resolution.detail}).")
                System.err.println("[general] require_vault is set: refusing to run locked (exit 78).")
                kotlin.system.exitProcess(MODE_REFUSAL_EXIT)
            } else if (!profileNeedsVaultedFields(profile)) {
                // Nothing of this profile comes out of the vault: it runs normally while the
                // vault itself waits for its unlock (the fields of other profiles stay sealed).
                DaemonVaultStart.Ready(emptyMap<String, Map<String, String>>().also { vaultDataCache = it })
            } else {
                daemonVaultLocked = true
                DaemonVaultStart.Locked(resolution.detail)
            }
    }
}

/** The chain resolution with the start's own `[general]` (the same as [vaultPassphraseResolution], parameterised). */
private fun Main.vaultPassphraseResolutionFor(general: org.krost.unidrive.sync.RawGeneral): PassphraseResolution =
    resolvePassphrase(
        secretKind = "vault",
        envName = VAULT_PASSPHRASE_ENV,
        credentialName = "unidrive-vault-pass",
        configDir = baseConfigDir,
        helperConfig = general.unlock_helper,
        prompt = "Vault passphrase: ",
    )

/** The vault read of a daemon start: the same verdicts as [loadVaultData]'s, with the data returned instead of cached. */
private fun Main.readVaultForDaemon(
    vault: Vault,
    passphrase: CharArray,
): Map<String, Map<String, String>> =
    try {
        vault.read(passphrase).also { vaultDataCache = it }
    } catch (_: javax.crypto.AEADBadTagException) {
        System.err.println("Error: Wrong vault passphrase.")
        kotlin.system.exitProcess(1)
    } catch (e: java.security.GeneralSecurityException) {
        System.err.println("Error: Vault decryption failed: ${e.message}")
        kotlin.system.exitProcess(1)
    }

/**
 * The `vault.unlock` attempt behind the IPC verb (issue#796): the passphrase is checked against
 * the vault file; on success the data is cached and the daemon's locked state ends.
 */
internal fun Main.daemonUnlockVault(passphrase: CharArray): VaultUnlockOutcome {
    val vault = Vault(baseConfigDir.resolve("vault.enc"))
    return try {
        VaultUnlockOutcome.Ok(vault.read(passphrase).also { data ->
            vaultDataCache = data
            daemonVaultLocked = false
        })
    } catch (_: javax.crypto.AEADBadTagException) {
        VaultUnlockOutcome.BadPassphrase
    } catch (e: java.security.GeneralSecurityException) {
        VaultUnlockOutcome.Failed(e.message ?: "vault read failed")
    }
}

/** The `vault.lock` side (issue#796): the data is gone from memory until the next unlock. */
internal fun Main.dropVaultData() {
    vaultDataCache = null
    daemonVaultLocked = true
}

/**
 * The vault seam a daemon runs with (issue#796): non-null when `vault.enc` exists — the verbs
 * are registered and the `vault-unlock:v1` capability advertised — with the gate starting
 * locked exactly on a [DaemonVaultStart.Locked] start.
 */
internal fun Main.daemonVault(start: DaemonVaultStart): DaemonVault? {
    if (!Files.exists(baseConfigDir.resolve("vault.enc"))) return null
    val gate =
        when (start) {
            is DaemonVaultStart.Ready -> VaultGate.unlocked()
            is DaemonVaultStart.Locked -> VaultGate.lockedStart(start.reason)
        }
    return DaemonVault(gate, unlockAttempt = ::daemonUnlockVault, dropData = ::dropVaultData)
}

/**
 * Whether [profile]'s provider type has required vault fields that neither config.toml nor the
 * environment provides — the fields only the vault can supply once it is unlocked (issue#796).
 * The daemon of a profile that needs nothing from the vault runs normally while locked.
 *
 * The vault's field set is [Vault.SENSITIVE_FIELDS], not the prompt's presentation: an unmasked
 * required prompt (S3's `access_key_id`) is still a field the vault stores and can supply
 * (issue#796 review). The environment mappings are read in their declared direction — env var →
 * config field — so a credential the provider takes from the environment counts as available.
 */
internal fun Main.profileNeedsVaultedFields(profile: ProfileInfo): Boolean {
    val factory = ProviderRegistry.get(profile.type) ?: return false
    val vaultFields = factory.credentialPrompts().filter { it.required && it.key in Vault.SENSITIVE_FIELDS }
    if (vaultFields.isEmpty()) return false
    val rp =
        runCatching {
            ConfigSecrets.decode(profile.rawProvider, profile.name, baseConfigDir.resolve("config.toml"))
        }.getOrNull()
    return vaultFields.any { prompt ->
        val inConfig = rawProviderSecret(rp, prompt.key)?.takeIf { it.isNotBlank() } != null
        val inEnv = factory.envVarMappings().any { (envVar, field) ->
            field == prompt.key && !LifecycleHooks.env(envVar).isNullOrBlank()
        }
        !inConfig && !inEnv
    }
}

/** The `[general]` section of config.toml, read tolerantly: a missing or broken file is the defaults. */
internal fun Main.rawGeneral(): org.krost.unidrive.sync.RawGeneral {
    val configFile = baseConfigDir.resolve("config.toml")
    if (!Files.exists(configFile)) return org.krost.unidrive.sync.RawGeneral()
    return runCatching {
        SyncConfig.parseRaw(Files.readString(configFile), configFile.toString()).general
    }.getOrDefault(org.krost.unidrive.sync.RawGeneral())
}
