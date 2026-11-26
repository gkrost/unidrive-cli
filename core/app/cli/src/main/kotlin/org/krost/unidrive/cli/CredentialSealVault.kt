package org.krost.unidrive.cli

import org.krost.unidrive.auth.CredentialSeal
import org.krost.unidrive.auth.CredentialSealKey
import org.krost.unidrive.ProviderRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.util.Base64

/**
 * gkrost/unidrive-cli#797 (P3): the vault side of the credential seal. The seal secret is a random 256-bit key kept
 * in the vault under its own section (a name no profile can have: profile names are letters, digits, `-` and `_`),
 * with the on/off switch beside it. Keeping the switch in the vault means only the passphrase holder can turn sealing
 * off; keeping the key random (not derived from the passphrase) means `change-passphrase` and a Hello re-enrollment
 * leave the sealed files readable.
 */
internal const val CREDENTIAL_SEAL_SECTION = "unidrive:credential-seal"

private const val SEAL_KEY_FIELD = "key"
private const val SEAL_ENABLED_FIELD = "enabled"

/**
 * True for vault entries that are not a profile's credentials: the credential seal key (#797) and the X-tra passphrase
 * ([XTRA_VAULT_ENTRY], #796). `vault decrypt` keeps them out of config.toml and out of the orphans; `vault forget` refuses them.
 */
internal fun isReservedVaultSection(name: String): Boolean = name == CREDENTIAL_SEAL_SECTION || name == XTRA_VAULT_ENTRY

/**
 * The seal key [data] holds, or null when the vault has none (sealing was never turned on). A section that is present
 * but undecodable (bad base64, wrong length) is not "no key": it throws, so the unlock fails naming the section
 * instead of leaving a process that reads as locked.
 */
internal fun credentialSealFrom(data: Map<String, Map<String, String>>): CredentialSealKey? {
    val section = data[CREDENTIAL_SEAL_SECTION] ?: return null
    val invalid = "the vault's credential seal section is invalid"
    val secret =
        section[SEAL_KEY_FIELD]?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            ?: throw GeneralSecurityException("$invalid (the key is missing or not base64)")
    if (secret.size != CredentialSealKey.SECRET_BYTES) throw GeneralSecurityException("$invalid (the key has the wrong length)")
    return CredentialSealKey(secret, enabled = section[SEAL_ENABLED_FIELD] == "true").also { secret.fill(0) }
}

/** Installs the seal key of freshly read vault [data] for this process (null removes it). */
internal fun installCredentialSeal(data: Map<String, Map<String, String>>?) {
    CredentialSeal.install(data?.let(::credentialSealFrom))
}

/** [data] with sealing switched to [enabled]; the key is created on first use and kept when sealing goes off. */
internal fun withCredentialSeal(
    data: Map<String, Map<String, String>>,
    enabled: Boolean,
): Map<String, Map<String, String>> {
    val existing = data[CREDENTIAL_SEAL_SECTION]?.get(SEAL_KEY_FIELD)
    val key = existing ?: Base64.getEncoder().encodeToString(CredentialSealKey.generateSecret())
    return data + (CREDENTIAL_SEAL_SECTION to mapOf(SEAL_KEY_FIELD to key, SEAL_ENABLED_FIELD to enabled.toString()))
}

/**
 * True when any profile directory under [baseDir] still holds a credential file sealed under the vault key, or
 * config.toml a provider secret field sealed under it (#859).
 */
internal fun anyCredentialsSealed(baseDir: Path): Boolean =
    org.krost.unidrive.sync.ConfigSecrets.holdsSealedField(baseDir.resolve("config.toml")) ||
        runCatching {
            Files.newDirectoryStream(baseDir, Files::isDirectory).use { dirs -> dirs.any { dir -> ProviderRegistry.all().any { it.needsCredentialSeal(dir) } } }
        }.getOrDefault(false)
