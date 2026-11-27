package org.krost.unidrive.cli

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.Vault
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.AEADBadTagException

/*
 * gkrost/unidrive-cli#973: `vault xtra-set --json` / `vault xtra-clear --json`, and `vault init|encrypt|decrypt|change-passphrase|forget|seal-credentials --json`. One JSON object on stdout, the
 * refusal carrying a stable `error` token (invalid_request, vault_absent, vault_bad_passphrase,
 * xtra_passphrase_mismatch, vault_exists, passphrase_too_short, reserved_entry, profile_configured, restore_failed), exit 0 or 1 as in human mode. Under --json nothing is ever prompted: the secrets come
 * only from the stdin flags, one line each (vault passphrase first), and a missing flag or line is invalid_request.
 */

/** The next line of standard input read byte by byte (nothing past the terminator is consumed), or null when it is empty. */
private fun stdinLineOrNull(): CharArray? {
    val bytes = java.io.ByteArrayOutputStream()
    while (true) {
        val b = System.`in`.read()
        if (b < 0 || b == '\n'.code) break
        bytes.write(b)
    }
    val line = bytes.toString(Charsets.UTF_8).removeSuffix("\r")
    return if (line.isEmpty()) null else line.toCharArray()
}

private fun requireStdinSecret(flag: String): CharArray =
    stdinLineOrNull() ?: throw LifecycleError("invalid_request", "$flag was given but standard input held no passphrase")

private fun openVault(configDir: Path): Vault {
    val vault = Vault(configDir.resolve("vault.enc"))
    if (!vault.exists()) throw LifecycleError("vault_absent", "no vault found; run 'unidrive vault init' first")
    return vault
}

private fun <T> badPassphraseAsRefusal(block: () -> T): T =
    try {
        block()
    } catch (e: AEADBadTagException) {
        throw LifecycleError("vault_bad_passphrase", "wrong vault passphrase", e)
    }

internal fun xtraSetJson(
    configDir: Path,
    passphraseStdin: Boolean,
    xtraPassphraseStdin: Boolean,
): Int =
    reply(true) {
        val vault = openVault(configDir)
        if (!passphraseStdin || !xtraPassphraseStdin) {
            throw LifecycleError("invalid_request", "--json needs --passphrase-stdin and --xtra-passphrase-stdin; it never prompts")
        }
        val vaultPass = requireStdinSecret("--passphrase-stdin")
        val xtraPass = requireStdinSecret("--xtra-passphrase-stdin")
        try {
            val present = XtraAlgorithm.entries.filter { Files.exists(configDir.resolve(it.keyFileName)) }
            val opening = present.filter { xtraKeyFileOpens(it, configDir, xtraPass) }
            if (present.isNotEmpty() && opening.isEmpty()) {
                throw LifecycleError(
                    "xtra_passphrase_mismatch",
                    "the passphrase opens none of ${present.joinToString { it.keyFileName }}; nothing was stored",
                )
            }
            badPassphraseAsRefusal {
                ConfigFileTransaction.withLock(configDir.resolve("config.toml")) {
                    vault.write(vaultPass, setVaultXtraPassphrase(vault.read(vaultPass), String(xtraPass)))
                }
            }
            buildJsonObject {
                put("ok", true)
                putJsonArray("key_files_not_under_passphrase") {
                    (present - opening.toSet()).forEach { add(JsonPrimitive(it.keyFileName)) }
                }
            }
        } finally {
            vaultPass.fill('\u0000')
            xtraPass.fill('\u0000')
        }
    }

