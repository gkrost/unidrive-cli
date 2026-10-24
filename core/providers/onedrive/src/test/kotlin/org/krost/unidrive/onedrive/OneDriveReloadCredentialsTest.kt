package org.krost.unidrive.onedrive

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.onedrive.model.Token
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `reloadCredentials()` (#813): a running daemon's provider instance picks up the token a sign-in through the daemon
 * stored, without a restart and without the browser fallback of `authenticate()`. The token is made up.
 */
class OneDriveReloadCredentialsTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("od-reload-")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `the same provider instance reads a token stored after it started, and reads its removal too`() =
        runBlocking {
            val config = OneDriveConfig(tokenPath = dir)
            val provider = OneDriveProvider(config)
            provider.reloadCredentials()
            assertFalse(provider.isAuthenticated, "nothing stored: not signed in, and no browser was opened")

            OAuthService(config).use {
                it.saveToken(Token(accessToken = "a".repeat(64), tokenType = "Bearer", expiresAt = System.currentTimeMillis() + 3_600_000L, refreshToken = "fake-refresh"))
            }
            provider.reloadCredentials()
            assertTrue(provider.isAuthenticated, "the stored token is the provider's now")

            Files.delete(dir.resolve("token.json"))
            provider.reloadCredentials()
            assertFalse(provider.isAuthenticated)
            provider.close()
        }
}
