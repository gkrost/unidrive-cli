package org.krost.unidrive.cli

import org.krost.unidrive.CloudProvider
import org.krost.unidrive.io.XTRA_PASSPHRASE_ENV
import org.krost.unidrive.io.VAULT_PASSPHRASE_ENV
import org.krost.unidrive.sync.RawProvider
import org.krost.unidrive.sync.Vault
import org.krost.unidrive.xtra.AgeV1Strategy
import org.krost.unidrive.xtra.AesGcmStrategy
import org.krost.unidrive.xtra.TinkStreamingStrategy
import org.krost.unidrive.xtra.XtraAgeKeyStore
import org.krost.unidrive.xtra.XtraEncryptedProvider
import org.krost.unidrive.xtra.XtraPlaintextListings
import org.krost.unidrive.xtra.XtraKeyManager
import org.krost.unidrive.xtra.XtraTinkKeyStore
import java.nio.file.Files
import java.nio.file.Path

/**
 * The X-tra layer (client-side encryption, config key `xtra_encryption`): every provider this CLI builds is
 * wrapped when the profile opts in, so every command that moves content — sync, daemon, get, verify, relocate —
 * sees plaintext while the provider stores ciphertext. The strategy owns its key material and loads it lazily on
 * the first content operation ([XtraEncryptedProvider]); commands that only list, authenticate or report never
 * need the passphrase. The algorithm's key file lives beside config.toml (the token path's parent), one per
 * installation — and an age-v1 or tink-aes-gcm profile has only its own key store, never an RSA pair.
 */
internal fun Main.wrapWithXtra(
    rp: RawProvider?,
    provider: CloudProvider,
    tokenPath: Path,
): CloudProvider {
    if (rp?.xtra_encryption != true) return provider
    val name = rp?.xtra_algorithm?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: XtraAlgorithm.AES_GCM.configName
    val algorithm = XtraAlgorithm.named(name) ?: run {
        val known = XtraAlgorithm.entries.joinToString(", ") { it.configName }
        val line = "unknown xtra_algorithm \"$name\" (known: $known)."
        refuse("configuration_error", line, listOf("Error: $line"))
    }
    // The key file comes from the same table `profile set` guards with. It never self-generates on the first
    // content operation (a root secret must not appear silently); it must exist, or the profile refuses to run.
    val keyPath = tokenPath.parent.resolve(algorithm.keyFileName)
    if (!Files.exists(keyPath)) {
        val line = "a profile sets xtra_encryption with xtra_algorithm = \"$name\" but $keyPath does not exist."
        refuse(
            "xtra_key_missing",
            "$line Create it first: ${algorithm.initCommand}",
            listOf("Error: $line", "Create it first: ${algorithm.initCommand}"),
        )
    }
    val strategy =
        when (algorithm) {
            XtraAlgorithm.AES_GCM -> AesGcmStrategy(lazy { XtraKeyManager(keyPath).also { it.load(xtraPassphrase()) } })
            XtraAlgorithm.AGE_V1 -> AgeV1Strategy(lazy { XtraAgeKeyStore(keyPath) }, this::xtraPassphrase)
            XtraAlgorithm.TINK_AES_GCM -> TinkStreamingStrategy(lazy { XtraTinkKeyStore(keyPath) }, this::xtraPassphrase)
        }
    // The listings decorator sits below the cipher, so both halves see the same provider: the listings answer
    // the plaintext size the engines' size contracts hold downloads against, and the cipher seals the content
    // and answers the hash questions with "cannot tell" (the AEAD tag is the integrity check).
    return XtraEncryptedProvider(XtraPlaintextListings(provider, strategy), strategy)
}

/**
 * The X-tra passphrase, resolved through the same chain as the vault's: env, a systemd
 * credential, the unlocked vault's `.xtra` entry (released by `vault.unlock`, issue#796), the unlock
 * helper (secret kind `xtra`), then a console prompt.
 */
internal fun Main.xtraPassphrase(): CharArray = xtraPassphrase(effectivePassphraseSources())

