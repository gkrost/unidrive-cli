package org.krost.unidrive.cli

import java.nio.file.Files
import java.nio.file.Path

/**
 * The X-tra content algorithms as the CLI names them: the `xtra_algorithm` value, the key file beside config.toml
 * and the `xtra` verbs that create it and change its passphrase. `profile set`'s key guard, the provider wiring and
 * the `xtra` verbs all read this one table, so the file a guard checks is the file the daemon loads.
 */
internal enum class XtraAlgorithm(
    val configName: String,
    val keyFileName: String,
    private val initVerb: String,
    private val changePassphraseVerb: String,
) {
    AES_GCM("aes-gcm", "xtra.key", "init", "change-passphrase"),
    AGE_V1("age-v1", "xtra-age.key", "init-age", "change-passphrase-age"),
    TINK_AES_GCM("tink-aes-gcm", "xtra-tink.key", "init-tink", "change-passphrase-tink"),
    ;

    val initCommand: String get() = "unidrive xtra $initVerb"

    val changePassphraseCommand: String get() = "unidrive xtra $changePassphraseVerb"

    companion object {
        fun named(name: String): XtraAlgorithm? = entries.firstOrNull { it.configName == name }

        /**
         * Every key file wraps under the one X-tra passphrase (one env var, one credential, one unlock-helper
         * secret), so changing it on [changed]'s file leaves the others under the old one: the daemon then fails to
         * load them. One reminder per other key file present in [configDir], and one for the vault's `.xtra` entry when
         * `vault.enc` exists there (issue#796).
         */
        fun sharedPassphraseReminders(
            configDir: Path,
            changed: XtraAlgorithm,
        ): List<String> {
            val keyFiles =
                entries.filter { it != changed && Files.exists(configDir.resolve(it.keyFileName)) }.map {
                    "Note: ${it.keyFileName} is still under the old passphrase (all X-tra key files share one); " +
                        "change it too: ${it.changePassphraseCommand}"
                }
            // The vault's `.xtra` entry wraps under the same passphrase; whether it holds the old one cannot be read here.
            val vaultEntry =
                if (Files.exists(configDir.resolve("vault.enc"))) {
                    listOf("Note: the vault's X-tra passphrase may still be the old one; update it: unidrive vault xtra-set")
                } else {
                    emptyList()
                }
            return keyFiles + vaultEntry
        }
    }
}
