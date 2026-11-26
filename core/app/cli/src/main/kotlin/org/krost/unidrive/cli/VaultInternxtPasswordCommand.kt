package org.krost.unidrive.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.krost.unidrive.AccountName
import org.krost.unidrive.sync.Vault
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.util.concurrent.Callable

/*
 * gkrost/unidrive-cli#861 (Windows Hello P5): `vault internxt-password-set` / `-clear` keep an Internxt profile's sign-in in
 * the vault, opt-in, so the engine signs an expired session in again by itself (see InternxtVaultPassword.kt). Secrets
 * come from standard input or a masked prompt, never from an argument. A profile held by a running daemon is not
 * refused: the change is handed to that daemon (`vault.internxt_password.set` / `.clear`), which writes the vault and
 * keeps the new data in memory, so the next expired session already uses it. No command reads the password back.
 */

private fun readStdinLine(): String {
    val bytes = java.io.ByteArrayOutputStream()
    while (true) {
        val b = System.`in`.read()
        if (b < 0 || b == '\n'.code) break
        bytes.write(b)
    }
    return bytes.toString(Charsets.UTF_8).removeSuffix("\r")
}

/** #845: a `--json` run never opens a prompt; the secret must arrive on standard input. */
private fun requireStdinWhenJson(
    json: Boolean,
    given: Boolean,
    token: String,
    flag: String,
) {
    if (json && !given) throw LifecycleError(token, "--json never prompts: give $flag", exitCode = 78)
}

private fun vaultPassphrase(
    stdin: Boolean,
    json: Boolean,
): CharArray = if (stdin) readPassphraseStdin("--passphrase-stdin", json) else readPassphrase("Vault passphrase: ")

/** The Internxt profile and its vault, or the refusal. */
private fun internxtVaultTarget(
    main: Main,
    profile: String?,
): Pair<ProfileTarget, Vault> {
    val target = resolveProfileTarget(main, profile)
    requireInternxtProfile(target)
    val vault = Vault(main.configBaseDir().resolve("vault.enc"))
    if (!vault.exists()) throw LifecycleError("vault_absent", "no vault found; run 'unidrive vault init' first")
    return target to vault
}

/** One rewrite of [vault] by this process, under the config lock every vault writer takes; a wrong passphrase writes nothing. */
private fun rewriteVault(
    main: Main,
    vault: Vault,
    passphrase: CharArray,
    change: (Map<String, Map<String, String>>) -> Map<String, Map<String, String>>,
) {
    try {
        ConfigFileTransaction.withLock(main.configBaseDir().resolve("config.toml")) {
            vault.write(passphrase, change(vault.read(passphrase)))
        }
    } catch (e: javax.crypto.AEADBadTagException) {
        throw LifecycleError("vault_bad_passphrase", "wrong vault passphrase", e)
    }
}

private fun daemonRequest(
    verb: String,
    passphrase: CharArray,
    extra: Map<String, String> = emptyMap(),
): JsonObject =
    buildJsonObject {
        // The verb first: a line the daemon cannot dispatch is logged by its first characters only.
        put("verb", verb)
        for ((k, v) in extra) put(k, v)
        put("passphrase", String(passphrase))
    }

@Command(
    name = "internxt-password-set",
    description = [
        "Keep the Internxt password of a profile in the vault (opt-in), so an expired session is signed in again without " +
            "asking; an account with a two-factor code is not, and reports totp_required. The password is read from standard " +
            "input with --password-stdin (else a masked prompt), never from an argument. No command reads it back.",
    ],
    mixinStandardHelpOptions = true,
)
class VaultInternxtPasswordSetCommand : Callable<Int> {
    @ParentCommand
    lateinit var vaultCmd: VaultCommand

    @Option(names = ["--profile"], description = ["Profile (default: -p, else the default profile)"])
    var profile: String? = null

    @Option(names = ["--passphrase-stdin"], description = ["Read the vault passphrase from the first line of standard input"])
    var passphraseStdin: Boolean = false

    @Option(names = ["--email"], description = ["Account e-mail (default: the one the last 'auth login' stored)"])
    var email: String? = null

