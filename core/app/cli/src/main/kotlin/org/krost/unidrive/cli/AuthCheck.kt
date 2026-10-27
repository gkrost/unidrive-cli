package org.krost.unidrive.cli

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.CredentialProof
import org.krost.unidrive.NotSignedInException
import org.krost.unidrive.ProviderException
import org.slf4j.LoggerFactory
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.util.concurrent.Callable

/*
 * `auth check` (archive#1240): proves the stored sign-in of a profile against the provider with ONE authenticated call
 * (CloudProvider.checkCredentials), without a daemon start, a scan, a sync or a write to state.db. It never signs in:
 * no browser, no console, no device code, whatever the profile holds. JSON on stdout, no secret in the output.
 *
 * Who makes the call decides the refresh race. A profile held by a running daemon is asked through that daemon
 * (`auth.check`, the way `auth login` is handed over since archive#813): the call goes through the daemon's own provider
 * instance, so an expired token is refreshed by the one refresher there is and the token file keeps one writer. A
 * stopped profile is checked here under the profile lock, so no daemon or sync can start meanwhile. A profile held by
 * a sync is `profile_in_use`.
 */

/** The seconds `auth check` waits for the provider unless `--timeout` says otherwise. */
internal const val AUTH_CHECK_DEFAULT_TIMEOUT_SECONDS: Long = 20

/** The most `--timeout` accepts: a front-end's own limit is shorter, and a longer wait is a hung call. */
internal const val AUTH_CHECK_MAX_TIMEOUT_SECONDS: Long = 300

// How much longer than the check's own limit the CLI waits for a daemon's answer (the socket round trip).
private const val DAEMON_ANSWER_GRACE_MS = 5_000L

private val NETWORK_STATUSES = setOf(408, 502, 503, 504, 524)

/**
 * The refusal for a check that failed with [e]. Worded by the exception's type (and the HTTP status when the provider
 * answered): the exception's own message could quote a response.
 */
internal fun checkFailure(e: Throwable): LifecycleError =
    when {
        e is NotSignedInException -> LifecycleError("not_signed_in", "no sign-in is stored for this profile; sign in first", e)
        e is AuthenticationException ->
            LifecycleError("credentials_rejected", "the provider did not accept the stored sign-in (revoked, expired or another account)", e)
        e is UnsupportedOperationException -> LifecycleError("unsupported", "this provider has no credential check", e)
        // The same classifier as the sign-in steps (#143): the cause chain, not only the exception's own type.
        else ->
            unreachableRefusal(e, "asked")
                ?: (e as? ProviderException)?.let(::providerAnswerFailure)
                ?: LifecycleError("provider_error", "the check failed: ${e.javaClass.simpleName}", e)
    }

// A provider failure without an HTTP status never got an answer; a gateway or timeout status says the same.
private fun providerAnswerFailure(e: ProviderException): LifecycleError {
    val status = e.httpStatus
    return if (status == null || status in NETWORK_STATUSES) {
        LifecycleError("network_error", "the provider could not be asked: ${e.javaClass.simpleName}${status?.let { " (HTTP $it)" } ?: ""}", e)
    } else {
        LifecycleError("provider_error", "the provider answered, but not usably: ${e.javaClass.simpleName} (HTTP $status)", e)
    }
}

