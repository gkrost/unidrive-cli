package org.krost.unidrive.dropbox

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CompleteAuthResult
import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.auth.CredentialData
import org.krost.unidrive.auth.CredentialProtector
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
 * The provider factory is where a profile's Dropbox config is built in production (issue#83 B): the refresh token it
 * signs in with, the pending no-redirect flow and the token the status commands read must all go through the protector
 * the factory was given, not sit as plaintext JSON next to it.
 */
class DropboxFactoryProtectionTest {
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
    private val profile = mapOf("client_id" to "app-key-from-profile")
    private val tokenFile: Path get() = dir.resolve(DropboxAuth.TOKEN_FILE)
    private val pendingFile: Path get() = dir.resolve(DropboxProviderFactory.PENDING_FLOW_FILE)

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ud-dropbox-factory-protection")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    /** A token answer the fake endpoint grants; the same fake must back the factory the sign-in runs through. */
    private val tokenResponse =
        """{"access_token":"${"a".repeat(40)}","expires_in":14400,"refresh_token":"refresh-token-secret","account_id":"dbid:a"}"""

    private fun protectedFactory(fake: FakeDropbox): DropboxProviderFactory =
        object : DropboxProviderFactory(protector) {
            override fun newHttpClientForBegin() = io.ktor.client.HttpClient(fake.engine)
        }

    /** Runs a begin → complete sign-in whose exchange succeeds against [fake]. */
    private fun completeSignIn(fake: FakeDropbox): DropboxProviderFactory {
        fake.json("oauth2/token", tokenResponse)
        return object : DropboxProviderFactory(protector) {
            override fun newHttpClientForBegin() = io.ktor.client.HttpClient(fake.engine)
        }
    }

    private fun beginAndComplete(factory: DropboxProviderFactory) =
        runTest {
            val begun = factory.beginInteractiveAuth(dir, profile)
            val outcome = factory.completeInteractiveAuth(dir, begun.continuationHandle, "the-pasted-code", profile)
            assertIs<CompleteAuthResult.Success>(outcome)
        }

    @Test
    fun `the grant a sign-in completes is written through the factory's protector`() {
        val factory = completeSignIn(FakeDropbox())

        beginAndComplete(factory)

        assertEquals(FakeProtector.REFERENCE, Files.readString(tokenFile))
        assertFalse("refresh-token-secret" in Files.readString(tokenFile))
    }

    @Test
    fun `the pending no-redirect flow is written through the factory's protector`() {
        val factory = protectedFactory(FakeDropbox())

        runTest { factory.beginInteractiveAuth(dir, profile) }

        assertEquals(FakeProtector.REFERENCE, Files.readString(pendingFile))
        assertFalse("verifier" in Files.readString(pendingFile))
    }

    @Test
    fun `status reads a protected token through the protector`() {
        val factory = completeSignIn(FakeDropbox())
        beginAndComplete(factory)

        assertEquals(CredentialHealth.Ok, factory.checkCredentialHealth(profile, dir))
    }

    @Test
    fun `a plaintext token from before is still read, and protected by the health check`() {
        Files.writeString(
            dir.resolve(DropboxAuth.TOKEN_FILE),
            org.krost.unidrive.UnidriveJson.encodeToString(
                DropboxToken.serializer(),
                DropboxToken("a".repeat(40), 0, "refresh-token-secret"),
            ),
        )
        val factory = protectedFactory(FakeDropbox())

        assertEquals(CredentialHealth.Ok, factory.checkCredentialHealth(profile, dir))

        assertEquals(FakeProtector.REFERENCE, Files.readString(tokenFile))
    }

    @Test
    fun `without a protector the owner-only plaintext file is kept`() {
        val fake = FakeDropbox()
        fake.json("oauth2/token", tokenResponse)
        val factory =
            object : DropboxProviderFactory(null) {
                override fun newHttpClientForBegin() = io.ktor.client.HttpClient(fake.engine)
            }
        beginAndComplete(factory)

        assertTrue("refresh-token-secret" in Files.readString(tokenFile))
        assertIs<CredentialHealth.Ok>(factory.checkCredentialHealth(profile, dir))
    }

    @Test
    fun `a token DropboxAuth saves rides the config's protector`() =
        runTest {
            val auth =
                DropboxAuth(
                    DropboxConfig(appKey = "key", tokenPath = dir, credentialProtector = protector),
                    io.ktor.client.HttpClient(FakeDropbox().engine),
                )

            auth.saveToken(DropboxToken("a".repeat(40), System.currentTimeMillis() + 14_400_000, "refresh-token-secret"))

            assertEquals(FakeProtector.REFERENCE, Files.readString(tokenFile))
            assertFalse("refresh-token-secret" in Files.readString(tokenFile))
        }
}
