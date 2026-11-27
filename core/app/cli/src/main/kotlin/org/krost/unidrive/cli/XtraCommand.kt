package org.krost.unidrive.cli

import org.krost.unidrive.sync.ProfileKeyEdit
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.editProfileKey
import org.krost.unidrive.xtra.XtraAgeKeyStore
import org.krost.unidrive.xtra.XtraKeyManager
import org.krost.unidrive.xtra.XtraSymmetricKeyStore
import org.krost.unidrive.xtra.XtraTinkKeyStore
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Callable

/**
 * Key management of the X-tra layer (client-side encryption of a profile's file content, config key
 * `xtra_encryption`). One key per installation and algorithm, beside config.toml: `init` creates the symmetric
 * aes-gcm-sym master key (`--algorithm aes-gcm` the older RSA pair); export it to a second machine with `export-key`
 * and `import-key`; a lost key loses the encrypted content — nothing else can decrypt it. The `-age` verbs do the
 * same for the age-v1 algorithm's identity (`xtra_algorithm = "age-v1"`, file `xtra-age.key`), whose recovery story
 * is stronger: the export is the standard age identity, so `age -d -i <identity-file>` decrypts the age file that
 * begins after the stored object's 266-byte XTRA header, not the raw object.
 */
@Command(
    name = "xtra",
    description = ["Manage X-tra client-side encryption (the key of profiles with xtra_encryption = true)"],
    mixinStandardHelpOptions = true,
    subcommands = [
        XtraInitCommand::class,
        XtraExportCommand::class,
        XtraImportCommand::class,
        XtraChangePassphraseCommand::class,
        XtraInitAgeCommand::class,
        XtraExportAgeCommand::class,
        XtraImportAgeCommand::class,
        XtraChangePassphraseAgeCommand::class,
        XtraInitTinkCommand::class,
        XtraChangePassphraseTinkCommand::class,
    ],
)
class XtraCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    override fun run() {
        println("Usage: unidrive xtra <init [--algorithm aes-gcm-sym|aes-gcm]|export-key|import-key|change-passphrase|init-age|export-age|import-age|change-passphrase-age|init-tink|change-passphrase-tink>")
    }
}

// ── The two key files the generic verbs manage ───────────────────────────────

/**
 * `init`, `export-key`, `import-key` and `change-passphrase` manage the key file of [XtraAlgorithm.AES_GCM_SYM] (the
 * symmetric master key, what `init` creates when no `--algorithm` is named) or of [XtraAlgorithm.AES_GCM] (the RSA
 * pair). Without `--algorithm`, `init` creates the symmetric key and the other three take xtra.key where it exists
 * ([XtraAlgorithm.keyVerbDefault]), so the commands an installation used before the symmetric default still act on
 * its RSA key file. age-v1 and tink-aes-gcm keep their own verbs.
 */
internal fun keyVerbAlgorithm(
    option: String?,
    fallback: XtraAlgorithm,
): XtraAlgorithm? {
    if (option == null) return fallback
    val named = XtraAlgorithm.named(option.trim().lowercase())
    if (named == XtraAlgorithm.AES_GCM_SYM || named == XtraAlgorithm.AES_GCM) return named
    System.err.println(
        "--algorithm takes aes-gcm-sym or aes-gcm, got '$option' (age-v1 and tink-aes-gcm have their own verbs: init-age, init-tink).",
    )
    return null
}

/** One line naming the algorithm of a key file, and whether it holds against a quantum attacker. */
internal fun algorithmLine(algorithm: XtraAlgorithm): String =
    when (algorithm) {
        XtraAlgorithm.AES_GCM_SYM ->
            "Algorithm: aes-gcm-sym (symmetric: passphrase -> PBKDF2 -> AES-256-GCM master key -> AES-256-GCM file keys; quantum-resistant)"
        XtraAlgorithm.AES_GCM -> "Algorithm: aes-gcm (file keys wrapped with RSA-2048; not quantum-resistant)"
        else -> "Algorithm: ${algorithm.configName}" + if (algorithm.quantumResistant) " (quantum-resistant)" else " (not quantum-resistant)"
    }

