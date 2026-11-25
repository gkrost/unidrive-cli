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
import kotlin.system.exitProcess

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
        System.err.println("Error: unknown xtra_algorithm \"$name\" (known: $known).")
        exitProcess(1)
    }
    // The key file comes from the same table `profile set` guards with. It never self-generates on the first
    // content operation (a root secret must not appear silently); it must exist, or the profile refuses to run.
    val keyPath = tokenPath.parent.resolve(algorithm.keyFileName)
    if (!Files.exists(keyPath)) {
        System.err.println("Error: a profile sets xtra_encryption with xtra_algorithm = \"$name\" but $keyPath does not exist.")
        System.err.println("Create it first: ${algorithm.initCommand}")
        exitProcess(1)
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
internal fun Main.xtraPassphrase(): CharArray {
    loadVaultForXtra()
    return resolveXtraPassphrase()
}

private fun Main.resolveXtraPassphrase(): CharArray =
    when (val resolution = resolvePassphrase(
        secretKind = "xtra",
        envName = XTRA_PASSPHRASE_ENV,
        credentialName = "unidrive-xtra-pass",
        configDir = baseConfigDir,
        helperConfig = rawGeneral().unlock_helper,
        prompt = "X-tra passphrase: ",
        sources = PassphraseSources(vaultSecret = { vaultXtraPassphrase() }),
    )) {
        is PassphraseResolution.Secret -> resolution.chars
        is PassphraseResolution.Refused ->
            if (resolution.consoleWasPresent) {
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
private fun Main.loadVaultForXtra() {
    if (vaultDataCache != null || daemonVaultLocked) return
    val vault = Vault(baseConfigDir.resolve("vault.enc"))
    if (!vault.exists()) return
    val passphrase = vaultPassphraseNoPrompt(PassphraseSources(consolePresent = { false })) ?: return
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
            sources = sources,
        ) as? PassphraseResolution.Secret
    )?.chars

/**
 * After `xtra change-passphrase*` re-wrapped a key file under [newPassphrase]: the vault's `.xtra` entry wraps under
 * the same passphrase, so it is brought along when the vault's passphrase is available without a prompt
 * (environment, systemd credential, unlock helper). Returns false, after printing the reminder, when the vault
 * exists but could not be updated (its passphrase is unavailable or wrong); the command then exits non-zero so the
 * mismatch cannot go unnoticed. No vault, or a vault without `.xtra`, is true and silent.
 */
internal fun Main.updateVaultXtraAfterChange(
    newPassphrase: CharArray,
    sources: PassphraseSources = PassphraseSources(consolePresent = { false }),
): Boolean {
    val vault = Vault(baseConfigDir.resolve("vault.enc"))
    if (!vault.exists()) return true
    val passphrase = vaultPassphraseNoPrompt(sources)
    val data = passphrase?.let { runCatching { vault.read(it) }.getOrNull() }
    if (passphrase == null || data == null) {
        passphrase?.fill('\u0000')
        System.err.println(
            "Error: the vault's X-tra passphrase may still be the old one and could not be updated here " +
                "(its passphrase is not available without a prompt); update it: unidrive vault xtra-set",
        )
        return false
    }
    try {
        if (XTRA_VAULT_ENTRY !in data) return true
        ConfigFileTransaction.withLock(baseConfigDir.resolve("config.toml")) {
            vault.write(passphrase, setVaultXtraPassphrase(vault.read(passphrase), String(newPassphrase)))
        }
        println("Updated the vault's X-tra passphrase.")
        return true
    } finally {
        passphrase.fill('\u0000')
    }
}
