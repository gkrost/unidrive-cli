package org.krost.unidrive.onedrive

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.auth.CredentialData
import org.krost.unidrive.auth.CredentialProtector
import org.krost.unidrive.onedrive.model.Token
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The provider factory is where a profile's OneDrive config is built in production (#83 B, archive#1261): the refresh
 * token it signs in with, the pending device flow and the token the status commands read must all go through the
 * protector the factory was given, not sit as plaintext JSON next to it.
 */
class OneDriveFactoryProtectionTest {
    /** Keeps the plaintext in memory and puts only a reference on disk; anything else decodes as legacy plaintext. */
    private class FakeProtector : CredentialProtector {
        val values = mutableMapOf<String, String>()

        override fun protect(
            id: String,
            plaintext: String,
        ): String {
            values[id] = plaintext
            return REFERENCE
        }

        override fun decode(
            id: String,
            stored: String,
        ): CredentialData = if (stored == REFERENCE) CredentialData(values.getValue(id), true) else CredentialData(stored, false)

        override fun delete(id: String) {
            values.remove(id)
        }

        companion object {
            const val REFERENCE = "protected-test-reference"
        }
    }

    private lateinit var dir: Path
    private val protector = FakeProtector()
    private val tokenFile: Path get() = dir.resolve("token.json")

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ud-onedrive-factory-protection")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun token() =
        Token(
            accessToken = "A".repeat(64),
            tokenType = "Bearer",
            expiresAt = System.currentTimeMillis() + 3_600_000L,
            refreshToken = "refresh-token-secret",
            scope = OAuthService.SCOPES,
        )

    @Test
    fun `the token a sign-in saves is written through the factory's protector`() =
        runTest {
            val factory = OneDriveProviderFactory(protector)

            factory.newOAuthServiceForBegin(dir).use { it.saveToken(token()) }

            assertEquals(FakeProtector.REFERENCE, Files.readString(tokenFile))
            assertFalse("refresh-token-secret" in Files.readString(tokenFile))
        }

    @Test
    fun `status reads a protected token through the protector`() =
        runTest {
            val factory = OneDriveProviderFactory(protector)
            factory.newOAuthServiceForBegin(dir).use { it.saveToken(token()) }

            assertEquals(CredentialHealth.Ok, factory.checkCredentialHealth(emptyMap(), dir))
            assertTrue("token expires" in factory.describeConnection(emptyMap(), dir), factory.describeConnection(emptyMap(), dir))
        }

    @Test
    fun `a plaintext token from before is still read, and protected by the first load`() =
        runTest {
            OneDriveProviderFactory(null).newOAuthServiceForBegin(dir).use { it.saveToken(token()) }
            assertTrue("refresh-token-secret" in Files.readString(tokenFile))
            val factory = OneDriveProviderFactory(protector)

            assertEquals(CredentialHealth.Ok, factory.checkCredentialHealth(emptyMap(), dir))

            assertEquals(FakeProtector.REFERENCE, Files.readString(tokenFile))
        }

    @Test
    fun `without a protector the owner-only plaintext file is kept`() =
        runTest {
            val factory = OneDriveProviderFactory(null)

            factory.newOAuthServiceForBegin(dir).use { it.saveToken(token()) }

            assertTrue("refresh-token-secret" in Files.readString(tokenFile))
            assertIs<CredentialHealth.Ok>(factory.checkCredentialHealth(emptyMap(), dir))
        }
}