/** Loads [keyPath] as [algorithm]'s key file under [passphrase]; throws on a wrong passphrase or a file that is not one. */
internal fun loadKeyFile(
    algorithm: XtraAlgorithm,
    keyPath: java.nio.file.Path,
    passphrase: CharArray,
) {
    when (algorithm) {
        XtraAlgorithm.AES_GCM_SYM -> XtraSymmetricKeyStore(keyPath).load(passphrase)
        else -> XtraKeyManager(keyPath).load(passphrase)
    }
}

/**
 * The profile `xtra init` names the algorithm of: the one `-p` names (by its exact name; a `-p` that names no profile
 * names none, it never falls back to another), else `[general] default_profile`, else the only profile. Null when no
 * profile qualifies, or when that profile already names its algorithm or already runs encrypted (an unset encrypted
 * profile is aes-gcm and stays it, owner decision on #1006).
 */
private fun initTarget(
    raw: org.krost.unidrive.sync.RawSyncConfig,
    named: String?,
): String? {
    val name = if (named != null) named.takeIf { it in raw.providers } else runCatching { SyncConfig.resolveDefaultProfile(raw) }.getOrNull()
    val rp = name?.let { raw.providers[it] } ?: return null
    return name.takeIf { rp.xtra_encryption != true && ProfileSettings.storedXtraAlgorithm(rp) == null }
}

private fun readConfig(configPath: java.nio.file.Path): Pair<String, org.krost.unidrive.sync.RawSyncConfig>? {
    if (!Files.exists(configPath)) return null
    val text = Files.readString(configPath)
    return runCatching { text to SyncConfig.parseRaw(text, configPath.toString()) }.getOrNull()
}

/**
 * Why `xtra init` must not name [algorithm] on the profile it would name, or null when it may: the profile has sync
 * state, so the algorithm it reads as (aes-gcm) may be the one its stored files were sealed with, and naming another
 * one would leave those files refused. [force] waives it, as for `profile set xtra_algorithm`.
 */
internal fun initRefusal(
    configDir: java.nio.file.Path,
    named: String?,
    algorithm: XtraAlgorithm,
    force: Boolean,
): String? {
    if (force) return null
    val (_, raw) = readConfig(configDir.resolve("config.toml")) ?: return null
    val name = initTarget(raw, named) ?: return null
    if (!ProfileSettings.hasSyncState(configDir.resolve(name))) return null
    val current = ProfileSettings.effectiveXtraAlgorithm(raw.providers.getValue(name))
    return "profile '$name' already has sync state; naming its algorithm ${algorithm.configName} would change what it " +
        "reads as ($current now), and files it already stored encrypted with $current would be refused until " +
        "xtra_algorithm names $current again. Nothing was created. Pass --force to write it anyway, or -p another profile."
}

/**
 * Owner decision (#1006): `xtra init` writes the algorithm it created a key for into the profile's section ([initTarget]),
 * so the profile names its algorithm explicitly. The line returned says what happened, null when there is no config.
 */
internal fun pinProfileAlgorithm(
    configDir: java.nio.file.Path,
    named: String?,
    algorithm: XtraAlgorithm,
): String? {
    val configPath = configDir.resolve("config.toml")
    if (!Files.exists(configPath)) return null
    return ConfigFileTransaction.withLock(configPath) {
        val (text, raw) = readConfig(configPath) ?: return@withLock null
        if (named != null && named !in raw.providers) {
            return@withLock "Note: -p '$named' names no profile; xtra_algorithm was written into none. " +
                "Name it on a profile with: unidrive profile set <profile> xtra_algorithm ${algorithm.configName}"
        }
        val name = initTarget(raw, named) ?: run {
            val kept = (named ?: runCatching { SyncConfig.resolveDefaultProfile(raw) }.getOrNull())?.let { raw.providers[it]?.let { rp -> it to rp } }
            return@withLock kept?.let { (n, rp) ->
                "Note: profile '$n' keeps its algorithm (${ProfileSettings.effectiveXtraAlgorithm(rp)}); " +
                    "to write its new files with this key: unidrive profile set $n xtra_algorithm ${algorithm.configName}"
            }
        }
        val line = "${ProfileSettings.XTRA_ALGORITHM} = \"${algorithm.configName}\""
        val edited = (editProfileKey(text, name, ProfileSettings.XTRA_ALGORITHM, line) as? ProfileKeyEdit.Edited)?.text
            ?: return@withLock null
        writeConfigAtomically(configPath, edited)
        "Set $line on profile '$name'."
    }
}

