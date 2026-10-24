package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.util.concurrent.Callable

/*
 * The non-interactive halves of `unidrive auth`, for scripts and front-ends: JSON on stdout, no
 * console, no prompt, no daemon started. Secrets never travel through the argument list: the
 * device-code flow needs none (the user signs in on the provider's page), and the Internxt password
 * is read from standard input only. Nothing here prints, logs or returns a password, a TOTP code,
 * a device code or a token.
 *
 * A profile held by a running daemon is not refused (#813): each step is handed to that daemon over its
 * authenticated IPC socket (`auth.begin`, `auth.complete`, `auth.login`, docs/dev/specs/unidrive-daemon-design.md
 * §4.11), which runs the same step (AuthSteps.kt), stores the credentials and reloads them in place, so the mount
 * keeps running. A secret the step needs travels in the request body only.
 */

internal fun reply(
    json: Boolean,
    block: () -> JsonObject,
): Int =
    try {
        val result = block()
        if (json) {
            emitJson(result)
        } else {
            println(result.toString())
        }
        0
    } catch (e: LifecycleError) {
        if (json) emitJson(lifecycleErrorJson(e)) else System.err.println("Error: ${e.message}")
        1
    } catch (e: Exception) {
        // A front-end parses stdout: whatever else goes wrong is still one JSON refusal, with the
        // exception's type only (its message could quote a response or a path).
        val refusal = LifecycleError("internal_error", "unexpected failure: ${e.javaClass.simpleName}")
        if (json) emitJson(lifecycleErrorJson(refusal)) else System.err.println("Error: ${refusal.message}")
        1
    }

// ── auth begin ───────────────────────────────────────────────────────────────

@Command(
    name = "begin",
    description = [
        "Start a device-code sign-in (OneDrive): prints the code and the page to enter it on. " +
            "Finish with 'auth complete --handle ...'. Starts no daemon; a running daemon of the profile runs the step itself.",
    ],
    mixinStandardHelpOptions = true,
)
class AuthBeginCommand : Callable<Int> {
    @ParentCommand
    lateinit var authCmd: AuthCommand

    @Option(names = ["--profile"], description = ["Profile (default: -p, else the default profile)"])
    var profile: String? = null

    @Option(names = ["--json"], description = ["Print the result (or the refusal) as one JSON object on stdout"])
    var json: Boolean = false

    override fun call(): Int =
        reply(json) {
            val target = resolveProfileTarget(authCmd.parent, profile)
            val factory = deviceCodeFactory(target, hint = "; for Internxt use 'auth login --password-stdin'")
            withProfileLockOrDaemon(
                target,
                viaDaemon = { endpoint -> askDaemonToSignIn(target, endpoint, buildJsonObject { put("verb", AuthLoginRpcHandler.BEGIN) }) },
            ) {
                runBlocking { beginSignIn(target, factory) }
            }
        }
}

// ── auth complete ────────────────────────────────────────────────────────────

@Command(
    name = "complete",
    description = [
        "Finish a device-code sign-in begun with 'auth begin': one poll (status authenticated or pending), " +
            "or with --wait until it ends. Starts no daemon; a running daemon of the profile runs the step itself.",
    ],
    mixinStandardHelpOptions = true,
)
class AuthCompleteCommand : Callable<Int> {
    @ParentCommand
    lateinit var authCmd: AuthCommand

    @Option(names = ["--profile"], description = ["Profile (default: -p, else the default profile)"])
    var profile: String? = null

    @Option(names = ["--handle"], required = true, description = ["The handle 'auth begin' returned"])
    lateinit var handle: String

    @Option(names = ["--wait"], description = ["Keep polling until the sign-in succeeds, fails or expires"])
    var wait: Boolean = false

    // #866: the code a no-redirect flow (Dropbox) shows the user. A one-time secret, so it is piped,
    // never an argument (precedent: auth login --password-stdin).
    @Option(names = ["--code-stdin"], description = ["Read the authorization code the provider showed from standard input"])
    var codeStdin: Boolean = false

    @Option(names = ["--json"], description = ["Print the result (or the refusal) as one JSON object on stdout"])
    var json: Boolean = false