    @Option(names = ["--email-stdin"], description = ["Read the account e-mail from standard input: the line after the vault passphrase"])
    var emailStdin: Boolean = false

    @Option(names = ["--password-stdin"], description = ["Read the Internxt password from standard input: the line after the e-mail"])
    var passwordStdin: Boolean = false

    @Option(names = ["--json"], description = ["Print the result (or the refusal) as one JSON object on stdout"])
    var json: Boolean = false

    override fun call(): Int =
        reply(json) {
            if (email != null && emailStdin) throw LifecycleError("invalid_value", "give --email or --email-stdin, not both")
            requireStdinWhenJson(json, passphraseStdin, "passphrase_stdin_required", "--passphrase-stdin")
            requireStdinWhenJson(json, passwordStdin, "password_stdin_required", "--password-stdin")
            val main = vaultCmd.parent
            val (target, vault) = internxtVaultTarget(main, profile)
            // One line each, in this order: vault passphrase, e-mail, password.
            val passphrase = vaultPassphrase(passphraseStdin, json)
            try {
                val account =
                    (if (emailStdin) readStdinLine() else email ?: AccountName.read(target.profileDir))?.trim()?.takeIf { it.isNotEmpty() }
                        ?: throw LifecycleError("missing_email", "no e-mail: give --email or --email-stdin, or sign in once with 'auth login'")
                val password = if (passwordStdin) readStdinLine() else readPassphrase("Internxt password: ").let { String(it).also { _ -> it.fill('\u0000') } }
                if (password.isEmpty()) throw LifecycleError("empty_password", "no password on standard input")
                val request = daemonRequest(VaultRpcHandler.INTERNXT_PASSWORD_SET, passphrase, mapOf("email" to account, "password" to password))
                withProfileLockOrDaemon(target, viaDaemon = { endpoint -> askDaemonToSignIn(target, endpoint, request)?.let { stored(target) } }) {
                    rewriteVault(main, vault, passphrase) { withVaultInternxtPassword(it, target.name, account, password) }
                    stored(target)
                }
            } finally {
                passphrase.fill('\u0000')
            }
        }

    private fun stored(target: ProfileTarget): JsonObject =
        buildJsonObject {
            put("ok", true)
            put("profile", target.name)
            put("stored", true)
        }
}

@Command(
    name = "internxt-password-clear",
    description = ["Remove the Internxt password of a profile from the vault (the option is off again)"],
    mixinStandardHelpOptions = true,
)
class VaultInternxtPasswordClearCommand : Callable<Int> {
    @ParentCommand
    lateinit var vaultCmd: VaultCommand

    @Option(names = ["--profile"], description = ["Profile (default: -p, else the default profile)"])
    var profile: String? = null

    @Option(names = ["--passphrase-stdin"], description = ["Read the vault passphrase from the first line of standard input"])
    var passphraseStdin: Boolean = false

    @Option(names = ["--json"], description = ["Print the result (or the refusal) as one JSON object on stdout"])
    var json: Boolean = false

    override fun call(): Int =
        reply(json) {
            requireStdinWhenJson(json, passphraseStdin, "passphrase_stdin_required", "--passphrase-stdin")
            val main = vaultCmd.parent
            val (target, vault) = internxtVaultTarget(main, profile)
            val passphrase = vaultPassphrase(passphraseStdin, json)
            try {
                val request = daemonRequest(VaultRpcHandler.INTERNXT_PASSWORD_CLEAR, passphrase)
                withProfileLockOrDaemon(
                    target,
                    viaDaemon = { endpoint ->
                        askDaemonToSignIn(target, endpoint, request)?.let { cleared(target, (it["removed"] as? JsonPrimitive)?.content == "true") }
                    },
                ) {
                    var removed = false
                    rewriteVault(main, vault, passphrase) { data ->
                        removed = internxtPasswordSection(target.name) in data
                        withoutVaultInternxtPassword(data, target.name)
                    }
                    cleared(target, removed)
                }
            } finally {
                passphrase.fill('\u0000')
            }
        }

    private fun cleared(
        target: ProfileTarget,
        removed: Boolean,
    ): JsonObject =
        buildJsonObject {
            put("ok", true)
            put("profile", target.name)
            put("removed", removed)
        }
}