// ── xtra init ─────────────────────────────────────────────────────────────────

@Command(
    name = "init",
    description = [
        "Generate a new encryption key: the symmetric aes-gcm-sym master key (the default, quantum-resistant), " +
            "or with --algorithm aes-gcm the RSA key pair of the older aes-gcm algorithm",
    ],
    mixinStandardHelpOptions = true,
)
class XtraInitCommand : Callable<Int> {
    @ParentCommand
    lateinit var xtraCmd: XtraCommand

    @Option(names = ["--algorithm"], description = ["aes-gcm-sym (default) or aes-gcm"])
    var algorithmName: String? = null

    @Option(
        names = ["--force"],
        description = ["Name the algorithm on a profile that already has sync state (files it stored under another algorithm are then refused)"],
    )
    var force: Boolean = false

    override fun call(): Int {
        val algorithm = keyVerbAlgorithm(algorithmName, XtraAlgorithm.NEW_KEY_DEFAULT) ?: return 1
        val configDir = xtraCmd.parent.configBaseDir()
        val keyPath = configDir.resolve(algorithm.keyFileName)
        if (Files.exists(keyPath)) {
            System.err.println("Key already exists at $keyPath. Use '${algorithm.changePassphraseCommand}' to update it.")
            return 1
        }
        initRefusal(configDir, xtraCmd.parent.provider, algorithm, force)?.let {
            System.err.println("Error: $it")
            return 1
        }
        try {
            if (!generate(algorithm, keyPath)) return 1
        } catch (e: IllegalStateException) {
            // Another initialization created the key file first (create-new install): its key stands, this one is gone.
            System.err.println("Error: ${e.message}")
            return 1
        }
        println(algorithmLine(algorithm))
        pinProfileAlgorithm(configDir, xtraCmd.parent.provider, algorithm)?.let(::println)
        println()
        println("WARNING: If you lose this key, encrypted files CANNOT be recovered.")
        println("Back up immediately: unidrive xtra export-key --algorithm ${algorithm.configName} --output /safe/location/")
        return 0
    }

    private fun generate(
        algorithm: XtraAlgorithm,
        keyPath: java.nio.file.Path,
    ): Boolean {
        if (algorithm == XtraAlgorithm.AES_GCM_SYM) {
            // The master key's passphrase comes from the same chain the daemon loads it with, so what is generated
            // here decrypts there by construction (as for init-age and init-tink).
            XtraSymmetricKeyStore(keyPath).generate(xtraCmd.parent.xtraPassphrase())
            println("Symmetric master key generated at $keyPath")
            return true
        }
        val console = System.console() ?: run { System.err.println("No console available"); return false }
        val pass = console.readPassword("New passphrase (min ${XtraKeyManager.MIN_PASSPHRASE_LENGTH} chars): ")
            ?: run { System.err.println("No passphrase read"); return false }
        val confirm = console.readPassword("Confirm passphrase: ")
        if (!pass.contentEquals(confirm)) { System.err.println("Passphrases don't match"); return false }
        XtraKeyManager(keyPath).generate(pass)
        println("Key pair generated at $keyPath")
        return true
    }
}

// ── xtra export-key ───────────────────────────────────────────────────────────

@Command(name = "export-key", description = ["Export the encryption key for backup"], mixinStandardHelpOptions = true)
class XtraExportCommand : Runnable {
    @ParentCommand
    lateinit var xtraCmd: XtraCommand

    @Option(names = ["--output"], required = true, description = ["Output directory (the file lands under the key file's name: xtra-sym.key or xtra.key)"])
    lateinit var output: String

    @Option(names = ["--algorithm"], description = ["aes-gcm-sym or aes-gcm (default: aes-gcm where xtra.key exists, else aes-gcm-sym)"])
    var algorithmName: String? = null

