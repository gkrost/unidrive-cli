package org.krost.unidrive.cli

import java.nio.file.Files
import java.nio.file.Path

/**
 * The X-tra content algorithms as the CLI names them: the `xtra_algorithm` value, the key file beside config.toml
 * and the `xtra` verbs that create it and change its passphrase. `profile set`'s key guard, the provider wiring and
 * the `xtra` verbs all read this one table, so the file a guard checks is the file the daemon loads.
 *
 * [quantumResistant]: whether every key operation of the algorithm is symmetric. A file whose key is wrapped with RSA
 * (aes-gcm) or X25519 (age-v1) can be opened later by a quantum attacker who recovers the private key from the public
 * one (Shor); AES-256 keeps its margin (Grover).
 */
internal enum class XtraAlgorithm(
    val configName: String,
    val keyFileName: String,
    private val initVerb: String,
    private val changePassphraseVerb: String,
    val quantumResistant: Boolean,
) {
    AES_GCM_SYM("aes-gcm-sym", "xtra-sym.key", "init", "change-passphrase --algorithm aes-gcm-sym", true),
    AES_GCM("aes-gcm", "xtra.key", "init --algorithm aes-gcm", "change-passphrase --algorithm aes-gcm", false),
    AGE_V1("age-v1", "xtra-age.key", "init-age", "change-passphrase-age", false),
    TINK_AES_GCM("tink-aes-gcm", "xtra-tink.key", "init-tink", "change-passphrase-tink", true),
    ;

    val initCommand: String get() = "unidrive xtra $initVerb"

    val changePassphraseCommand: String get() = "unidrive xtra $changePassphraseVerb"

    companion object {
        fun named(name: String): XtraAlgorithm? = entries.firstOrNull { it.configName == name }

        /** The algorithm `xtra init` creates a key for when none is named: the symmetric chain. */
        val NEW_KEY_DEFAULT: XtraAlgorithm get() = AES_GCM_SYM

        /**
         * The algorithm of a profile that sets no `xtra_algorithm`: aes-gcm, the default before aes-gcm-sym existed, so a
         * profile that already runs encrypted keeps its algorithm and its files. New profiles never rely on it: turning
         * encryption on and `xtra init` write [NEW_KEY_DEFAULT] into the profile (owner decision, #1006), and no key file
         * ever changes what a profile reads as.
         */
        val UNSET_DEFAULT: XtraAlgorithm get() = AES_GCM

        /**
         * The key file `export-key`, `import-key` and `change-passphrase` act on without `--algorithm`: xtra.key where it
         * exists (the commands an existing installation used act on what they always did), xtra-sym.key otherwise. It
         * picks a file for one command and never a profile's algorithm.
         */
        fun keyVerbDefault(configDir: Path): XtraAlgorithm =
            if (Files.exists(configDir.resolve(AES_GCM.keyFileName))) AES_GCM else NEW_KEY_DEFAULT

        /**
         * Every key file wraps under the one X-tra passphrase (one env var, one credential, one unlock-helper
         * secret), so changing it on [changed]'s file leaves the others under the old one: the daemon then fails to
         * load them. One reminder per other key file present in [configDir].
         */
        fun sharedPassphraseReminders(
            configDir: Path,
            changed: XtraAlgorithm,
        ): List<String> =
            entries.filter { it != changed && Files.exists(configDir.resolve(it.keyFileName)) }.map {
                "Note: ${it.keyFileName} is still under the old passphrase (all X-tra key files share one); " +
                    "change it too: ${it.changePassphraseCommand}"
            }
    }
}
