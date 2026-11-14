package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.TransientNetworkException
import org.krost.unidrive.auth.RefreshableTokenLatch
import org.krost.unidrive.onedrive.model.Token
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * #748 finding 1: a token refresh that runs past the latch's 60 s deadline (a token endpoint that accepted and
 * then stalls always loses to the latch: the socket timeout equals it) is a transport failure, not a verdict on
 * the credential — and not a user cancellation either. The latch converts its deadline into a
 * [java.net.SocketTimeoutException], which [TokenManager.isTransientNetworkFailure] classifies as retryable, so
 * the stalled refresh must surface [TransientNetworkException] instead of latching the session as
 * "authentication expired". A real cancellation during the refresh stays one and records no refresh failure.
 */
class TokenManagerRefreshTimeoutTest {
    private fun nearExpiryTokenWithRefresh(): Token =
        Token(
            // Above the 32-char plausibility floor so the refresh path is reached.
            accessToken = "A".repeat(64),
            tokenType = "Bearer",
            // Near-expiry so getValidToken() forces a refresh.
            expiresAt = System.currentTimeMillis() + 1_000,
            refreshToken = "r".repeat(80),
            scope = "openid Files.ReadWrite.All",
        )

    @Test
    fun `a refresh that stalls past the latch deadline is retryable, not authentication expired`() = runTest {
        // The token endpoint accepted and then stalls: the refresh never answers, and the latch deadline (real
        // time, Dispatchers.Default) is the only thing that can end it.
        val manager =
            TokenManager(
                OneDriveConfig(),
                OAuthService(OneDriveConfig(), httpClient = HttpClient(MockEngine { awaitCancellation() })),
                refreshLatch = RefreshableTokenLatch(timeout = 300.milliseconds),
            )
        manager.token = nearExpiryTokenWithRefresh()

        val e =
            assertFailsWith<TransientNetworkException> {
                manager.getValidToken()
            }
        assertTrue(
            e.message?.contains("token refresh timed out") == true,
            "the message names the stall, not an expiry: ${e.message}",
        )
        assertTrue(
            manager.lastRefreshFailure?.errorClass != "AuthenticationException",
            "a stalled endpoint must not be recorded as an AuthenticationException latch",
        )
    }

    @Test
    fun `a real cancellation during the refresh stays a cancellation and records no failure`() = runTest {
        // The engine reports when the caller's refresh has reached the stalled endpoint, so the cancellation below
        // lands inside the refresh (not before it).
        val reached = CompletableDeferred<Unit>()
        val manager =
            TokenManager(
                OneDriveConfig(),
                OAuthService(
                    OneDriveConfig(),
                    httpClient = HttpClient(MockEngine { reached.complete(Unit); awaitCancellation() }),
                ),
            )
        manager.token = nearExpiryTokenWithRefresh()

        val observed = CompletableDeferred<Throwable>()
        val caller =
            launch {
                try {
                    manager.getValidToken()
                } catch (e: Throwable) {
                    observed.complete(e)
                    throw e
                }
            }
        runCurrent()
        reached.await() // the refresh is inside the stalled token endpoint now
        caller.cancelAndJoin()

        val e = observed.await()
        assertTrue(e is kotlinx.coroutines.CancellationException, "a cancelled caller stays cancelled; got ${e.javaClass.name}")
        assertFalse(e is AuthenticationException, "a cancellation must not surface as authentication expired")
        assertNull(manager.lastRefreshFailure, "a cancelled caller is not a refresh failure")
    }
}