    override fun run() {
        val configDir = xtraCmd.parent.configBaseDir()
        val algorithm = keyVerbAlgorithm(algorithmName, XtraAlgorithm.keyVerbDefault(configDir)) ?: return
        val keyPath = configDir.resolve(algorithm.keyFileName)
        if (!Files.exists(keyPath)) { System.err.println("No key found at $keyPath. Run '${algorithm.initCommand}' first."); return }
        val dest = Paths.get(output).resolve(algorithm.keyFileName)
        if (Files.exists(dest)) {
            System.err.println("A file already exists at $dest; remove it or export elsewhere — an export never overwrites one.")
            return
        }
        Files.createDirectories(dest.parent)
        // The export is the backup that decrypts everything; it is written owner-only and atomically, like the
        // installation's own copy. Both key files open only under their passphrase, so the export is the file as stored.
        org.krost.unidrive.io.OwnerOnlyAtomicWrite.replace(dest, Files.readAllBytes(keyPath))
        println("Key exported to $dest")
        println(algorithmLine(algorithm))
        println("WARNING: this file decrypts everything the key encrypts — store it accordingly.")
    }
}

/**
 * The algorithm of the backup in [inputDir] when `import-key` names none: read from what the folder holds, never from
 * this installation (a clean one would otherwise look for xtra-sym.key and miss an RSA backup). Exactly one of
 * xtra-sym.key and xtra.key must be there, and its content must be that kind of key file (a symmetric key file starts
 * with `XSYM`, an RSA one never does). Null after printing why not.
 */
internal fun importAlgorithmOf(inputDir: java.nio.file.Path): XtraAlgorithm? {
    val present = listOf(XtraAlgorithm.AES_GCM_SYM, XtraAlgorithm.AES_GCM).filter { Files.isRegularFile(inputDir.resolve(it.keyFileName)) }
    if (present.isEmpty()) {
        System.err.println("Error: no X-tra key file (xtra-sym.key or xtra.key) in $inputDir; nothing was imported.")
        return null
    }
    if (present.size > 1) {
        System.err.println("Error: $inputDir holds both xtra-sym.key and xtra.key; name one with --algorithm aes-gcm-sym or --algorithm aes-gcm.")
        return null
    }
    val algorithm = present.single()
    val head = Files.newInputStream(inputDir.resolve(algorithm.keyFileName)).use { it.readNBytes(4) }
    val symmetric = head.contentEquals("XSYM".toByteArray(Charsets.US_ASCII))
    if (symmetric != (algorithm == XtraAlgorithm.AES_GCM_SYM)) {
        System.err.println(
            "Error: ${algorithm.keyFileName} in $inputDir is ${if (symmetric) "a symmetric (aes-gcm-sym)" else "not a symmetric"} key file; " +
                "its name and content disagree, nothing was imported.",
        )
        return null
    }
    return algorithm
}

// ── xtra import-key ───────────────────────────────────────────────────────────

@Command(name = "import-key", description = ["Import an encryption key from another machine"], mixinStandardHelpOptions = true)
class XtraImportCommand : Callable<Int> {
    @ParentCommand
    lateinit var xtraCmd: XtraCommand

    @Option(names = ["--input"], required = true, description = ["Directory that holds the key file (xtra-sym.key or xtra.key)"])
    lateinit var input: String

    @Option(names = ["--algorithm"], description = ["aes-gcm-sym or aes-gcm (default: the kind of key file the --input folder holds)"])
    var algorithmName: String? = null

    @Option(
        names = ["--force"],
        description = ["Replace an existing key (every file the replaced key encrypted becomes unreadable unless it was exported)"],
    )
    var force: Boolean = false

