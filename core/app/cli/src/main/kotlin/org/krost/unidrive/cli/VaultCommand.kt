package org.krost.unidrive.cli

import org.krost.unidrive.sync.ConfigSecrets
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.Vault
import org.krost.unidrive.sync.escapeTomlValue
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.nio.file.Files

@Command(
    name = "vault",
    description = ["Manage encrypted credential vault"],
    mixinStandardHelpOptions = true,
    subcommands = [
        VaultInitCommand::class,
        VaultEncryptCommand::class,
        VaultDecryptCommand::class,
        VaultChangePassphraseCommand::class,
        VaultForgetCommand::class,
    ],
)
class VaultCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    override fun run() {
        println("Usage: unidrive vault <init|encrypt|decrypt|change-passphrase|forget>")
    }
}

// ── vault init ───────────────────────────────────────────────────────────────

@Command(name = "init", description = ["Initialize a new credential vault"], mixinStandardHelpOptions = true)
class VaultInitCommand : Runnable {
    @ParentCommand
    lateinit var vaultCmd: VaultCommand

    // issue#796: a client can set up the vault without a console (issue#796); the
    // passphrase is never an argument.
    @Option(
        names = ["--passphrase-stdin"],
        description = [
            "Read the new vault passphrase from the first line of standard input instead of the " +
                "terminal (no confirmation line; no interactive terminal required)",
        ],
    )
    var passphraseStdin: Boolean = false

    override fun run() {
        val vault = Vault(vaultCmd.parent.configBaseDir().resolve("vault.enc"))
        if (vault.exists()) {
            System.err.println("Vault already exists. Use 'vault change-passphrase' to update.")
            System.exit(1)
        }

        val pass1 =
            if (passphraseStdin) {
                readPassphraseStdin("--passphrase-stdin")
            } else {
                val console = System.console()
                if (console == null) {
                    System.err.println("Error: interactive terminal required.")
                    System.exit(1)
                }
                val pass = console.readPassword("New vault passphrase (min ${Vault.MIN_PASSPHRASE_LENGTH} chars): ") ?: run {
                    System.err.println("Error: could not read passphrase.")
                    kotlin.system.exitProcess(1)
                }
                val confirmed = console.readPassword("Confirm passphrase: ") ?: run {
                    System.err.println("Error: could not read passphrase.")
                    kotlin.system.exitProcess(1)
                }
                if (!pass.contentEquals(confirmed)) {
                    System.err.println("Passphrases do not match.")
                    System.exit(1)
                }
                pass
            }

        if (pass1.size < Vault.MIN_PASSPHRASE_LENGTH) {
            System.err.println("Passphrase must be at least ${Vault.MIN_PASSPHRASE_LENGTH} characters.")
            System.exit(1)
        }

        vault.init(pass1)
        println("Vault initialized at ${vaultCmd.parent.configBaseDir().resolve("vault.enc")}")
    }
}

// ── vault encrypt ────────────────────────────────────────────────────────────

@Command(name = "encrypt", description = ["Move config credentials into the vault"], mixinStandardHelpOptions = true)
class VaultEncryptCommand : Runnable {
    @ParentCommand
    lateinit var vaultCmd: VaultCommand

    // issue#796: the passphrase from standard input instead of the terminal; never an argument.
    @Option(
        names = ["--passphrase-stdin"],
        description = [
            "Read the vault passphrase from the first line of standard input instead of the " +
                "terminal (no interactive terminal required)",
        ],
    )
    var passphraseStdin: Boolean = false

