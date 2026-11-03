package org.krost.unidrive.onedrive

import org.krost.unidrive.AccountOwner
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.TransientNetworkException
import org.krost.unidrive.auth.RefreshableTokenLatch
import org.krost.unidrive.auth.awaitOAuthCallback
import org.krost.unidrive.http.HttpRetryBudget
import org.krost.unidrive.io.openBrowser
import org.krost.unidrive.onedrive.model.Token
import java.nio.channels.UnresolvedAddressException
import kotlin.time.Duration.Companion.minutes

class TokenManager(
    private val config: OneDriveConfig,
    private val oauthService: OAuthService,
    // Who a console sign-in signed in (the drive owner id), read before its token is saved; a test seam.
    private val identityOf: suspend (Token) -> String? = { graphUserId(config, it) },
) {
    private val log = org.slf4j.LoggerFactory.getLogger(TokenManager::class.java)

    // Tests seed it to skip a sign-in.
    @Volatile
    internal var token: Token? = null

    // UD-338: shared mutex + NonCancellable wrap lifted to :app:core/auth.
    // Provider-specific force-refresh (UD-310) and RefreshFailure recording
    // (UD-111) stay below in `getValidToken`.
    private val refreshLatch = RefreshableTokenLatch()

    // Only the log and tests read it today: the daemon's credential_health is an offline check of the token files
    // (ProviderFactory.checkCredentialHealth) and holds no TokenManager, so a save failure is not on a health surface.
    @Volatile
    var lastRefreshFailure: RefreshFailure? = null
        private set

    // The refreshed token is live in memory but its save failed: the token file still holds the older (possibly rotated
    // away) refresh token. A plain getValidToken retries the save, at most once per SAVE_RETRY_INTERVAL_MS.
    @Volatile
    private var saveDirty = false

    @Volatile
    private var nextSaveRetryAtMs = 0L

    data class RefreshFailure(
        val timestamp: java.time.Instant,
        val errorClass: String,
        val message: String?,
    )

    val isAuthenticated: Boolean get() = token != null

    suspend fun initialize() {
        val loaded = oauthService.loadToken()
        refreshLatch.replace { token = loaded }
    }

    /**
     * Re-reads the stored token, for a sign-in that wrote a new one while this manager is in use. A refresh that failed
     * before belongs to the token that was replaced, so its record goes too. Never signs in itself.
     */
    suspend fun reload() {
        val loaded = oauthService.loadToken()
        refreshLatch.replace {
            token = loaded
            lastRefreshFailure = null
            saveDirty = false
        }
    }

    /**
     * Drops the token from memory after a sign-out removed the stored one (archive#1186): [getValidToken] fails as not
     * authenticated from now on, and a refresh still under way stores nothing. The token file is not touched.
     */
    suspend fun forget() {
        refreshLatch.replace {
            token = null
            lastRefreshFailure = null
            saveDirty = false
        }
    }

    suspend fun authenticateWithBrowser(): Token {
        val state =
            java.util.UUID
                .randomUUID()
                .toString()
        val (authUrl, pkceVerifier) = oauthService.getAuthorizationUrl(state)

        println("Opening browser for authentication...")
        println("URL: $authUrl")
        println("Waiting up to 5 minutes for authentication...")

        openBrowser(authUrl)

        val code =
            awaitOAuthCallback(
                port = config.oauthCallbackPort,
                expectedState = state,
                providerLabel = "Azure",
                timeout = 5.minutes,
                bindFailureHint =
                    "Set ${OneDriveConfig.OAUTH_PORT_ENV} to a free port, or sign in without a browser " +
                        "with `unidrive auth --device-code`.",
            )

        return storeSignIn(oauthService.exchangeCodeForToken(code, pkceVerifier))
    }

    suspend fun authenticateWithDeviceCode(): Token {
        val deviceCode = oauthService.getDeviceCode()

        println(deviceCode.message)
        println()
        println("Waiting for authentication...")

        val fresh = storeSignIn(oauthService.pollForToken(deviceCode.deviceCode, deviceCode.interval))

        println("Authentication successful!")

        return fresh
    }

    /**
     * Saves the token of a console sign-in, after comparing its user with the one the profile's state belongs to
     * ([AccountOwner]): another user is refused ([org.krost.unidrive.AccountChangedException]) and nothing is saved.
     */
    private suspend fun storeSignIn(fresh: Token): Token {
        val user = identityOf(fresh)
        AccountOwner.requireSameUser(config.tokenPath, user)
        oauthService.saveToken(fresh)
        token = fresh
        saveDirty = false
        AccountOwner.record(config.tokenPath, user)
        return fresh
    }

    suspend fun getValidToken(
        forceRefresh: Boolean = false,
        // The token the caller's rejected request carried (#215): when the stored token already differs
        // from it, another caller's refresh answered the 401 and this one must not rotate again. Without
        // it, the token observed at entry decides, as before.
        rejectedToken: String? = null,
    ): Token {
        // Read before the token: a sign-out or reload after this point keeps a retried save from storing a stale token.
        val seenGeneration = refreshLatch.generation()
        val currentToken = token ?: throw AuthenticationException("Not authenticated")

        if (!forceRefresh && !currentToken.isNearExpiry()) {
            if (saveDirty) retryDirtySave(currentToken, seenGeneration)
            return currentToken
        }

        // UD-338: serialisation + NonCancellable wrap delegated to
        // RefreshableTokenLatch. The skip-if-already-fresh predicate
        // captures both OneDrive nuances:
        //  - normal callers (no forceRefresh) accept any fresh token,
        //  - forceRefresh callers accept a fresh token IFF it's not the
        //    one their rejected request carried (another coroutine
        //    already did the force-refresh), or — when no rejected token
        //    was named — a different object than the one observed pre-lock.
        // RefreshFailure recording (UD-111) and the throw still live
        // here because they're provider-specific UX, not lock-mechanics.
        return refreshLatch.withRefresh(
            isAlreadyFresh = {
                val freshToken = token ?: throw AuthenticationException("Not authenticated")
                val rotated = rejectedToken?.let { freshToken.accessToken != it } ?: (freshToken !== currentToken)
                when {
                    !forceRefresh && !freshToken.isNearExpiry() -> freshToken
                    forceRefresh && !freshToken.isNearExpiry() && rotated -> freshToken
                    else -> null
                }
            },
            body = {
                // Read before the token: a sign-out after this point keeps the refreshed token from being stored.
                val generation = refreshLatch.generation()
                val freshToken = token ?: throw AuthenticationException("Not authenticated")
                val refreshToken = freshToken.refreshToken
                var signedOut = false
                if (refreshToken != null) {
                    try {
                        val refreshed = oauthService.refreshToken(refreshToken)
                        // The refresh succeeded. A failed save of it (an antivirus scanner or a backup tool
                        // holding the token file) must not surface as an auth failure: the new token is live
                        // in memory and the caller gets it. The next refresh (or a sign-in) is the retry of
                        // the save — every successful refresh saves again, and a plain getValidToken retries it
                        // sooner (retryDirtySave). The failure is recorded in lastRefreshFailure, which names the
                        // save, not "authentication expired"; only the log and tests read it today (no health
                        // surface does).
                        val stored =
                            refreshLatch.commitIfCurrent(generation) {
                                token = refreshed
                                try {
                                    oauthService.saveToken(refreshed)
                                    lastRefreshFailure = null
                                    saveDirty = false
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    log.warn(
                                        "The refreshed token could not be saved to {}: {}: {}. It stays in memory; the next refresh or sign-in saves it.",
                                        config.tokenPath,
                                        e.javaClass.simpleName,
                                        e.message,
                                    )
                                    lastRefreshFailure =
                                        RefreshFailure(
                                            timestamp = java.time.Instant.now(),
                                            errorClass = e.javaClass.simpleName,
                                            message = "saving the refreshed token failed: ${e.message}",
                                        )
                                    saveDirty = true
                                    nextSaveRetryAtMs = 0L
                                }
                            }
                        if (stored) return@withRefresh refreshed
                        signedOut = true
                    } catch (e: Exception) {
                        val failure =
                            RefreshFailure(
                                timestamp = java.time.Instant.now(),
                                errorClass = e.javaClass.simpleName,
                                message = e.message,
                            )
                        lastRefreshFailure = failure
                        // A transient network blip (DNS unresolved, connection
                        // reset, read/connect timeout) during refresh is NOT proof
                        // the token expired — a later live call can still succeed.
                        // Surface it as retryable so the session is not latched as
                        // "expired → re-authenticate."
                        if (isTransientNetworkFailure(e)) {
                            log.warn(
                                "OAuth refresh hit a transient network failure: {}: {}. Treating as retryable, not expired.",
                                failure.errorClass,
                                failure.message,
                                e,
                            )
                            throw TransientNetworkException(
                                "Transient network failure during token refresh: ${e.message}",
                                cause = e,
                            )
                        }
                        log.warn(
                            "OAuth refresh failed: {}: {}. User will be prompted to re-authenticate.",
                            failure.errorClass,
                            failure.message,
                            e,
                        )
                    }
                }
                if (signedOut) throw AuthenticationException("Not authenticated")
                throw AuthenticationException("Authentication expired. Please re-authenticate.")
            },
        )
    }

    /**
     * Retries the save of a refreshed token whose first save failed, on the fast path of [getValidToken], so a stopped
     * process within the hour until the next refresh does not lose a rotated refresh token. Cheap: it runs only while
     * [saveDirty], at most once per [SAVE_RETRY_INTERVAL_MS], and not at all after a sign-out or reload since
     * [seenGeneration] (the commit is refused then) or when a refresh replaced [current] meanwhile (that refresh saves).
     */
    private suspend fun retryDirtySave(
        current: Token,
        seenGeneration: Long,
    ) {
        val now = System.currentTimeMillis()
        if (now < nextSaveRetryAtMs) return
        refreshLatch.commitIfCurrent(seenGeneration) {
            if (!saveDirty || token !== current) return@commitIfCurrent
            try {
                oauthService.saveToken(current)
                saveDirty = false
                lastRefreshFailure = null
                log.info("The refreshed token was saved to {} on retry.", config.tokenPath)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                nextSaveRetryAtMs = now + SAVE_RETRY_INTERVAL_MS
                log.debug("Retrying the save of the refreshed token to {} failed: {}: {}", config.tokenPath, e.javaClass.simpleName, e.message)
            }
        }
    }

    suspend fun logout() {
        forget()
        oauthService.deleteToken()
    }

    private companion object {
        const val SAVE_RETRY_INTERVAL_MS = 30_000L
    }

    // Classify a refresh-time throwable as a transient network failure (retryable)
    // versus a real auth failure (expired refresh token / invalid_grant). The auth
    // failure surfaces from refreshToken as an AuthenticationException on a non-2xx
    // body; anything network-flavoured (DNS unresolved, connection reset, timeout)
    // must be treated as retryable so a blip can't latch the session as expired.
    private fun isTransientNetworkFailure(e: Throwable): Boolean {
        // A non-2xx refresh response (e.g. 400 invalid_grant) is a real auth
        // failure, not transient — refreshToken raises AuthenticationException.
        if (e is AuthenticationException) return false
        // The token endpoint answered 408 / 429 / 5xx (OAuthService.refreshToken): it said nothing about the token.
        if (e is TransientNetworkException) return true
        if (e is UnresolvedAddressException) return true // DNS blip — transient.
        if (e is io.ktor.client.plugins.HttpRequestTimeoutException) return true
        if (e is java.io.IOException && HttpRetryBudget.isRetriableIoException(e)) return true
        // Ktor wraps the underlying socket failure; walk one cause level for the
        // common DNS-unresolved and retryable-IO shapes.
        val cause = e.cause ?: return false
        if (cause is UnresolvedAddressException) return true
        if (cause is io.ktor.client.plugins.HttpRequestTimeoutException) return true
        return cause is java.io.IOException && HttpRetryBudget.isRetriableIoException(cause)
    }
}
