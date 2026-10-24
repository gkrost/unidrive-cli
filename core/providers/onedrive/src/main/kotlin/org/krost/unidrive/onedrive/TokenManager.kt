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

    @Volatile
    private var token: Token? = null

    // UD-338: shared mutex + NonCancellable wrap lifted to :app:core/auth.
    // Provider-specific force-refresh (UD-310) and RefreshFailure recording
    // (UD-111) stay below in `getValidToken`.
    private val refreshLatch = RefreshableTokenLatch()

    @Volatile
    var lastRefreshFailure: RefreshFailure? = null
        private set

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
        AccountOwner.record(config.tokenPath, user)
        return fresh
    }

    suspend fun getValidToken(forceRefresh: Boolean = false): Token {
        val currentToken = token ?: throw AuthenticationException("Not authenticated")

        if (!forceRefresh && !currentToken.isNearExpiry()) {
            return currentToken
        }

        // UD-338: serialisation + NonCancellable wrap delegated to
        // RefreshableTokenLatch. The skip-if-already-fresh predicate
        // captures both OneDrive nuances:
        //  - normal callers (no forceRefresh) accept any fresh token,
        //  - forceRefresh callers accept a fresh token IFF it's a
        //    different object than the one we observed pre-lock (i.e.
        //    another coroutine already did the force-refresh).
        // RefreshFailure recording (UD-111) and the throw still live
        // here because they're provider-specific UX, not lock-mechanics.
        return refreshLatch.withRefresh(
            isAlreadyFresh = {
                val freshToken = token ?: throw AuthenticationException("Not authenticated")
                when {
                    !forceRefresh && !freshToken.isNearExpiry() -> freshToken
                    forceRefresh && !freshToken.isNearExpiry() && freshToken !== currentToken -> freshToken
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
                        val stored =
                            refreshLatch.commitIfCurrent(generation) {
                                token = refreshed
                                oauthService.saveToken(refreshed)
                                lastRefreshFailure = null
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

    suspend fun logout() {
        forget()
        oauthService.deleteToken()
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
