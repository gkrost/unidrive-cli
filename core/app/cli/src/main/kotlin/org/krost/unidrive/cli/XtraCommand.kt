package org.krost.unidrive.cli

import org.krost.unidrive.xtra.XtraAgeKeyStore
import org.krost.unidrive.xtra.XtraKeyManager
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Key management of the X-tra layer (client-side encryption of a profile's file content, config key
 * `xtra_encryption`). One key per installation, beside config.toml: export it to a second machine with `export-key`
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
    ],
)
class XtraCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    override fun run() {
        println("Usage: unidrive xtra <init|export-key|import-key|change-passphrase|init-age|export-age|import-age|change-passphrase-age|init-tink>")
    }
}

// ── xtra init ─────────────────────────────────────────────────────────────────

@Command(name = "init", description = ["Generate a new encryption key pair"], mixinStandardHelpOptions = true)
class XtraInitCommand : Runnable {
    @ParentCommand
    lateinit var xtraCmd: XtraCommand

    override fun run() {
        val keyPath = xtraCmd.parent.configBaseDir().resolve("xtra.key")
        if (Files.exists(keyPath)) {
            System.err.println("Key already exists at $keyPath. Use 'xtra change-passphrase' to update it.")
            return
        }
        val console = System.console() ?: run { System.err.println("No console available"); return }
        val pass = console.readPassword("New passphrase (min ${XtraKeyManager.MIN_PASSPHRASE_LENGTH} chars): ")
            ?: run { System.err.println("No passphrase read"); return }
        val confirm = console.readPassword("Confirm passphrase: ")
        if (!pass.contentEquals(confirm)) { System.err.println("Passphrases don't match"); return }
        XtraKeyManager(keyPath).generate(pass)
        println("Key pair generated at $keyPath")
        println()
        println("WARNING: If you lose this key, encrypted files CANNOT be recovered.")
        println("Back up immediately: unidrive xtra export-key --output /safe/location/")
    }
}

// ── xtra export-key ───────────────────────────────────────────────────────────

@Command(name = "export-key", description = ["Export the encryption key for backup"], mixinStandardHelpOptions = true)
class XtraExportCommand : Runnable {
    @ParentCommand
    lateinit var xtraCmd: XtraCommand

    @Option(names = ["--output"], required = true, description = ["Output directory (the file lands as xtra.key)"])
    lateinit var output: String

    override fun run() {
        val keyPath = xtraCmd.parent.configBaseDir().resolve("xtra.key")
        if (!Files.exists(keyPath)) { System.err.println("No key found. Run 'unidrive xtra init' first."); return }
        val dest = Paths.get(output).resolve("xtra.key")
        if (Files.exists(dest)) {
            System.err.println("A file already exists at $dest; remove it or export elsewhere — an export never overwrites one.")
            return
        }
        Files.createDirectories(dest.parent)
        // The export is the backup that decrypts everything; it is written owner-only and atomically, like the
        // installation's own copy.
        org.krost.unidrive.io.OwnerOnlyAtomicWrite.replace(dest, XtraKeyManager(keyPath).exportKey())
        println("Key exported to $dest")
        println("WARNING: this file decrypts everything the key encrypts — store it accordingly.")
    }
}

// ── xtra import-key ───────────────────────────────────────────────────────────

@Command(name = "import-key", description = ["Import an encryption key from another machine"], mixinStandardHelpOptions = true)
class XtraImportCommand : Runnable {
    @ParentCommand
    lateinit var xtraCmd: XtraCommand

    @Option(names = ["--input"], required = true, description = ["Directory that holds xtra.key"])
    lateinit var input: String

    @Option(
        names = ["--force"],
        description = ["Replace an existing key (every file the replaced key encrypted becomes unreadable unless it was exported)"],
    )
    var force: Boolean = false