internal fun xtraClearJson(
    configDir: Path,
    passphraseStdin: Boolean,
): Int =
    reply(true) {
        val vault = openVault(configDir)
        if (!passphraseStdin) throw LifecycleError("invalid_request", "--json needs --passphrase-stdin; it never prompts")
        val passphrase = requireStdinSecret("--passphrase-stdin")
        try {
            val removed =
                badPassphraseAsRefusal {
                    ConfigFileTransaction.withLock(configDir.resolve("config.toml")) {
                        forgetVaultEntry(vault, passphrase, XTRA_VAULT_ENTRY)
                    }
                }
            buildJsonObject {
                put("ok", true)
                put("removed", removed)
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }

private fun requireFlags(
    vararg given: Boolean,
    message: String,
) {
    if (!given.all { it }) throw LifecycleError("invalid_request", message)
}

private fun requireLongEnough(passphrase: CharArray) {
    if (passphrase.size < Vault.MIN_PASSPHRASE_LENGTH) {
        throw LifecycleError("passphrase_too_short", "the passphrase must be at least ${Vault.MIN_PASSPHRASE_LENGTH} characters")
    }
}

internal fun vaultInitJson(
    configDir: Path,
    passphraseStdin: Boolean,
): Int =
    reply(true) {
        val vault = Vault(configDir.resolve("vault.enc"))
        if (vault.exists()) throw LifecycleError("vault_exists", "a vault already exists; use 'vault change-passphrase' to update it")
        requireFlags(passphraseStdin, message = "--json needs --passphrase-stdin; it never prompts")
        val passphrase = requireStdinSecret("--passphrase-stdin")
        try {
            requireLongEnough(passphrase)
            vault.init(passphrase)
            buildJsonObject { put("ok", true) }
        } finally {
            passphrase.fill('\u0000')
        }
    }

/** [moveCredentials] answers the number of profiles moved into the vault, or null without a config.toml. */
internal fun vaultEncryptJson(
    configDir: Path,
    passphraseStdin: Boolean,
    moveCredentials: (Vault, CharArray) -> Int?,
): Int =
    reply(true) {
        val vault = openVault(configDir)
        requireFlags(passphraseStdin, message = "--json needs --passphrase-stdin; it never prompts")
        val passphrase = requireStdinSecret("--passphrase-stdin")
        try {
            val moved = badPassphraseAsRefusal { moveCredentials(vault, passphrase) }
            buildJsonObject {
                put("ok", true)
                put("profiles", moved ?: 0)
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }

internal fun vaultChangePassphraseJson(
    configDir: Path,
    passphraseStdin: Boolean,
    newPassphraseStdin: Boolean,
): Int =
    reply(true) {
        val vault = openVault(configDir)
        requireFlags(passphraseStdin, newPassphraseStdin, message = "--json needs --passphrase-stdin and --new-passphrase-stdin; it never prompts")
        val oldPass = requireStdinSecret("--passphrase-stdin")
        var newPass: CharArray? = null
        try {
            // #1038: both secrets first, then read and write under the config lock, so no other vault writer's entry is lost.
            val fresh = requireStdinSecret("--new-passphrase-stdin")
            newPass = fresh
            requireLongEnough(fresh)
            badPassphraseAsRefusal {
                ConfigFileTransaction.withLock(configDir.resolve("config.toml")) {
                    vault.write(fresh, vault.read(oldPass))
                }
            }
            buildJsonObject { put("ok", true) }
        } finally {
            oldPass.fill('\u0000')
            newPass?.fill('\u0000')
        }
    }

internal fun vaultDecryptJson(
    configDir: Path,
    passphraseStdin: Boolean,
): Int =
    reply(true) {
        val vault = openVault(configDir)
        requireFlags(passphraseStdin, message = "--json needs --passphrase-stdin; it never prompts")
        val passphrase = requireStdinSecret("--passphrase-stdin")
        try {
            val report =
                try {
                    badPassphraseAsRefusal { decryptVaultReport(configDir, passphrase) { } }
                } catch (e: IllegalArgumentException) {
                    // restoreVaultCredentials refuses before it writes: the vault is kept.
                    throw LifecycleError("restore_failed", "the credentials could not be restored; the vault was kept", e)
                }
            buildJsonObject {
                put("ok", true)
                put("restored_profiles", report.restoredProfiles)
                putJsonArray("orphans") { report.orphans.forEach { add(JsonPrimitive(it)) } }
                put("vault_deleted", !vault.exists())
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }

internal fun vaultForgetJson(
    configDir: Path,
    profile: String,
    passphraseStdin: Boolean,
): Int =
    reply(true) {
        val vault = openVault(configDir)
        if (isReservedVaultSection(profile)) {
            throw LifecycleError("reserved_entry", "'$profile' is a reserved vault entry, not a profile; nothing was dropped")
        }
        requireFlags(passphraseStdin, message = "--json needs --passphrase-stdin; it never prompts")
        val passphrase = requireStdinSecret("--passphrase-stdin")
        try {
            val configPath = configDir.resolve("config.toml")
            val removed =
                badPassphraseAsRefusal {
                    ConfigFileTransaction.withLock(configPath) {
                        // The entry of a configured profile is the only copy of its credentials: never dropped here.
                        if (Files.exists(configPath) &&
                            profile in SyncConfig.parseRaw(Files.readString(configPath), configPath.toString()).providers
                        ) {
                            throw LifecycleError("profile_configured", "profile '$profile' is still configured; remove it with 'unidrive profile remove $profile'")
                        }
                        forgetVaultEntry(vault, passphrase, profile)
                    }
                }
            buildJsonObject {
                put("ok", true)
                put("profile", profile)
                put("removed", removed)
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }

internal fun vaultSealCredentialsJson(
    configDir: Path,
    state: String,
    passphraseStdin: Boolean,
): Int {
    val enabled =
        when (state) {
            "on" -> true
            "off" -> false
            else -> {
                // The human mode's usage error: exit code 2.
                emitJson(lifecycleErrorJson(LifecycleError("invalid_request", "expected on or off, got '$state'")))
                return 2
            }
        }
    return reply(true) {
        val vault = openVault(configDir)
        requireFlags(passphraseStdin, message = "--json needs --passphrase-stdin; it never prompts")
        val passphrase = requireStdinSecret("--passphrase-stdin")
        try {
            badPassphraseAsRefusal {
                ConfigFileTransaction.withLock(configDir.resolve("config.toml")) {
                    vault.write(passphrase, withCredentialSeal(vault.read(passphrase), enabled))
                }
            }
            buildJsonObject {
                put("ok", true)
                put("sealing", if (enabled) "on" else "off")
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }
}
