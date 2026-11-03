package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.onedrive.model.Token
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A token-file save that fails after a successful refresh (an antivirus scanner or a backup tool
 * holding the file during the hourly refresh) must not surface as "Authentication expired": the
 * refresh succeeded and the new token is live in memory, so [TokenManager.getValidToken] returns it
 * and the failure is recorded as the save failure it is.
 */
class TokenManagerSaveFailureTest {
    private fun nearExpiryTokenWithRefresh(): Token =
        Token(
            accessToken = "A".repeat(64),
            tokenType = "Bearer",
            // Near-expiry so getValidToken(forceRefresh = true) takes the refresh path.
            expiresAt = System.currentTimeMillis() + 1_000,
            refreshToken = "r".repeat(80),
            scope = "openid Files.ReadWrite.All",
        )

    @Test
    fun `a failed save after a successful refresh returns the refreshed token and names the save`() =
        runTest {
            // A regular file where the token DIRECTORY should be: every save fails with
            // FileAlreadyExistsException, deterministically on every platform.
            val blocked = Files.createTempFile("unidrive-token-blocked-", ".json")
            try {
                var refreshCalls = 0
                val engine =
                    MockEngine { _ ->
                        refreshCalls++
                        respond(
                            content =
                                """{"access_token":"${"B".repeat(64)}","token_type":"Bearer",""" +
                                    """"expires_in":3600,"refresh_token":"${"n".repeat(80)}"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                val config = OneDriveConfig(tokenPath = blocked)
                val manager = TokenManager(config, OAuthService(config, httpClient = HttpClient(engine)))
                manager.token = nearExpiryTokenWithRefresh()

                // No AuthenticationException: the refresh succeeded, and only the save failed.
                val token = manager.getValidToken(forceRefresh = true)

                assertEquals("B".repeat(64), token.accessToken, "the refreshed token is returned, not a failure")
                assertEquals(1, refreshCalls, "the refresh itself succeeded on the first call")
                val failure = manager.lastRefreshFailure
                assertNotNull(failure, "the save failure is recorded for the health surface")
                assertEquals("FileAlreadyExistsException", failure.errorClass, "the failure names the save")
                assertTrue(
                    failure.message.orEmpty().contains("saving the refreshed token failed"),
                    "the failure names the save, not an expiry: ${failure.message}",
                )
            } finally {
                Files.deleteIfExists(blocked)
            }
        }

    private fun refreshEngine() =
        MockEngine { _ ->
            respond(
                content =
                    """{"access_token":"${"B".repeat(64)}","token_type":"Bearer",""" +
                        """"expires_in":3600,"refresh_token":"${"n".repeat(80)}"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

    // The recovery half of the claim: once the blocking file is gone, the next forced refresh saves the new token
    // again and clears the recorded failure.
    @Test
    fun `the next refresh after the blocking file is gone saves the token and clears the failure`() =
        runTest {
            val blocked = Files.createTempFile("unidrive-token-blocked-", ".json")
            try {
                val config = OneDriveConfig(tokenPath = blocked)
                val oauth = OAuthService(config, httpClient = HttpClient(refreshEngine()))
                val manager = TokenManager(config, oauth)
                manager.token = nearExpiryTokenWithRefresh()

                manager.getValidToken(forceRefresh = true)
                assertNotNull(manager.lastRefreshFailure, "the first save failed")

                Files.delete(blocked)
                manager.token = manager.token!!.copy(expiresAt = System.currentTimeMillis() + 1_000)
                manager.getValidToken(forceRefresh = true)

                assertEquals("B".repeat(64), oauth.loadToken()?.accessToken, "the token file holds the refreshed token")
                assertNull(manager.lastRefreshFailure, "a successful save clears the failure")
            } finally {
                blocked.toFile().deleteRecursively()
            }
        }

    // A plain getValidToken() on the fast path (token not near expiry) retries the failed save right away, so a
    // stopped process does not wait for the hourly refresh and lose a rotated refresh token.
    @Test
    fun `a plain getValidToken retries the failed save and clears the failure`() =
        runTest {
            val blocked = Files.createTempFile("unidrive-token-blocked-", ".json")
            try {
                val config = OneDriveConfig(tokenPath = blocked)
                val oauth = OAuthService(config, httpClient = HttpClient(refreshEngine()))
                val manager = TokenManager(config, oauth)
                manager.token = nearExpiryTokenWithRefresh()
                manager.getValidToken(forceRefresh = true)
                Files.delete(blocked)

                // Not near expiry (3600 s): the fast path, no refresh call.
                val token = manager.getValidToken()

                assertEquals("B".repeat(64), token.accessToken)
                assertEquals("B".repeat(64), oauth.loadToken()?.accessToken, "the fast path saved the token")
                assertNull(manager.lastRefreshFailure, "the retried save clears the failure")
            } finally {
                blocked.toFile().deleteRecursively()
            }
        }

    // A sign-out between the failed save and the retry: the retry must not bring the token back to disk.
    @Test
    fun `a retried save does not store a token after a sign-out`() =
        runTest {
            val blocked = Files.createTempFile("unidrive-token-blocked-", ".json")
            try {
                val config = OneDriveConfig(tokenPath = blocked)
                val oauth = OAuthService(config, httpClient = HttpClient(refreshEngine()))
                val manager = TokenManager(config, oauth)
                manager.token = nearExpiryTokenWithRefresh()
                manager.getValidToken(forceRefresh = true)
                Files.delete(blocked)

                manager.forget()
                assertFailsWith<org.krost.unidrive.AuthenticationException> { manager.getValidToken() }

                assertNull(oauth.loadToken(), "nothing was written after the sign-out")
                assertNull(manager.lastRefreshFailure)
            } finally {
                blocked.toFile().deleteRecursively()
            }
        }
}