    override fun run() {
        val baseDir = vaultCmd.parent.configBaseDir()
        val vault = Vault(baseDir.resolve("vault.enc"))
        if (!vault.exists()) {
            System.err.println("No vault found. Run 'unidrive vault init' first.")
            System.exit(1)
        }

        val passphrase =
            if (passphraseStdin) readPassphraseStdin("--passphrase-stdin") else readPassphrase("Vault passphrase: ")

        val configPath = baseDir.resolve("config.toml")
        ConfigFileTransaction.withLock(configPath) {
            if (!Files.exists(configPath)) {
                println("No config.toml found — nothing to encrypt.")
                return@withLock
            }
        val data = vault.read(passphrase)
        val mutableData = data.toMutableMap()

        val configLines = Files.readAllLines(configPath)
        val raw = SyncConfig.parseRaw(configLines.joinToString("\n"), configPath.toString())
        val vaultedCreds = mutableMapOf<String, Map<String, String>>()
        for ((profileName, rp) in raw.providers) {
            // issue#786: config secret fields are protected at rest; the vault stores the decoded value,
            // never the at-rest reference (which would be useless outside this machine/user).
            val creds = secretCreds(ConfigSecrets.decode(rp, profileName, configPath) ?: rp)
            if (creds.isNotEmpty()) {
                mutableData[profileName] = (mutableData[profileName] ?: emptyMap()) + creds
                vaultedCreds[profileName] = creds
            }
        }

            if (vaultedCreds.isEmpty()) {
                println("No sensitive credentials found in config.toml.")
                return@withLock
            }

            val filteredLines = filterVaultedLines(configLines, vaultedCreds)

            vault.write(passphrase, mutableData)

            ConfigFileTransaction.writeAtomically(configPath, filteredLines.joinToString("\n"))

            println("Encrypted credentials for ${vaultedCreds.size} profile(s).")
        }
    }

    /** The sensitive fields of a parsed provider profile that move into the vault. */
    private fun secretCreds(rp: org.krost.unidrive.sync.RawProvider): Map<String, String> {
        val creds = mutableMapOf<String, String>()
        extractIfPresent(rp.access_key_id, "access_key_id", creds)
        extractIfPresent(rp.secret_access_key, "secret_access_key", creds)
        extractIfPresent(rp.password, "password", creds)
        extractIfPresent(rp.client_id, "client_id", creds)
        extractIfPresent(rp.client_secret, "client_secret", creds)
        return creds
    }

    private fun extractIfPresent(
        value: String?,
        key: String,
        target: MutableMap<String, String>,
    ) {
        if (!value.isNullOrBlank()) target[key] = value
    }
}

/** The named sensitive field of a parsed provider profile; the vault filter's post-check reads it, and so does the locked-start check (issue#796). */
internal fun rawProviderSecret(
    profile: org.krost.unidrive.sync.RawProvider?,
    key: String,
): String? =
    when (key) {
        "access_key_id" -> profile?.access_key_id
        "secret_access_key" -> profile?.secret_access_key
        "password" -> profile?.password
        "client_id" -> profile?.client_id
        "client_secret" -> profile?.client_secret
        else -> null
    }

/**
 * The indices in [range] whose line sets [key]: leading whitespace and any spacing around the `=` (ktoml
 * accepts `key<TAB>=`), and only a whole bare key matches, not one that merely starts with the same characters.
 */
private fun linesSettingKey(
    configLines: List<String>,
    range: IntRange,
    key: String,
): List<Int> {
    val keyPattern = Regex("""^\s*${Regex.escape(key)}\s*=""")
    return range.filter { keyPattern.containsMatchIn(configLines[it]) }
}

/**
 * The config lines left after the vaulted credentials are cut out of their `[providers.<name>]` sections
 * (issue#83 C). Header and key shapes are the tolerant ones ktoml accepts — inner and trailing whitespace in
 * the header, a trailing comment, spacing around the `=` — so a secret is not left behind in a shape the
 * strict match used to miss; any header line closes the section before it. As the backstop, the filtered
 * lines are re-parsed and the function refuses (naming the profile and key, never the value) if ktoml still
 * reads a vaulted value out of them: what the parser still finds would win over the vault.
 */
