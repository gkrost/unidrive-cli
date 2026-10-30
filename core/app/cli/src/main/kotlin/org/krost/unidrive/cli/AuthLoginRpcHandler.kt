package org.krost.unidrive.cli

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.krost.unidrive.io.string
import org.slf4j.LoggerFactory

/**
 * The sign-in verbs (docs/dev/specs/unidrive-daemon-design.md §4.11): a front-end signs an account in again while its
 * daemon runs. The daemon holds the profile lock, so `auth begin` / `auth complete` / `auth login` hand their step to it
 * instead of refusing with `profile_in_use`, and the daemon runs the same step the CLI runs on a stopped profile
 * (AuthSteps.kt):
 *
 * - `auth.begin` `{}`: starts the device-code flow (OneDrive); the reply is `auth begin --json`'s.
 * - `auth.complete` `{"handle":..., "code":...?}`: one poll of that flow; `code` is the authorization code of a
 *   no-redirect flow (Dropbox). The reply is `auth complete --json`'s for one poll (`pending` or `authenticated`).
 * - `auth.login` `{"email":..., "password":..., "totp":...?}`: the Internxt sign-in; the reply is `auth login --json`'s.
 *
 * A step that succeeds has stored the credentials (the provider writes them, owner-only, only after the account
 * accepted them: a wrong password or code leaves the stored ones as they were). [onSignedIn] then reloads them into
 * the daemon's own provider instance, so the mount keeps running and the next `daemon.status` reports them. A refusal is
 * `{"ok":false,"error":token,"message":...}` with the CLI's tokens; `reload_failed` says the credentials were stored but
 * the running daemon could not load them.
 *
 * The secrets (password, TOTP code, authorization code) arrive in the request body only. Nothing here logs them, puts
 * them in a reply or keeps them after the request; what is logged is the verb and the refusal token. One step runs at
 * a time. Admin class: a `read` connection gets `forbidden` ([org.krost.unidrive.sync.IpcAuth]).
 */
internal class AuthLoginRpcHandler(
    // The profile the daemon serves, with its non-secret config; null when it could not be read at start (every verb
    // then answers sign_in_unavailable).
    private val target: ProfileTarget?,
    private val onSignedIn: suspend () -> Unit,
) {
    private val log = LoggerFactory.getLogger(AuthLoginRpcHandler::class.java)
    private val oneAtATime = Mutex()

    suspend fun begin(jsonRequest: String): String =
        step(BEGIN, jsonRequest) { target, _ ->
            beginSignIn(target, deviceCodeFactory(target))
        }

    suspend fun complete(jsonRequest: String): String =
        step(COMPLETE, jsonRequest) { target, body ->
            val handle = body.string("handle")?.takeIf { it.isNotBlank() } ?: throw LifecycleError("missing_handle", "the request names no handle")
            val code = body.string("code")?.trim()?.takeIf { it.isNotEmpty() }
            completeSignInOnce(target, deviceCodeFactory(target), handle, code).also { reply ->
                if (!reply.isPendingSignIn()) reload()
            }
        }

    suspend fun login(jsonRequest: String): String =
        step(LOGIN, jsonRequest) { target, body ->
            val email = body.string("email") ?: ""
            val password = body.string("password").orEmpty()
            loginInternxt(target, email, password, body.string("totp")).also { reload() }
        }

    // The reload is the provider's own code: whatever it throws is reload_failed, logged and worded by its type only.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun reload() {
        try {
            onSignedIn()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("sign-in: the credentials were stored but could not be loaded into the running daemon: {}", e.javaClass.simpleName)
            throw LifecycleError(
                "reload_failed",
                "the credentials were stored, but the running daemon could not load them (${e.javaClass.simpleName}); restart it",
                e,
            )
        }
    }

    // Every failure is one refusal line: a LifecycleError with its token, anything else by its type only (its message
    // could quote a response).
    @Suppress("TooGenericExceptionCaught")
    private suspend fun step(
        verb: String,
        jsonRequest: String,
        block: suspend (ProfileTarget, JsonObject) -> JsonObject,
    ): String {
        val profile = target ?: return SIGN_IN_UNAVAILABLE
        val body = runCatching { Json.parseToJsonElement(jsonRequest) as? JsonObject }.getOrNull() ?: return INVALID_REQUEST
        return oneAtATime.withLock {
            try {
                block(profile, body).toString()
            } catch (e: LifecycleError) {
                log.info("{}: refused ({})", verb, e.token)
                lifecycleErrorJson(e).toString()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("{}: the sign-in failed: {}", verb, e.javaClass.simpleName)
                lifecycleErrorJson(LifecycleError("internal_error", "unexpected failure: ${e.javaClass.simpleName}")).toString()
            }
        }
    }

    companion object {
        const val BEGIN: String = "auth.begin"
        const val COMPLETE: String = "auth.complete"
        const val LOGIN: String = "auth.login"

        private const val SIGN_IN_UNAVAILABLE =
            """{"ok":false,"error":"sign_in_unavailable","message":"this daemon cannot sign its profile in; stop it and sign in with the CLI"}"""
        private const val INVALID_REQUEST = """{"ok":false,"error":"invalid_request","message":"the request is not one JSON object"}"""
    }
}
