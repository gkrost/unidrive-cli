package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * archive#1261: OneDrive's token.json goes through [OneDriveConfig.credentialProtector], as
 * Internxt's credentials do. One named test per invariant:
 *  1. a token saved with a protector loads back, and the file holds only the protector's reference;
 *  2. a plaintext token.json from before still loads, and loading it rewrites it protected;
 *  3. a token renewed by a refresh is written protected;
 *  4. without a protector the file stays plain JSON (the desktop default is unchanged);
 *  5. signing out removes the protected copy too.
 */
class OneDriveCredentialProtectorTest {
    /** Keeps the plaintext in memory and puts only a reference on disk; anything else decodes as legacy plaintext. */
    private class FakeProtector : CredentialProtector {
        val values = mutableMapOf<String, String>()

        override fun protect(id: String, plaintext: String): String {
            values[id] = plaintext
            return REFERENCE
        }

        override fun decode(id: String, stored: String): CredentialData =
            if (stored == REFERENCE) CredentialData(values.getValue(id), true) else CredentialData(stored, false)

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
        dir = Files.createTempDirectory("ud-onedrive-protector")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun token(
        refreshToken: String = "refresh-token-secret",
        expiresAt: Long = System.currentTimeMillis() + 3_600_000L,
    ) = Token(
        accessToken = "A".repeat(64),
        tokenType = "Bearer",
        expiresAt = expiresAt,
        refreshToken = refreshToken,
        scope = OAuthService.SCOPES,
    )

    private fun protectedConfig() = OneDriveConfig(tokenPath = dir, credentialProtector = protector)

    @Test
    fun `token saved through a protector loads back and only the reference is on disk`() =
        runTest {
            val saved = token()
            OAuthService(protectedConfig()).use { it.saveToken(saved) }

            assertEquals(FakeProtector.REFERENCE, Files.readString(tokenFile))
            assertEquals(saved, OAuthService(protectedConfig()).use { it.loadToken() })
        }

    @Test
    fun `plaintext token json is loaded and rewritten protected when a protector is set`() =
        runTest {
            val legacy = token()
            OAuthService(OneDriveConfig(tokenPath = dir)).use { it.saveToken(legacy) }
            assertTrue(Files.readString(tokenFile).contains("refresh-token-secret"))

            assertEquals(legacy, OAuthService(protectedConfig()).use { it.loadToken() })

            assertEquals(FakeProtector.REFERENCE, Files.readString(tokenFile))
            assertEquals(legacy, OAuthService(protectedConfig()).use { it.loadToken() })
        }

    @Test
    fun `refreshed token is written protected`() =
        runTest {
            OAuthService(protectedConfig()).use { it.saveToken(token(refreshToken = "old-refresh", expiresAt = 0L)) }
            val engine =
                MockEngine {
                    respond(
                        content = """{"access_token":"${"B".repeat(64)}","token_type":"Bearer","expires_in":3600,"refresh_token":"rotated-refresh"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val config = protectedConfig()
            OAuthService(config, HttpClient(engine)).use { oauth ->
                val manager = TokenManager(config, oauth)
                manager.initialize()
                assertEquals("rotated-refresh", manager.getValidToken().refreshToken)
            }

            val onDisk = Files.readString(tokenFile)
            assertEquals(FakeProtector.REFERENCE, onDisk)
            assertFalse(onDisk.contains("rotated-refresh"))
            assertEquals("rotated-refresh", OAuthService(protectedConfig()).use { it.loadToken() }?.refreshToken)
        }

    @Test
    fun `without a protector token json stays plain json`() =
        runTest {
            assertNull(OneDriveConfig().credentialProtector)
            val saved = token()
            OAuthService(OneDriveConfig(tokenPath = dir)).use { it.saveToken(saved) }

            val json = Json { ignoreUnknownKeys = true }
            assertEquals(saved, json.decodeFromString<Token>(Files.readString(tokenFile)))
            assertEquals(saved, OAuthService(OneDriveConfig(tokenPath = dir)).use { it.loadToken() })
        }

    @Test
    fun `logout removes the token file and its protected copy`() =
        runTest {
            val config = protectedConfig()
            OAuthService(config).use { oauth ->
                oauth.saveToken(token())
                val manager = TokenManager(config, oauth)
                manager.initialize()
                manager.logout()
            }

            assertFalse(Files.exists(tokenFile))
            assertTrue(protector.values.isEmpty())
        }
}