    // Whatever the probe load throws, the answer is the same: refuse, the local key stays.
    @Suppress("TooGenericExceptionCaught")
    override fun call(): Int {
        val configDir = xtraCmd.parent.configBaseDir()
        val inputDir = Paths.get(input)
        val algorithm =
            (if (algorithmName != null) keyVerbAlgorithm(algorithmName, XtraAlgorithm.NEW_KEY_DEFAULT) else importAlgorithmOf(inputDir))
                ?: return 1
        val src = inputDir.resolve(algorithm.keyFileName)
        if (!Files.exists(src)) { System.err.println("Error: no key found at $src; nothing was imported."); return 1 }
        val keyPath = configDir.resolve(algorithm.keyFileName)
        val data = Files.readAllBytes(src)
        // The passphrase the imported key must work under is this installation's — the daemon loads the imported
        // key with xtraPassphrase(), so it is resolved here, before the probe, where its own error stands.
        val passphrase = xtraCmd.parent.xtraPassphrase()
        // Prove the import is a key before it can replace one: it must load from a temp path, so a wrong file
        // fails here with the installation's key untouched.
        val probe = Files.createTempFile("xtra-import-", ".key")
        try {
            Files.write(probe, data)
            loadKeyFile(algorithm, probe, passphrase)
        } catch (e: Exception) {
            System.err.println("The imported file is not a usable X-tra key (${e.javaClass.simpleName}: ${e.message}); the local key is untouched.")
            return 1
        } finally {
            Files.deleteIfExists(probe)
        }
        try {
            when (algorithm) {
                XtraAlgorithm.AES_GCM_SYM -> XtraSymmetricKeyStore(keyPath).importKey(data, force)
                else -> XtraKeyManager(keyPath).importKey(data, force)
            }
        } catch (e: IllegalStateException) {
            System.err.println("Error: ${e.message}")
            return 1
        } catch (e: IllegalArgumentException) {
            System.err.println("Error: ${e.message}")
            return 1
        }
        println("Key imported from $src")
        println(algorithmLine(algorithm))
        return 0
    }
}

// ── xtra change-passphrase ────────────────────────────────────────────────────

@Command(name = "change-passphrase", description = ["Change the encryption key's passphrase (the key itself is unchanged)"], mixinStandardHelpOptions = true)
class XtraChangePassphraseCommand : Callable<Int> {
    @ParentCommand
    lateinit var xtraCmd: XtraCommand

    @Option(names = ["--algorithm"], description = ["aes-gcm-sym or aes-gcm (default: aes-gcm where xtra.key exists, else aes-gcm-sym)"])
    var algorithmName: String? = null

    override fun call(): Int {
        val configDir = xtraCmd.parent.configBaseDir()
        val algorithm = keyVerbAlgorithm(algorithmName, XtraAlgorithm.keyVerbDefault(configDir)) ?: return 1
        val keyPath = configDir.resolve(algorithm.keyFileName)
        if (!Files.exists(keyPath)) { System.err.println("No key found at $keyPath. Run '${algorithm.initCommand}' first."); return 0 }
        val console = System.console() ?: run { System.err.println("No console available"); return 0 }
        val current = console.readPassword("Current passphrase: ") ?: return 0
        val rewrap: (CharArray) -> Unit =
            when (algorithm) {
                XtraAlgorithm.AES_GCM_SYM -> XtraSymmetricKeyStore(keyPath).also { it.load(current) }::changePassphrase
                else -> XtraKeyManager(keyPath).also { it.load(current) }::changePassphrase
            }
        val newPass = console.readPassword("New passphrase (min ${XtraKeyManager.MIN_PASSPHRASE_LENGTH} chars): ")
            ?: return 0
        val confirm = console.readPassword("Confirm passphrase: ")
        if (!newPass.contentEquals(confirm)) { System.err.println("Passphrases don't match"); return 0 }
        // Re-wraps the same key under the new passphrase; a fresh generate would orphan every file already
        // encrypted with the old key.
        val newForVault = newPass.copyOf()
        rewrap(newPass)
        println("Passphrase changed successfully.")
        XtraAlgorithm.sharedPassphraseReminders(keyPath.parent, algorithm).forEach(::println)
        return try {
            xtraCmd.parent.updateVaultXtraAfterChange(newForVault)
        } finally {
            newForVault.fill('\u0000')
        }
    }
}

// ── xtra init-age ─────────────────────────────────────────────────────────────

@Command(name = "init-age", description = ["Generate the age-v1 encryption identity (xtra_algorithm = \"age-v1\") up front"], mixinStandardHelpOptions = true)
class XtraInitAgeCommand : Runnable {
    @ParentCommand
    lateinit var xtraCmd: XtraCommand

    override fun run() {
        val keyPath = xtraCmd.parent.configBaseDir().resolve("xtra-age.key")
        if (Files.exists(keyPath)) {
            System.err.println("An identity already exists at $keyPath. Use 'xtra change-passphrase-age' to update it.")
            return
        }
        // The identity's passphrase comes from the same chain the daemon loads it with, so what is generated here
        // decrypts there by construction — this is the first content operation's silent generate, made explicit.
        XtraAgeKeyStore(keyPath).generate(xtraCmd.parent.xtraPassphrase())
        println("Age identity generated at $keyPath")
        println()
        println("WARNING: If you lose this identity, age-encrypted files CANNOT be recovered.")
        println("Back up immediately: unidrive xtra export-age --output /safe/location/")
    }
}