    // Whatever the probe load throws, the answer is the same: refuse, the local key stays.
    @Suppress("TooGenericExceptionCaught")
    override fun run() {
        val src = Paths.get(input).resolve("xtra.key")
        if (!Files.exists(src)) { System.err.println("No key found at $src"); return }
        val keyPath = xtraCmd.parent.configBaseDir().resolve("xtra.key")
        val data = Files.readAllBytes(src)
        // The passphrase the imported key must work under is this installation's — the daemon loads the imported
        // key with xtraPassphrase(), so it is resolved here, before the probe, where its own error stands.
        val passphrase = xtraCmd.parent.xtraPassphrase()
        // Prove the import is a key before it can replace one: it must load from a temp path, so a wrong file
        // fails here with the installation's key untouched.
        val probe = Files.createTempFile("xtra-import-", ".key")
        try {
            Files.write(probe, data)
            XtraKeyManager(probe).load(passphrase)
        } catch (e: Exception) {
            System.err.println("The imported file is not a usable X-tra key (${e.javaClass.simpleName}: ${e.message}); the local key is untouched.")
            return
        } finally {
            Files.deleteIfExists(probe)
        }
        XtraKeyManager(keyPath).importKey(data, force)
        println("Key imported from $src")
    }
}

// ── xtra change-passphrase ────────────────────────────────────────────────────

@Command(name = "change-passphrase", description = ["Change the encryption key's passphrase"], mixinStandardHelpOptions = true)
class XtraChangePassphraseCommand : Runnable {
    @ParentCommand
    lateinit var xtraCmd: XtraCommand

    override fun run() {
        val keyPath = xtraCmd.parent.configBaseDir().resolve("xtra.key")
        if (!Files.exists(keyPath)) { System.err.println("No key found. Run 'unidrive xtra init' first."); return }
        val console = System.console() ?: run { System.err.println("No console available"); return }
        val km = XtraKeyManager(keyPath)
        km.load(console.readPassword("Current passphrase: ") ?: return)
        val newPass = console.readPassword("New passphrase (min ${XtraKeyManager.MIN_PASSPHRASE_LENGTH} chars): ")
            ?: return
        val confirm = console.readPassword("Confirm passphrase: ")
        if (!newPass.contentEquals(confirm)) { System.err.println("Passphrases don't match"); return }
        // Re-wraps the same key pair under the new passphrase; a fresh generate would orphan every file already
        // encrypted with the old key.
        km.changePassphrase(newPass)
        println("Passphrase changed successfully.")
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
class XtraChangePassphraseAgeCommand : Runnable {
    @ParentCommand
    lateinit var xtraCmd: XtraCommand

    override fun run() {
        val keyPath = xtraCmd.parent.configBaseDir().resolve("xtra-age.key")
        if (!Files.exists(keyPath)) { System.err.println("No age identity found. Run 'unidrive xtra init-age' first."); return }
        val console = System.console() ?: run { System.err.println("No console available"); return }
        val current = console.readPassword("Current passphrase: ") ?: return
        val newPass = console.readPassword("New passphrase (min ${XtraKeyManager.MIN_PASSPHRASE_LENGTH} chars): ")
            ?: return
        val confirm = console.readPassword("Confirm passphrase: ")
        if (!newPass.contentEquals(confirm)) { System.err.println("Passphrases don't match"); return }
        // Re-wraps the same identity under the new passphrase; a fresh generate would orphan every file already
        // encrypted to the old one.
        XtraAgeKeyStore(keyPath).changePassphrase(current, newPass)
        println("Passphrase changed successfully.")
    }
}

// ── xtra init-tink ────────────────────────────────────────────────────────────

@Command(name = "init-tink", description = ["Generate the tink-aes-gcm encryption keyset (xtra_algorithm = \"tink-aes-gcm\") up front"], mixinStandardHelpOptions = true)
class XtraInitTinkCommand : Runnable {
    @ParentCommand
    lateinit var xtraCmd: XtraCommand

    override fun run() {
        val keyPath = xtraCmd.parent.configBaseDir().resolve("xtra-tink.key")
        if (Files.exists(keyPath)) {
            System.err.println("A keyset already exists at $keyPath.")
            return
        }
        // The keyset's passphrase comes from the same chain the daemon loads it with, so what is generated here
        // decrypts there by construction — the same up-front generate the other algorithms' init verbs make.
        org.krost.unidrive.xtra.XtraTinkKeyStore(keyPath).generate(xtraCmd.parent.xtraPassphrase())
        println("Tink keyset generated at $keyPath")
        println()
        println("WARNING: If you lose this keyset, tink-encrypted files CANNOT be recovered.")
        println("Unlike age-v1 there is no external recovery tool: only this engine's keyset decrypts the stored files.")
        println("Back up xtra-tink.key itself (it decrypts only under its passphrase).")
    }
}
