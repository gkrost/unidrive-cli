package org.krost.unidrive.cli

import org.krost.unidrive.xtra.XtraKeyManager
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Key management of the X-tra layer (client-side encryption of a profile's file content, config key
 * `xtra_encryption`). One key per installation, beside config.toml: export it to a second machine with `export-key`
 * and `import-key`; a lost key loses the encrypted content — nothing else can decrypt it.
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
    ],
)
class XtraCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    override fun run() {
        println("Usage: unidrive xtra <init|export-key|import-key|change-passphrase>")
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
        Files.createDirectories(dest.parent)
        Files.write(dest, XtraKeyManager(keyPath).exportKey())
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

    override fun run() {
        val src = Paths.get(input).resolve("xtra.key")
        if (!Files.exists(src)) { System.err.println("No key found at $src"); return }
        XtraKeyManager(xtraCmd.parent.configBaseDir().resolve("xtra.key")).importKey(Files.readAllBytes(src))
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