// ── xtra export-age ───────────────────────────────────────────────────────────

@Command(
    name = "export-age",
    description = ["Export the age identity for backup (standard age format; age decrypts the age file after the stored object's 266-byte XTRA header)"],
    mixinStandardHelpOptions = true,
)
class XtraExportAgeCommand : Runnable {
    @ParentCommand
    lateinit var xtraCmd: XtraCommand

    @Option(names = ["--output"], required = true, description = ["Output directory (the file lands as xtra-age-identity.txt)"])
    lateinit var output: String

    override fun run() {
        val keyPath = xtraCmd.parent.configBaseDir().resolve("xtra-age.key")
        if (!Files.exists(keyPath)) { System.err.println("No age identity found. Run 'unidrive xtra init-age' first."); return }
        val dest = Paths.get(output).resolve("xtra-age-identity.txt")
        if (Files.exists(dest)) {
            System.err.println("A file already exists at $dest; remove it or export elsewhere — an export never overwrites one.")
            return
        }
        Files.createDirectories(dest.parent)
        // The export is the identity itself — the file 'age -d -i' takes — so it is written owner-only and atomically,
        // like the installation's own copy.
        org.krost.unidrive.io.OwnerOnlyAtomicWrite.replace(dest, XtraAgeKeyStore(keyPath).exportIdentity(xtraCmd.parent.xtraPassphrase()).toByteArray())
        println("Age identity exported to $dest")
        println("WARNING: this file decrypts everything the identity encrypts — store it accordingly.")
        println("It is the standard age identity. age -d -i '$dest' decrypts the age file that begins after the stored object's 266-byte XTRA header, not the raw object.")
    }
}

// ── xtra import-age ───────────────────────────────────────────────────────────

@Command(name = "import-age", description = ["Import an age identity from another machine"], mixinStandardHelpOptions = true)
class XtraImportAgeCommand : Runnable {
    @ParentCommand
    lateinit var xtraCmd: XtraCommand

    @Option(names = ["--input"], required = true, description = ["Directory that holds xtra-age-identity.txt"])
    lateinit var input: String

    @Option(
        names = ["--force"],
        description = ["Replace an existing identity (every file the replaced identity encrypted becomes unreadable unless it was exported)"],
    )
    var force: Boolean = false

    // Whatever the probe decode throws, the answer is the same: refuse, the local identity stays.
    @Suppress("TooGenericExceptionCaught")
    override fun run() {
        val src = Paths.get(input).resolve("xtra-age-identity.txt")
        if (!Files.exists(src)) { System.err.println("No identity file found at $src"); return }
        val keyPath = xtraCmd.parent.configBaseDir().resolve("xtra-age.key")
        val encoded = Files.readString(src).trim()
        // The identity string carries no passphrase; the envelope it lands in uses this installation's — resolved
        // here, before the probe, where its own error stands.
        val passphrase = xtraCmd.parent.xtraPassphrase()
        val store = XtraAgeKeyStore(keyPath)
        // Prove the import is an identity before it can replace one: a wrong file fails here with the local
        // identity untouched.
        try {
            store.probeIdentity(encoded)
        } catch (e: Exception) {
            System.err.println("The imported file is not a usable age identity (${e.javaClass.simpleName}: ${e.message}); the local identity is untouched.")
            return
        }
        store.importIdentity(encoded, passphrase, force)
        println("Age identity imported from $src")
    }
}

// ── xtra change-passphrase-age ────────────────────────────────────────────────

@Command(name = "change-passphrase-age", description = ["Change the age identity's passphrase (the identity itself is unchanged)"], mixinStandardHelpOptions = true)
class XtraChangePassphraseAgeCommand : Callable<Int> {
    @ParentCommand
    lateinit var xtraCmd: XtraCommand