internal fun filterVaultedLines(
    configLines: List<String>,
    vaulted: Map<String, Map<String, String>>,
): List<String> {
    val headerPattern = Regex("""^\s*\[([^\]]+)]\s*(?:#.*)?$""")
    val sectionRanges = mutableMapOf<String, IntRange>()
    var currentSection: String? = null
    var currentStart = -1
    for ((idx, line) in configLines.withIndex()) {
        val header = headerPattern.matchEntire(line)?.groupValues?.get(1)?.trim()
        if (header != null) {
            if (currentSection != null) {
                sectionRanges[currentSection] = currentStart until idx
            }
            currentSection = header
            currentStart = idx
        }
    }
    if (currentSection != null) {
        sectionRanges[currentSection] = currentStart until configLines.size
    }

    val linesToRemove = mutableSetOf<Int>()
    for ((profileName, creds) in vaulted) {
        // Scoped to this profile's section only.
        val range = sectionRanges["providers.$profileName"] ?: continue
        linesToRemove += creds.keys.flatMap { key -> linesSettingKey(configLines, range, key) }
    }

    val filteredLines = configLines.filterIndexed { idx, _ -> idx !in linesToRemove }
    val remaining = SyncConfig.parseRaw(filteredLines.joinToString("\n"))
    val leftBehind =
        vaulted.flatMapTo(mutableListOf()) { (profileName, creds) ->
            val profile = remaining.providers[profileName]
            creds.keys.filter { rawProviderSecret(profile, it) != null }.map { "$profileName.$it" }
        }
    check(leftBehind.isEmpty()) {
        "vault encrypt would leave ${leftBehind.joinToString()} as plaintext in config.toml; " +
            "nothing was written — the config uses a key or section shape this command cannot cut, please report it"
    }
    return filteredLines
}

// ── vault decrypt ────────────────────────────────────────────────────────────

@Command(name = "decrypt", description = ["Restore credentials from vault to config.toml"], mixinStandardHelpOptions = true)
class VaultDecryptCommand : Runnable {
    @ParentCommand
    lateinit var vaultCmd: VaultCommand

    override fun run() {
        val baseDir = vaultCmd.parent.configBaseDir()
        val vault = Vault(baseDir.resolve("vault.enc"))
        if (!vault.exists()) {
            System.err.println("No vault found.")
            System.exit(1)
        }

        val passphrase = readPassphrase("Vault passphrase: ")
        println(decryptVault(baseDir, passphrase) { System.err.println("Warning: $it") })
    }
}

/**
 * `vault decrypt` past the passphrase: restores the vault of [baseDir] into its config.toml; returns the line to print.
 * An entry whose profile is no longer configured (#749 finding 7: a `profile remove` that found the vault locked) has no
 * section to go back into: it is skipped with one [warn] naming `vault forget`, and stays in the vault, which is then
 * rewritten with only those entries instead of deleted.
 */
internal fun decryptVault(
    baseDir: java.nio.file.Path,
    passphrase: CharArray,
    warn: (String) -> Unit,
): String {
    val vault = Vault(baseDir.resolve("vault.enc"))
    val data = vault.read(passphrase)
    val configPath = baseDir.resolve("config.toml")
    return ConfigFileTransaction.withLock(configPath) {
        if (data.isEmpty()) {
            vault.delete()
            return@withLock "Vault is empty — nothing to restore."
        }
        val configContent = if (Files.exists(configPath)) Files.readString(configPath) else "[general]\n"
        val configured = SyncConfig.parseRaw(configContent, configPath.toString()).providers.keys
        val restorable = data.filterKeys { it in configured }
        val orphans = data - restorable.keys
        for (name in orphans.keys) {
            warn("the vault entry of profile '$name' has no config section and was not restored; drop it with `unidrive vault forget $name`")
        }
        if (restorable.isNotEmpty()) {
            ConfigFileTransaction.writeAtomically(configPath, restoreVaultCredentials(configContent, restorable))
        }
        if (orphans.isEmpty()) {
            vault.delete()
            "Credentials restored to config.toml. Vault deleted."
        } else {
            vault.write(passphrase, orphans)
            "Credentials of ${restorable.size} profile(s) restored to config.toml; the vault keeps ${orphans.size} orphan entry(ies)."
        }
    }
}