    override fun call(): Int =
        reply(json) {
            val target = resolveProfileTarget(authCmd.parent, profile)
            val factory = deviceCodeFactory(target)
            // A piped code completes in one call: no polling, and the code never reaches argv or a log.
            val secret = if (codeStdin) readCode() else null
            withProfileLockOrDaemon(
                target,
                // A daemon that stops answering between two polls leaves the rest to this process (the pending flow is
                // kept in the profile folder, so the handle stays good).
                viaDaemon = daemon@{ endpoint ->
                    pollUntilDone(secret) { askDaemonToSignIn(target, endpoint, completeRequest(secret)) ?: return@daemon null }
                },
            ) {
                pollUntilDone(secret) { runBlocking { completeSignInOnce(target, factory, handle, secret) } }
            }
        }

    private fun readCode(): String {
        val read = generateSequence { readLine() }.firstOrNull() ?: ""
        return read.trim().takeIf { it.isNotEmpty() }
            ?: throw LifecycleError("missing_code", "--code-stdin was given but standard input held no code")
    }

    private fun completeRequest(secret: String?): JsonObject =
        buildJsonObject {
            put("verb", AuthLoginRpcHandler.COMPLETE)
            put("handle", handle)
            secret?.let { put("code", it) }
        }

    /** One poll, or with --wait (and no piped code) polls until the sign-in is no longer pending. */
    private inline fun pollUntilDone(
        secret: String?,
        pollOnce: () -> JsonObject,
    ): JsonObject {
        var outcome = pollOnce()
        while (wait && secret == null && outcome.isPendingSignIn()) {
            LifecycleHooks.sleepSeconds(outcome.retryAfterSeconds())
            outcome = pollOnce()
        }
        return outcome
    }
}

// ── auth login (Internxt) ────────────────────────────────────────────────────

@Command(
    name = "login",
    description = [
        "Sign in to Internxt without a console. The password is read from standard input (first line), " +
            "or asked for without echo when standard input and output are a terminal; " +
            "never from an argument. Starts no daemon; a running daemon of the profile signs in itself.",
    ],
    mixinStandardHelpOptions = true,
)
class AuthLoginCommand : Callable<Int> {
    @ParentCommand
    lateinit var authCmd: AuthCommand

    @Option(names = ["--profile"], description = ["Profile (default: -p, else the default profile)"])
    var profile: String? = null

    @Option(names = ["--email"], required = true, description = ["Account e-mail"])
    lateinit var email: String

    @Option(names = ["--password-stdin"], description = ["Read the password from the first line of standard input, or without echo at a terminal (required)"])
    var passwordStdin: Boolean = false

    @Option(names = ["--totp"], description = ["Current two-factor code, when the account has one"])
    var totp: String? = null

    @Option(names = ["--json"], description = ["Print the result (or the refusal) as one JSON object on stdout"])
    var json: Boolean = false

    override fun call(): Int =
        reply(json) {
            if (!passwordStdin) {
                throw LifecycleError("password_stdin_required", "pass --password-stdin and give the password on standard input")
            }
            if (email.isBlank()) throw LifecycleError("missing_email", "--email is required")
            val target = resolveProfileTarget(authCmd.parent, profile)
            requireInternxtProfile(target) // before the password is asked for
            val password = readPasswordLine()
            requireInternxtLogin(target, email, password)
            val code = totp?.trim()?.takeIf { it.isNotEmpty() }

            withProfileLockOrDaemon(
                target,
                viaDaemon = { endpoint ->
                    // The verb first: a line the daemon cannot dispatch is logged by its first characters only.
                    val request =
                        buildJsonObject {
                            put("verb", AuthLoginRpcHandler.LOGIN)
                            put("email", email.trim())
                            code?.let { put("totp", it) }
                            put("password", password)
                        }
                    askDaemonToSignIn(target, endpoint, request)
                },
            ) {
                runBlocking { loginInternxt(target, email, password, code) }
            }
        }

    /**
     * On a terminal: the prompt on stderr (stdout stays clean for --json) and a masked read. Otherwise the
     * first line of standard input without its line terminator, no prompt. Spaces in the password are kept.
     */
    private fun readPasswordLine(): String {
        val terminal = LifecycleHooks.terminalPassword()
        if (terminal != null) {
            System.err.print("Password: ")
            System.err.flush()
            val typed = terminal.readPassword() ?: return ""
            return String(typed).also { typed.fill('\u0000') }
        }
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = System.`in`.read()
            if (b < 0 || b == '\n'.code) break
            bytes.write(b)
        }
        return bytes.toString(Charsets.UTF_8).removeSuffix("\r")
    }
}
