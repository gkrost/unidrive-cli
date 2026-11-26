package org.krost.unidrive.sync

import org.krost.unidrive.auth.CredentialData
import org.krost.unidrive.auth.CredentialProtectionException
import org.krost.unidrive.auth.CredentialProtector
import org.krost.unidrive.auth.CredentialProtectors
import org.krost.unidrive.auth.KeyedCredentialProtector
import java.nio.file.Files
import java.nio.file.Path

/** The vault-key seal of config.toml's provider secret fields ([ConfigSecrets.SENSITIVE_FIELDS]; #859, #797). */
object ConfigSecretSeal {
    /**
     * The protector of these fields (gkrost/unidrive-cli#859, #797): the vault-key seal over the host's platform
     * default, as the credential files have it. Without a seal key in the process, or with sealing off, it writes and
     * reads exactly what the platform default did; a host without one (Linux) keeps plaintext fields until sealing is on.
     */
    fun defaultProtector(): CredentialProtector = sealableProtector(CredentialProtectors.atRestDefaultOrNull())

    /** The seal over [inner], or over the plaintext field itself when [inner] is null ([NoPlatformStore]). */
    fun sealableProtector(inner: CredentialProtector?): CredentialProtector = KeyedCredentialProtector(inner ?: NoPlatformStore)

    /** Whether config.toml holds a field sealed under the vault key (so `vault decrypt` keeps the key, #797). */
    fun holdsSealedField(configPath: Path): Boolean =
        runCatching { Files.readString(configPath).contains(KeyedCredentialProtector.PREFIX) }.getOrDefault(false)

    /**
     * No platform layer: the field is its own value. A platform reference from another host is not plaintext here
     * ([ForeignReference], reported as "protected on another computer", as without a protector).
     */
    private object NoPlatformStore : CredentialProtector {
        override fun protect(id: String, plaintext: String): String = plaintext

        override fun decode(id: String, stored: String): CredentialData {
            if (CredentialProtectors.REFERENCE_PREFIXES.any { stored.startsWith(it) }) throw ForeignReference()
            return CredentialData(stored, true)
        }

        override fun delete(id: String) = Unit
    }

    internal class ForeignReference : CredentialProtectionException("a platform reference from another computer")
}
