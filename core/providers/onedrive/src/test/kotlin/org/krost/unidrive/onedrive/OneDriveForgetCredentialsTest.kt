package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.onedrive.model.Token
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `forgetCredentials()` (#1186): after a sign-out through the running daemon, the daemon's own instance drops the
 * token it holds in memory. It stops reporting itself authenticated, no later request gets the old token, and a
 * refresh that was under way stores nothing. Every token here is made up.
 */
class OneDriveForgetCredentialsTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("od-forget-")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun token(expiresInMs: Long) =
        Token(accessToken = "a".repeat(64), tokenType = "Bearer", expiresAt = System.currentTimeMillis() + expiresInMs, refreshToken = "r".repeat(80))

    @Test
    fun `the provider forgets its token and touches nothing on disk`() =
        runBlocking<Unit> {
            val config = OneDriveConfig(tokenPath = dir)
            OAuthService(config).use { it.saveToken(token(3_600_000L)) }
            val provider = OneDriveProvider(config)
            try {
                provider.reloadCredentials()
                assertTrue(provider.isAuthenticated)

                provider.forgetCredentials()

                assertFalse(provider.isAuthenticated, "the daemon's instance is signed out")
                assertTrue(Files.exists(dir.resolve("token.json")), "only the memory: the sign-out removes the files itself")
            } finally {
                provider.close()
            }
        }

    @Test
    fun `no request gets the forgotten token`() =
        runBlocking<Unit> {
            val config = OneDriveConfig(tokenPath = dir)
            OAuthService(config).use { it.saveToken(token(3_600_000L)) }
            val manager = TokenManager(config, OAuthService(config))
            manager.initialize()
            assertTrue(manager.getValidToken().accessToken.isNotEmpty())

            manager.forget()

            assertFailsWith<AuthenticationException> { manager.getValidToken() }
            assertFailsWith<AuthenticationException> { manager.getValidToken(forceRefresh = true) }
        }

    @Test
    fun `a refresh under way when the token is forgotten stores nothing`() =
        runBlocking<Unit> {
            val config = OneDriveConfig(tokenPath = dir)
            val refreshStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val engine =
                MockEngine {
                    refreshStarted.complete(Unit)
                    release.await()
                    respond(
                        """{"access_token":"${"b".repeat(64)}","token_type":"Bearer","expires_in":3600,"refresh_token":"${"n".repeat(80)}"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val oauth = OAuthService(config, httpClient = HttpClient(engine))
            oauth.saveToken(token(1_000L)) // near expiry: the next request refreshes it
            val manager = TokenManager(config, oauth)
            manager.initialize()

            val request = async { runCatching { manager.getValidToken() } }
            refreshStarted.await()
            // The sign-out: the daemon's instance forgets, then the token file goes.
            manager.forget()
            Files.delete(dir.resolve("token.json"))
            release.complete(Unit)

            assertTrue(request.await().exceptionOrNull() is AuthenticationException, "the request under way fails as signed out")
            assertFalse(manager.isAuthenticated, "the refreshed token did not bring the account back")
            assertFalse(Files.exists(dir.resolve("token.json")), "the refreshed token was not written back")
        }
}