// The provider's answer, or null when it gave none within the limit; a failure is its refusal.
@Suppress("TooGenericExceptionCaught")
private suspend fun askProvider(
    provider: CloudProvider,
    timeoutSeconds: Long,
): CredentialProof? =
    try {
        withTimeoutOrNull(timeoutSeconds * 1000) { provider.checkCredentials() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw checkFailure(e)
    }

/**
 * Runs the check of [provider] within [timeoutSeconds] and returns the success reply; every failure is a [LifecycleError]
 * with the token of [checkFailure], and an exceeded limit is `network_error` (the provider could not be asked: not a verdict).
 */
internal suspend fun checkProvider(
    profile: String,
    provider: CloudProvider,
    timeoutSeconds: Long,
): JsonObject {
    val proof =
        askProvider(provider, timeoutSeconds)
            ?: throw LifecycleError("network_error", "the provider did not answer within $timeoutSeconds s")
    return buildJsonObject {
        put("ok", true)
        put("profile", profile)
        put("status", "authenticated")
        // Absent, not null, when the provider does not say it.
        proof.account?.let { put("account", it) }
    }
}

/** The `--timeout` of the command or of the request: 1 to [AUTH_CHECK_MAX_TIMEOUT_SECONDS], else `invalid_timeout`. */
internal fun requireCheckTimeout(seconds: Long): Long {
    if (seconds !in 1..AUTH_CHECK_MAX_TIMEOUT_SECONDS) {
        throw LifecycleError("invalid_timeout", "--timeout must be between 1 and $AUTH_CHECK_MAX_TIMEOUT_SECONDS seconds")
    }
    return seconds
}

@Command(
    name = "check",
    description = [
        "Prove the stored sign-in of a profile against the provider with one authenticated call: no sync, no scan, " +
            "no sign-in prompt. Prints status authenticated (with the account when the provider says it) or a refusal " +
            "token: not_signed_in, credentials_rejected, network_error (could not be asked: not a verdict), provider_error, " +
            "unsupported. Exit 1 on any refusal. A running daemon of the profile is asked itself, so its token refresh is not raced.",
    ],
    mixinStandardHelpOptions = true,
)
class AuthCheckCommand : Callable<Int> {
    @ParentCommand
    lateinit var authCmd: AuthCommand

    @Option(names = ["--profile"], description = ["Profile (default: -p, else the default profile)"])
    var profile: String? = null

    @Option(names = ["--json"], description = ["Print the result (or the refusal) as one JSON object on stdout"])
    var json: Boolean = false

    @Option(
        names = ["--timeout"],
        description = ["Seconds to wait for the provider before answering network_error (default: \${DEFAULT-VALUE}, at most 300)"],
    )
    var timeoutSeconds: Long = AUTH_CHECK_DEFAULT_TIMEOUT_SECONDS

    override fun call(): Int =
        reply(json) {
            val limit = requireCheckTimeout(timeoutSeconds)
            val target = resolveProfileTarget(authCmd.parent, profile)
            // No account, nothing to prove: answered before any provider is built.
            if (LifecycleHooks.factoryFor(target.type)?.metadata?.authType.equals("None", ignoreCase = true)) {
                throw LifecycleError("unsupported", "profile '${target.name}' (type ${target.type}) has no account to check")
            }
            withProfileLockOrDaemon(
                target,
                viaDaemon = { endpoint ->
                    val request =
                        buildJsonObject {
                            put("verb", AuthCheckRpcHandler.VERB)
                            put("timeout_seconds", limit)
                        }
                    askDaemonToSignIn(target, endpoint, request, timeoutMs = limit * 1000 + DAEMON_ANSWER_GRACE_MS)
                },
            ) {
                runBlocking {
                    val provider = LifecycleHooks.providerFor(authCmd.parent, target)
                    try {
                        checkProvider(target.name, provider, limit)
                    } finally {
                        provider.close()
                    }
                }
            }
        }
}

/**
 * `auth.check` (docs/dev/specs/unidrive-daemon-design.md §4.12): the daemon runs the check through its own provider
 * instance, the one that holds and refreshes the profile's token. ADMIN class like the other `auth.*` verbs. Nothing
 * here logs or returns a token; what is logged is the verb and the refusal token.
 */
internal class AuthCheckRpcHandler(
    private val profile: String,
    private val provider: CloudProvider,
) {
    private val log = LoggerFactory.getLogger(AuthCheckRpcHandler::class.java)

    // One check at a time: a front-end that asks twice does not start two provider calls.
    private val oneAtATime = Mutex()

    @Suppress("TooGenericExceptionCaught")
    suspend fun check(jsonRequest: String): String {
        val body = runCatching { Json.parseToJsonElement(jsonRequest) as? JsonObject }.getOrNull() ?: return INVALID_REQUEST
        val seconds = (body["timeout_seconds"] as? JsonPrimitive)?.content?.toLongOrNull() ?: AUTH_CHECK_DEFAULT_TIMEOUT_SECONDS
        return oneAtATime.withLock {
            try {
                checkProvider(profile, provider, requireCheckTimeout(seconds)).toString()
            } catch (e: LifecycleError) {
                log.info("{}: refused ({})", VERB, e.token)
                lifecycleErrorJson(e).toString()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("{}: the check failed: {}", VERB, e.javaClass.simpleName)
                lifecycleErrorJson(LifecycleError("provider_error", "the check failed: ${e.javaClass.simpleName}")).toString()
            }
        }
    }

    companion object {
        const val VERB: String = "auth.check"

        private const val INVALID_REQUEST = """{"ok":false,"error":"invalid_request","message":"the request is not one JSON object"}"""
    }
}