    override fun call(): Int {
        val keyPath = xtraCmd.parent.configBaseDir().resolve("xtra-age.key")
        if (!Files.exists(keyPath)) { System.err.println("No age identity found. Run 'unidrive xtra init-age' first."); return 0 }
        val console = System.console() ?: run { System.err.println("No console available"); return 0 }
        val current = console.readPassword("Current passphrase: ") ?: return 0
        val newPass = console.readPassword("New passphrase (min ${XtraKeyManager.MIN_PASSPHRASE_LENGTH} chars): ")
            ?: return 0
        val confirm = console.readPassword("Confirm passphrase: ")
        if (!newPass.contentEquals(confirm)) { System.err.println("Passphrases don't match"); return 0 }
        // Re-wraps the same identity under the new passphrase; a fresh generate would orphan every file already
        // encrypted to the old one.
        val newForVault = newPass.copyOf()
        XtraAgeKeyStore(keyPath).changePassphrase(current, newPass)
        println("Passphrase changed successfully.")
        XtraAlgorithm.sharedPassphraseReminders(keyPath.parent, XtraAlgorithm.AGE_V1).forEach(::println)
        return try {
            xtraCmd.parent.updateVaultXtraAfterChange(newForVault)
        } finally {
            newForVault.fill('\u0000')
        }
    }
}

// ── xtra init-tink ────────────────────────────────────────────────────────────

@Command(name = "init-tink", description = ["Generate the tink-aes-gcm encryption keyset (xtra_algorithm = \"tink-aes-gcm\") up front"], mixinStandardHelpOptions = true)
class XtraInitTinkCommand : Runnable {
    @ParentCommand
    lateinit var xtraCmd: XtraCommand

    override fun run() {
        val keyPath = xtraCmd.parent.configBaseDir().resolve(XtraAlgorithm.TINK_AES_GCM.keyFileName)
        if (Files.exists(keyPath)) {
            System.err.println("A keyset already exists at $keyPath. Use 'xtra change-passphrase-tink' to update it.")
            return
        }
        // The keyset's passphrase comes from the same chain the daemon loads it with, so what is generated here
        // decrypts there by construction — the same up-front generate the other algorithms' init verbs make.
        XtraTinkKeyStore(keyPath).generate(xtraCmd.parent.xtraPassphrase())
        println("Tink keyset generated at $keyPath")
        println()
        println("WARNING: If you lose this keyset, tink-encrypted files CANNOT be recovered.")
        println("Unlike age-v1 there is no external recovery tool: only this engine's keyset decrypts the stored files.")
        println("Back up xtra-tink.key itself (it decrypts only under its passphrase).")
    }
}

// ── xtra change-passphrase-tink ───────────────────────────────────────────────

@Command(name = "change-passphrase-tink", description = ["Change the tink keyset's passphrase (the keyset itself is unchanged)"], mixinStandardHelpOptions = true)
class XtraChangePassphraseTinkCommand : Callable<Int> {
    @ParentCommand
    lateinit var xtraCmd: XtraCommand

    override fun call(): Int {
        val keyPath = xtraCmd.parent.configBaseDir().resolve(XtraAlgorithm.TINK_AES_GCM.keyFileName)
        if (!Files.exists(keyPath)) { System.err.println("No tink keyset found. Run 'unidrive xtra init-tink' first."); return 0 }
        val console = System.console() ?: run { System.err.println("No console available"); return 0 }
        val current = console.readPassword("Current passphrase: ") ?: return 0
        val newPass = console.readPassword("New passphrase (min ${XtraKeyManager.MIN_PASSPHRASE_LENGTH} chars): ")
            ?: return 0
        val confirm = console.readPassword("Confirm passphrase: ")
        if (!newPass.contentEquals(confirm)) { System.err.println("Passphrases don't match"); return 0 }
        // Re-wraps the same keyset under the new passphrase; a fresh generate would orphan every file it sealed.
        val newForVault = newPass.copyOf()
        XtraTinkKeyStore(keyPath).changePassphrase(current, newPass)
        println("Passphrase changed successfully.")
        XtraAlgorithm.sharedPassphraseReminders(keyPath.parent, XtraAlgorithm.TINK_AES_GCM).forEach(::println)
        return try {
            xtraCmd.parent.updateVaultXtraAfterChange(newForVault)
        } finally {
            newForVault.fill('\u0000')
        }
    }
}
