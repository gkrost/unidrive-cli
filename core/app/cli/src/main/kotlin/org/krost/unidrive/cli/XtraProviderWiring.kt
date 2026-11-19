package org.krost.unidrive.cli

import org.krost.unidrive.CloudProvider
import org.krost.unidrive.io.XTRA_PASSPHRASE_ENV
import org.krost.unidrive.sync.RawProvider
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
 * credential, the unlock helper (secret kind `xtra`), then a console prompt.
 */
internal fun Main.xtraPassphrase(): CharArray =
    when (val resolution = resolvePassphrase(
        secretKind = "xtra",
        envName = XTRA_PASSPHRASE_ENV,
        credentialName = "unidrive-xtra-pass",
        configDir = baseConfigDir,
        helperConfig = rawGeneral().unlock_helper,
        prompt = "X-tra passphrase: ",
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
                        "or [general] unlock_helper (docs/dev/specs/unlock-helper-protocol.md).",
                )
                error("no X-tra passphrase available")
            }
    }