internal fun restoreVaultCredentials(configContent: String, data: Map<String, Map<String, String>>): String {
    val lines = configContent.lines().toMutableList()
    for ((profileName, creds) in data) {
        val sectionHeader = "[providers.$profileName]"
        val sectionIdx = lines.indexOfFirst { it.trim().substringBefore('#').trim() == sectionHeader }
        require(sectionIdx >= 0) { "Cannot restore vault: config has no $sectionHeader section; vault kept." }
        require(creds.keys.all { it in Vault.SENSITIVE_FIELDS }) { "Cannot restore unknown vault fields; vault kept." }

        // Find insertion point: after last key in section, before next section
        var insertIdx = sectionIdx + 1
        while (insertIdx < lines.size) {
            val trimmed = lines[insertIdx].trim()
            if (trimmed.startsWith("[") && trimmed != sectionHeader) break
            insertIdx++
        }
        // Insert before the blank line preceding next section
        if (insertIdx > 0 && lines[insertIdx - 1].isBlank()) insertIdx--

        val credLines = creds.entries.map { (k, v) -> "$k = \"${escapeTomlValue(v)}\"" }
        lines.addAll(insertIdx, credLines)
    }

    val restored = lines.joinToString("\n")
    val parsed = SyncConfig.parseRaw(restored)
    for ((name, creds) in data) {
        val profile = requireNotNull(parsed.providers[name]) { "Cannot restore vault profile; vault kept." }
        val values = mapOf(
            "access_key_id" to profile.access_key_id,
            "secret_access_key" to profile.secret_access_key,
            "password" to profile.password,
            "client_id" to profile.client_id,
            "client_secret" to profile.client_secret,
        )
        require(creds.all { (key, value) -> values[key] == value }) {
            "Cannot verify restored credentials; vault kept."
        }
    }
    return restored
}

// ── vault change-passphrase ──────────────────────────────────────────────────

@Command(name = "change-passphrase", description = ["Change the vault passphrase"], mixinStandardHelpOptions = true)
class VaultChangePassphraseCommand : Runnable {
    @ParentCommand
    lateinit var vaultCmd: VaultCommand

    // issue#796: a client can re-key the vault without a console (issue#796); the
    // passphrases are never arguments.
    @Option(
        names = ["--passphrase-stdin"],
        description = [
            "Read the current vault passphrase from the first line of standard input instead of " +
                "the terminal (no interactive terminal required)",
        ],
    )
    var passphraseStdin: Boolean = false

    @Option(
        names = ["--new-passphrase-stdin"],
        description = [
            "Read the new vault passphrase from the first line of standard input instead of the " +
                "terminal (no confirmation line; no interactive terminal required)",
        ],
    )
    var newPassphraseStdin: Boolean = false

    override fun run() {
        val vault = Vault(vaultCmd.parent.configBaseDir().resolve("vault.enc"))
        if (!vault.exists()) {
            System.err.println("No vault found. Run 'unidrive vault init' first.")
            System.exit(1)
        }

        val oldPass =
            if (passphraseStdin) readPassphraseStdin("--passphrase-stdin") else readPassphrase("Current passphrase: ")
        val data = vault.read(oldPass)

        val newPass1 =
            if (newPassphraseStdin) {
                readPassphraseStdin("--new-passphrase-stdin")
            } else {
                val console = System.console()
                if (console == null) {
                    System.err.println("Error: interactive terminal required.")
                    System.exit(1)
                }
                val pass = console.readPassword("New passphrase (min ${Vault.MIN_PASSPHRASE_LENGTH} chars): ") ?: run {
                    System.err.println("Error: could not read passphrase.")
                    kotlin.system.exitProcess(1)
                }
                val confirmed = console.readPassword("Confirm new passphrase: ") ?: run {
                    System.err.println("Error: could not read passphrase.")
                    kotlin.system.exitProcess(1)
                }
                if (!pass.contentEquals(confirmed)) {
                    System.err.println("Passphrases do not match.")
                    System.exit(1)
                }
                pass
            }

        if (newPass1.size < Vault.MIN_PASSPHRASE_LENGTH) {
            System.err.println("Passphrase must be at least ${Vault.MIN_PASSPHRASE_LENGTH} characters.")
            System.exit(1)
        }

        vault.write(newPass1, data)
        println("Vault passphrase changed.")
    }
}

