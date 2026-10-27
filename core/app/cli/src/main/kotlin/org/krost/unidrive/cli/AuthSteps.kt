package org.krost.unidrive.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.krost.unidrive.AccountChangedException
import org.krost.unidrive.AccountName
import org.krost.unidrive.AccountUnverifiedException
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.CompleteAuthResult
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.auth.CredentialProtectionException
import org.krost.unidrive.internxt.BadCredentialsException
import org.krost.unidrive.internxt.InternxtConfig
import org.krost.unidrive.auth.CredentialProtectors
import org.krost.unidrive.internxt.TfaInvalidException
import org.krost.unidrive.internxt.TfaRequiredException
import org.krost.unidrive.sync.IpcEndpoint

/*
 * The steps of a console-free sign-in (`auth begin`, `auth complete`, `auth login`), shared by the two callers that run
 * them: the CLI, under the profile lock, when no daemon holds the profile, and the running daemon itself
 * (AuthLoginRpcHandler), when the CLI handed the step to it over IPC. Each step returns the command's JSON reply or
 * throws a LifecycleError; none prints, logs or returns a password, a TOTP code, an authorization code or a token, and a
 * step that fails stores nothing (the provider's own code writes the credentials only after the provider accepted them).
 */

private const val MAX_AUTH_FAILURE_MESSAGE_LENGTH = 512

/**
 * The refusal of a sign-in as another user than the one the profile's sync state belongs to
 * ([org.krost.unidrive.AccountOwner]): nothing was stored, a running daemon reloads nothing, and the earlier credentials
 * stay as they were. The reply names no user and no id.
 */
internal const val ACCOUNT_CHANGED = "account_changed"

/** The refusal of a sign-in whose user the provider did not say, on a profile with a stored user; nothing was stored. */
internal const val ACCOUNT_UNVERIFIED = "account_unverified"

internal fun boundedAuthFailureMessage(message: String): String {
    if (message.length <= MAX_AUTH_FAILURE_MESSAGE_LENGTH) return message
    var end = MAX_AUTH_FAILURE_MESSAGE_LENGTH
    if (Character.isHighSurrogate(message[end - 1]) && Character.isLowSurrogate(message[end])) end--
    return message.substring(0, end)
}

/** The provider factory of [target] when it has a device-code sign-in; otherwise the `no_device_code_flow` refusal. */
internal fun deviceCodeFactory(
    target: ProfileTarget,
    hint: String = "",
): ProviderFactory =
    LifecycleHooks.factoryFor(target.type)?.takeIf { it.supportsInteractiveAuth() }
        ?: throw LifecycleError("no_device_code_flow", "profile '${target.name}' (type ${target.type}) has no device-code sign-in$hint")

/**
 * Runs one call of the provider's own sign-in code: whatever it throws (but a cancellation) is the [token] refusal, or
 * `network_error` when the provider could not be reached ([unreachableRefusal]), worded by the exception's type only
 * (its message could quote a response).
 */
@Suppress("TooGenericExceptionCaught")
private suspend fun <T> providerStep(
    token: String,
    what: String,
    call: suspend () -> T,
): T =
    try {
        call()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        throw unreachableRefusal(e) ?: LifecycleError(token, "the sign-in could not be $what: ${e.javaClass.simpleName}", e)
    }

/** `auth begin`: starts the device-code flow; the pending flow is kept in the profile folder by the provider. */
internal suspend fun beginSignIn(
    target: ProfileTarget,
    factory: ProviderFactory,
): JsonObject {
    val begun = providerStep("begin_failed", "started") { factory.beginInteractiveAuth(target.profileDir, target.properties) }
    return buildJsonObject {
        put("ok", true)
        put("profile", target.name)
        put("handle", begun.continuationHandle)
        // Provider-supplied fields (user_code, verification_uri, expires_in, interval_seconds, message).
        for ((k, v) in begun.fields) put(k, v)
        put("expires_at", begun.expiresAt.toString())
    }
}

/**
 * One poll of `auth complete`: `status` `authenticated` (the provider stored the token) or `pending`; a failed,
 * expired or refused sign-in is a [LifecycleError]. [secret] is the authorization code of a no-redirect flow, if any.
 */
internal suspend fun completeSignInOnce(
    target: ProfileTarget,
    factory: ProviderFactory,
    handle: String,
    secret: String?,
): JsonObject {
    val outcome =
        providerStep("complete_failed", "completed") { factory.completeInteractiveAuth(target.profileDir, handle, secret, target.properties) }
    return when (outcome) {
        is CompleteAuthResult.Success ->
            buildJsonObject {
                put("ok", true)
                put("profile", target.name)
                put("status", "authenticated")
                // Absent, not null, when the provider could not say it.
                outcome.account?.let { put("account", it) }
            }
        is CompleteAuthResult.Pending ->
            buildJsonObject {
                put("ok", true)
                put("profile", target.name)
                put("status", "pending")
                put("retry_after_seconds", outcome.retryAfterSeconds)
            }
        is CompleteAuthResult.Failure -> {
            val token =
                when (outcome.reason) {
                    CompleteAuthResult.Reason.EXPIRED -> "flow_expired"
                    CompleteAuthResult.Reason.UNKNOWN_HANDLE -> "unknown_handle"
                    CompleteAuthResult.Reason.ACCESS_DENIED -> "access_denied"
                    CompleteAuthResult.Reason.ACCOUNT_CHANGED -> ACCOUNT_CHANGED
                    CompleteAuthResult.Reason.ACCOUNT_UNVERIFIED -> ACCOUNT_UNVERIFIED
                    null -> "auth_failed"
                }
            throw LifecycleError(token, boundedAuthFailureMessage(outcome.message))
        }
    }
}

