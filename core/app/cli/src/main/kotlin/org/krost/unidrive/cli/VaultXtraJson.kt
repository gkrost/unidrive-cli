package org.krost.unidrive.cli

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.krost.unidrive.sync.Vault
import java.nio.file.Files
import java.nio.file.Path
import javax.crypto.AEADBadTagException

/*
 * gkrost/unidrive-cli#973: `vault xtra-set --json` / `vault xtra-clear --json`, and `vault init|encrypt|change-passphrase --json`. One JSON object on stdout, the
 * refusal carrying a stable `error` token (invalid_request, vault_absent, vault_bad_passphrase,
 * xtra_passphrase_mismatch, vault_exists, passphrase_too_short), exit 0 or 1 as in human mode. Under --json nothing is ever prompted: the secrets come
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
            val data = badPassphraseAsRefusal { vault.read(oldPass) }
            newPass = requireStdinSecret("--new-passphrase-stdin")
            requireLongEnough(newPass)
            vault.write(newPass, data)
            buildJsonObject { put("ok", true) }
        } finally {
            oldPass.fill('\u0000')
            newPass?.fill('\u0000')
        }
    }