// ── vault forget ─────────────────────────────────────────────────────────────

@Command(
    name = "forget",
    description = ["Drop the vault entry of a profile that is no longer configured (one `profile remove` could not drop)"],
    mixinStandardHelpOptions = true,
)
class VaultForgetCommand : java.util.concurrent.Callable<Int> {
    @ParentCommand
    lateinit var vaultCmd: VaultCommand

    @Parameters(index = "0", description = ["Profile whose vault entry is dropped"])
    lateinit var profile: String

    @Option(
        names = ["--passphrase-stdin"],
        description = [
            "Read the vault passphrase from the first line of standard input instead of the " +
                "terminal (no interactive terminal required)",
        ],
    )
    var passphraseStdin: Boolean = false

    override fun call(): Int {
        val baseDir = vaultCmd.parent.configBaseDir()
        val vault = Vault(baseDir.resolve("vault.enc"))
        if (!vault.exists()) {
            System.err.println("No vault found.")
            return 1
        }
        val configPath = baseDir.resolve("config.toml")
        val passphrase =
            if (passphraseStdin) readPassphraseStdin("--passphrase-stdin") else readPassphrase("Vault passphrase: ")
        val dropped =
            ConfigFileTransaction.withLock(configPath) {
                // The entry of a configured profile is the only copy of its credentials: never dropped here.
                if (Files.exists(configPath) &&
                    profile in SyncConfig.parseRaw(Files.readString(configPath), configPath.toString()).providers
                ) {
                    null
                } else {
                    forgetVaultEntry(vault, passphrase, profile)
                }
            }
        when (dropped) {
            null -> {
                System.err.println("Profile '$profile' is still configured; remove it with 'unidrive profile remove $profile'.")
                return 1
            }
            true -> println("Dropped the vault entry of profile '$profile'.")
            false -> println("The vault holds no entry for profile '$profile'.")
        }
        return 0
    }
}

/** Drops [name]'s entry from [vault]; true when there was one. The other entries are written back unchanged. */
internal fun forgetVaultEntry(
    vault: Vault,
    passphrase: CharArray,
    name: String,
): Boolean {
    val data = vault.read(passphrase)
    if (name !in data) return false
    vault.write(passphrase, data - name)
    return true
}

// ── Shared helpers ───────────────────────────────────────────────────────────

private fun readPassphrase(prompt: String): CharArray {
    val console = System.console()
    if (console == null) {
        System.err.println("Error: interactive terminal required.")
        System.exit(1)
    }
    return console!!.readPassword(prompt) ?: run {
        System.err.println("Error: could not read passphrase.")
        kotlin.system.exitProcess(1)
    }
}

/**
 * The first line of standard input, read byte by byte so nothing past the line terminator is
 * consumed (the same shape `auth login --password-stdin` reads). Empty input is a refusal naming
 * the flag, never a blank passphrase.
 */
private fun readPassphraseStdin(flag: String): CharArray {
    val bytes = java.io.ByteArrayOutputStream()
    while (true) {
        val b = System.`in`.read()
        if (b < 0 || b == '\n'.code) break
        bytes.write(b)
    }
    val line = bytes.toString(Charsets.UTF_8).removeSuffix("\r")
    if (line.isEmpty()) {
        System.err.println("Error: $flag was given but standard input held no passphrase.")
        kotlin.system.exitProcess(1)
    }
    return line.toCharArray()
}