/** True when a `complete` reply says the sign-in is still waiting for the user. */
internal fun JsonObject.isPendingSignIn(): Boolean = (this["status"] as? JsonPrimitive)?.content == "pending"

/** The seconds a `pending` reply asks to wait before the next poll (at least one). */
internal fun JsonObject.retryAfterSeconds(): Long = maxOf(1L, (this["retry_after_seconds"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 1L)

/** `auth login` is the Internxt sign-in: another provider type is `login_not_supported`. */
internal fun requireInternxtProfile(target: ProfileTarget) {
    if (target.type != "internxt") {
        throw LifecycleError("login_not_supported", "'auth login' is for Internxt profiles; profile '${target.name}' is ${target.type}")
    }
}

/** The refusals of `auth login` that need no provider call: another provider type, no e-mail, no password. */
internal fun requireInternxtLogin(
    target: ProfileTarget,
    email: String,
    password: String?,
) {
    requireInternxtProfile(target)
    if (email.isBlank()) throw LifecycleError("missing_email", "--email is required")
    if (password.isNullOrEmpty()) throw LifecycleError("empty_password", "no password on standard input")
}

/**
 * `auth login` (Internxt): signs in with [password] and the optional [totp]. The provider stores the credentials only
 * after the account accepted them, so a wrong password leaves the stored ones as they were.
 */
internal suspend fun loginInternxt(
    target: ProfileTarget,
    email: String,
    password: String,
    totp: String?,
): JsonObject {
    requireInternxtLogin(target, email, password)
    val account = email.trim()
    callInternxtLogin(target, account, password, totp?.trim()?.takeIf { it.isNotEmpty() })
    AccountName.write(target.profileDir, account)
    return buildJsonObject {
        put("ok", true)
        put("profile", target.name)
        put("status", "authenticated")
        put("account", account)
    }
}

/** The library sign-in, its failures mapped to tokens without their messages (they could quote a response). */
@Suppress("TooGenericExceptionCaught", "ThrowsCount")
private suspend fun callInternxtLogin(
    target: ProfileTarget,
    email: String,
    password: String,
    totp: String?,
) {
    try {
        LifecycleHooks.internxtLogin(
            InternxtConfig(tokenPath = target.profileDir, credentialProtector = CredentialProtectors.forCurrentPlatform()),
            email,
            password,
            totp,
        )
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        throw internxtLoginRefusal(e)
    }
}

private fun internxtLoginRefusal(e: Exception): LifecycleError =
    when (e) {
        is TfaRequiredException -> LifecycleError("tfa_required", "this account needs a two-factor code; repeat with --totp")
        is TfaInvalidException -> LifecycleError("tfa_invalid", "the two-factor code was rejected")
        is CredentialProtectionException ->
            LifecycleError("credential_protection_unavailable", e.message ?: "unlock the native credential store and retry", e)
        is BadCredentialsException -> LifecycleError("bad_credentials", "wrong e-mail or password")
        // Before AuthenticationException, their supertype: the library refused before it stored anything (AccountOwner).
        is AccountChangedException -> LifecycleError(ACCOUNT_CHANGED, e.message ?: ACCOUNT_CHANGED)
        is AccountUnverifiedException -> LifecycleError(ACCOUNT_UNVERIFIED, e.message ?: ACCOUNT_UNVERIFIED)
        is AuthenticationException -> LifecycleError("auth_failed", "the provider refused the sign-in")
        else -> unreachableRefusal(e) ?: LifecycleError("login_failed", "the sign-in failed: ${e.javaClass.simpleName}")
    }

// ── the hand-over to a running daemon ───────────────────────────────────────

/** How long the CLI waits for the daemon's answer to one sign-in step (a provider round trip, the key derivation of a login). */
internal const val DAEMON_SIGN_IN_TIMEOUT_MS: Long = 60_000

/**
 * Sends one sign-in step ([request], with its `verb`) to the daemon on [endpoint] over its authenticated, full-scope
 * connection: the daemon's reply when it is a success, a [LifecycleError] when it refused, null when it could not be
 * asked (it stopped meanwhile, or did not answer in time). The request may carry a secret; it travels in the body
 * only and nothing here prints or logs it.
 */
internal fun askDaemonToSignIn(
    target: ProfileTarget,
    endpoint: IpcEndpoint,
    request: JsonObject,
    timeoutMs: Long = DAEMON_SIGN_IN_TIMEOUT_MS,
): JsonObject? {
    val reply = sendDaemonRequest(endpoint, request.toString(), timeoutMs) ?: return null
    if ((reply["ok"] as? JsonPrimitive)?.content == "true") return reply
    val token = (reply["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val message = (reply["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    throw when (token) {
        "unknown_verb" ->
            LifecycleError(
                "daemon_too_old",
                "the running daemon of profile '${target.name}' is older than this command and cannot take this step over IPC;" +
                    "stop it first (`unidrive -p ${target.name} daemon stop`)",
            )
        else ->
            LifecycleError(
                token ?: "daemon_sign_in_failed",
                message?.let { boundedAuthFailureMessage(it) } ?: "the daemon of profile '${target.name}' refused the sign-in",
            )
    }
}
