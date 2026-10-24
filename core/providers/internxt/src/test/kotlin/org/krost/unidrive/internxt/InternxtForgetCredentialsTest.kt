package org.krost.unidrive.internxt

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.internxt.model.InternxtCredentials
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `forgetCredentials()` (archive#1186): after a sign-out through the running daemon, the daemon's own instance drops the
 * credentials it holds in memory. It stops reporting itself authenticated, no later request gets the old JWT, and a
 * refresh that was under way stores nothing. Every value here is made up.
 */
class InternxtForgetCredentialsTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ix-forget-")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun jwt(expEpochSec: Long): String {
        val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
        return "header.${encoder.encodeToString("""{"exp":$expEpochSec}""".toByteArray())}.signature"
    }

    private fun store(jwt: String) {
        val creds = InternxtCredentials(jwt = jwt, mnemonic = "fake mnemonic", rootFolderId = "root-folder-uuid", email = "user@example.invalid")
        Files.writeString(dir.resolve("credentials.json"), Json.encodeToString(InternxtCredentials.serializer(), creds))
    }

    private fun config() = InternxtConfig(credentialProtector = null, tokenPath = dir)

    @Test
    fun `the provider forgets its credentials and touches nothing on disk`() =
        runBlocking<Unit> {
            store(jwt(9_999_999_999L))
            val provider = InternxtProvider(config())
            try {
                provider.reloadCredentials()
                assertTrue(provider.isAuthenticated)

                provider.forgetCredentials()

                assertFalse(provider.isAuthenticated, "the daemon's instance is signed out")
                assertTrue(Files.exists(dir.resolve("credentials.json")), "only the memory: the sign-out removes the files itself")
            } finally {
                provider.close()
            }
        }

    @Test
    fun `no request gets the forgotten credentials`() =
        runBlocking<Unit> {
            store(jwt(9_999_999_999L))
            val auth = AuthService(config())
            try {
                auth.initialize()
                assertTrue(auth.getValidCredentials().jwt.isNotEmpty())

                auth.forget()

                assertFailsWith<AuthenticationException> { auth.getValidCredentials() }
                assertFailsWith<AuthenticationException> { auth.getValidCredentials(forceRefresh = true) }
            } finally {
                auth.close()
            }
        }

    /** The refresh route waits for [release] once it has been reached. */
    private class HeldRefreshAuthService(
        config: InternxtConfig,
    ) : AuthService(config) {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun fetchRefreshedJwt(currentJwt: String): String {
            started.complete(Unit)
            release.await()
            return "header.refreshed.signature"
        }
    }

    @Test
    fun `a refresh under way when the credentials are forgotten stores nothing`() =
        runBlocking<Unit> {
            // Inside the refresh window: the next request refreshes the JWT.
            store(jwt(System.currentTimeMillis() / 1000 + 3600))
            val auth = HeldRefreshAuthService(config())
            try {
                auth.initialize()
                val request = async { runCatching { auth.getValidCredentials() } }
                auth.started.await()
                // The sign-out: the daemon's instance forgets, then the credential file goes.
                auth.forget()
                Files.delete(dir.resolve("credentials.json"))
                auth.release.complete(Unit)

                assertTrue(request.await().exceptionOrNull() is AuthenticationException, "the request under way fails as signed out")
                assertFalse(auth.isAuthenticated, "the refreshed JWT did not bring the account back")
                assertFalse(Files.exists(dir.resolve("credentials.json")), "the refreshed JWT was not written back")
            } finally {
                auth.close()
            }
        }
}
