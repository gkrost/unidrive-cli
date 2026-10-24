package org.krost.unidrive.onedrive

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.AccountName
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.NotSignedInException
import org.krost.unidrive.ProviderException
import org.krost.unidrive.onedrive.model.Token
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `checkCredentials()` (archive#1240): one authenticated GET of the drive root with the stored token, no sign-in whatever is
 * stored. The Graph answers are scripted on a MockEngine; the token is made up.
 */
class OneDriveCheckCredentialsTest {
    private lateinit var dir: Path
    private val requests = java.util.concurrent.CopyOnWriteArrayList<HttpRequestData>()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("od-check-")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun store() =
        runBlocking {
            OAuthService(OneDriveConfig(tokenPath = dir)).use {
                it.saveToken(
                    Token(
                        accessToken = "a".repeat(64),
                        tokenType = "Bearer",
                        expiresAt = System.currentTimeMillis() + 3_600_000L,
                        refreshToken = "fake-refresh",
                    ),
                )
            }
        }

    private fun provider(handler: (HttpRequestData) -> Pair<HttpStatusCode, String>): OneDriveProvider =
        OneDriveProvider(
            OneDriveConfig(tokenPath = dir),
            MockEngine { request ->
                requests += request
                val (status, body) = handler(request)
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )

    private val drive =
        """{"id":"d1","driveType":"personal","owner":{"user":{"displayName":"Ada Example","id":"u1"}},"quota":{"total":10,"used":1,"remaining":9,"deleted":0}}"""

    @Test
    fun `an accepted token is one GET of the drive root, with the owner as the account`() =
        runBlocking {
            store()
            val provider = provider { HttpStatusCode.OK to drive }
            try {
                val proof = provider.checkCredentials()

                assertEquals("Ada Example", proof.account)
                assertEquals(1, requests.size, "one authenticated call")
                assertTrue(requests.single().url.encodedPath.endsWith("/me/drive"), requests.single().url.toString())
                assertEquals("Bearer " + "a".repeat(64), requests.single().headers[HttpHeaders.Authorization])
            } finally {
                provider.close()
            }
        }

    @Test
    fun `the name stored at sign-in wins over the owner name`() =
        runBlocking {
            store()
            AccountName.write(dir, "stored@example.test")
            val provider = provider { HttpStatusCode.OK to drive }
            try {
                assertEquals("stored@example.test", provider.checkCredentials().account)
            } finally {
                provider.close()
            }
        }

    @Test
    fun `an account the drive does not name has no account`() =
        runBlocking {
            store()
            val provider = provider { HttpStatusCode.OK to """{"id":"d1","driveType":"personal"}""" }
            try {
                assertNull(provider.checkCredentials().account)
            } finally {
                provider.close()
            }
        }

    @Test
    fun `nothing stored is not signed in, with no request and no browser`() =
        runBlocking<Unit> {
            val provider = provider { error("no request may be made without a token") }
            try {
                assertFailsWith<NotSignedInException> { provider.checkCredentials() }
                assertTrue(requests.isEmpty())
            } finally {
                provider.close()
            }
        }

    @Test
    fun `a token the provider refuses even after the refresh is an authentication failure`() =
        runBlocking<Unit> {
            store()
            val provider = provider { HttpStatusCode.Unauthorized to """{"error":{"code":"InvalidAuthenticationToken"}}""" }
            try {
                assertFailsWith<AuthenticationException> { provider.checkCredentials() }
            } finally {
                provider.close()
            }
        }

    @Test
    fun `a connection that fails is an IOException, not a verdict`() =
        runBlocking<Unit> {
            store()
            val provider = OneDriveProvider(OneDriveConfig(tokenPath = dir), MockEngine { throw IOException("connection reset") })
            try {
                assertFailsWith<IOException> { provider.checkCredentials() }
            } finally {
                provider.close()
            }
        }

    @Test
    fun `an answer that is not usable keeps its status`() =
        runBlocking<Unit> {
            store()
            val provider = provider { HttpStatusCode.Forbidden to """{"error":{"code":"accessDenied"}}""" }
            try {
                val e = assertFailsWith<ProviderException> { provider.checkCredentials() }
                assertEquals(403, e.httpStatus)
            } finally {
                provider.close()
            }
        }
}