internal fun Main.xtraPassphrase(sources: PassphraseSources): CharArray =
    when (val resolution = resolvePassphrase(
        secretKind = "xtra",
        envName = XTRA_PASSPHRASE_ENV,
        credentialName = "unidrive-xtra-pass",
        configDir = baseConfigDir,
        helperConfig = rawGeneral().unlock_helper,
        prompt = "X-tra passphrase: ",
        sources = PassphraseSources(
            env = sources.env,
            helper = sources.helper,
            consolePresent = sources.consolePresent,
            consoleRead = sources.consoleRead,
            vaultSecret = {
                loadVaultForXtra(sources)
                vaultXtraPassphrase()
            },
        ),
    )) {
        is PassphraseResolution.Secret -> resolution.chars
        is PassphraseResolution.Refused ->
            if (isNonInteractive) {
                throw LifecycleError(
                    "xtra_passphrase_required",
                    "xtra_encryption is on but no passphrase is available (${resolution.detail}); set UNIDRIVE_XTRA_PASS, " +
                        "a systemd credential named unidrive-xtra-pass, `unidrive vault xtra-set`, or [general] unlock_helper",
                )
            } else if (resolution.consoleWasPresent) {
                System.err.println("Error: could not read passphrase.")
                error("no X-tra passphrase available")
            } else if (daemonVaultLocked) {
                error("no X-tra passphrase available: ${resolution.detail}")
            } else {
                System.err.println("Error: xtra_encryption is on but no passphrase is available (${resolution.detail}).")
                System.err.println(
                    "Configure UNIDRIVE_XTRA_PASS, a systemd credential named unidrive-xtra-pass, " +
                        "`unidrive vault xtra-set`, or [general] unlock_helper (docs/dev/specs/unlock-helper-protocol.md).",
                )
                error("no X-tra passphrase available")
            }
    }

/** The reserved vault entry that holds the X-tra passphrase; not a valid profile name, so no profile can collide with it. */
internal const val XTRA_VAULT_ENTRY = Vault.XTRA_ENTRY

/** The field of [XTRA_VAULT_ENTRY] that holds the passphrase. */
internal const val XTRA_VAULT_FIELD = "passphrase"

/** A fresh copy of the X-tra passphrase in the in-memory vault data, or null while the vault is locked, not loaded, or holds none. */
internal fun Main.vaultXtraPassphrase(): CharArray? =
    vaultDataCache?.get(XTRA_VAULT_ENTRY)?.get(XTRA_VAULT_FIELD)?.takeIf { it.isNotEmpty() }?.toCharArray()

/**
 * A one-shot CLI process (not a daemon, whose vault data is already there or deliberately locked) has not read the
 * vault when the X-tra step asks for its `.xtra` entry: read it when the vault's passphrase is available without a
 * prompt (environment, systemd credential, unlock helper), so the CLI and the daemon agree on what "this
 * installation's passphrase" is (issue#796). Any failure leaves the vault step out; the rest of the chain answers.
 */
private fun Main.loadVaultForXtra(sources: PassphraseSources) {
    if (vaultDataCache != null || daemonVaultLocked) return
    val vault = Vault(baseConfigDir.resolve("vault.enc"))
    if (!vault.exists()) return
    val passphrase = vaultPassphraseNoPrompt(sources) ?: return
    runCatching { vault.read(passphrase) }.onSuccess { vaultDataCache = it }
    passphrase.fill('\u0000')
}

private fun Main.vaultPassphraseNoPrompt(sources: PassphraseSources): CharArray? =
    (
        resolvePassphrase(
            secretKind = "vault",
            envName = VAULT_PASSPHRASE_ENV,
            credentialName = "unidrive-vault-pass",
            configDir = baseConfigDir,
            helperConfig = rawGeneral().unlock_helper,
            prompt = "Vault passphrase: ",
            sources = PassphraseSources(env = sources.env, helper = sources.helper, consolePresent = { false }),
        ) as? PassphraseResolution.Secret
    )?.chars

/** Returns the exit status after bringing the optional vaulted copy in step with the re-wrapped key. */
@Suppress("TooGenericExceptionCaught") // any failure after the key was re-wrapped needs the same recovery command
internal fun Main.updateVaultXtraAfterChange(
    newPassphrase: CharArray,
    sources: PassphraseSources = PassphraseSources(),
    vault: Vault = Vault(baseConfigDir.resolve("vault.enc")),
): Int {
    try {
        if (!vault.exists()) return 0
        val passphrase = vaultPassphraseNoPrompt(sources)
            ?: return vaultXtraUpdateFailed("the vault passphrase is unavailable without a prompt")
        try {
            ConfigFileTransaction.withLock(baseConfigDir.resolve("config.toml")) {
                if (!vault.exists()) return@withLock
                val data = vault.read(passphrase)
                if (XTRA_VAULT_ENTRY in data) {
                    vault.write(passphrase, setVaultXtraPassphrase(data, String(newPassphrase)))
                    println("Updated the vault's X-tra passphrase.")
                }
            }
        } finally {
            passphrase.fill('\u0000')
        }
        return 0
    } catch (e: Exception) {
        return vaultXtraUpdateFailed(e.javaClass.simpleName)
    }
}

private fun vaultXtraUpdateFailed(reason: String): Int {
    System.err.println(
        "Error: the X-tra key file already changed, but its vaulted passphrase could not be updated ($reason). " +
            "Update it with: unidrive vault xtra-set",
    )
    return 1
}
