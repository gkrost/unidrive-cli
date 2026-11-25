package org.krost.unidrive.cli

import org.krost.unidrive.auth.CredentialSeal
import org.krost.unidrive.auth.CredentialSealKey
import org.krost.unidrive.auth.KeyedCredentialProtector
import java.nio.file.Files
import java.nio.file.Path
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

/** True for vault sections that are not a profile's credentials (`vault decrypt` / `vault forget` leave them alone). */
internal fun isReservedVaultSection(name: String): Boolean = name == CREDENTIAL_SEAL_SECTION

/** The seal key [data] holds, or null when the vault has none (sealing was never turned on). */
internal fun credentialSealFrom(data: Map<String, Map<String, String>>): CredentialSealKey? {
    val section = data[CREDENTIAL_SEAL_SECTION] ?: return null
    val secret = section[SEAL_KEY_FIELD]?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: return null
    if (secret.size != CredentialSealKey.SECRET_BYTES) return null
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

/** True when the profile's Internxt mnemonic file (credentials.json in [profileDir]) is sealed under the vault key. */
internal fun profileCredentialsSealed(profileDir: Path): Boolean =
    runCatching {
        Files.newBufferedReader(profileDir.resolve("credentials.json")).use { reader ->
            val head = CharArray(KeyedCredentialProtector.PREFIX.length)
            reader.read(head) == head.size && String(head) == KeyedCredentialProtector.PREFIX
        }
    }.getOrDefault(false)
