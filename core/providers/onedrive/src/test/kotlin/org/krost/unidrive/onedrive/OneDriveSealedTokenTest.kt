package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.CompleteAuthResult
import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.auth.CredentialLockedException
import org.krost.unidrive.auth.CredentialSealKey
import org.krost.unidrive.auth.CredentialStore
import org.krost.unidrive.auth.KeyedCredentialProtector
import org.krost.unidrive.onedrive.model.Token
import org.krost.unidrive.testing.FakePlatformProtector
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#797: OneDrive's token.json and the pending device flow (device-flow.json) under the vault-key seal,
 * inside the platform layer. One test per invariant: re-sealed on the first unlocked read; locked is its own health,
 * neither signed out nor unparseable; the profile needs the vault; a refresh in a locked process never downgrades the
 * file; the real device-code sign-in (begin, then complete) replaces a sealed file without the key.
 */
class OneDriveSealedTokenTest {
    private val dir: Path = TestScratch.directory("onedrive-sealed-token")
    private val platform = FakePlatformProtector()
    private var current: CredentialSealKey? = null
    private val protector = KeyedCredentialProtector(platform) { current }
    private val secret = CredentialSealKey.generateSecret()
    private val tokenFile get() = dir.resolve("token.json")
    private val pendingFile get() = dir.resolve(PENDING_DEVICE_FLOW_FILE)

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun unlock() {
        current = CredentialSealKey(secret, enabled = true)
    }

    private fun token(refresh: String = "refresh-token-secret") =
        Token(accessToken = "A".repeat(64), tokenType = "Bearer", expiresAt = System.currentTimeMillis() + 3_600_000L, refreshToken = refresh)

    private fun tokenStore(p: org.krost.unidrive.auth.CredentialProtector = protector) =
        CredentialStore(dir, "token.json", Token.serializer(), protector = p)

    private fun writeSealedToken(refresh: String = "refresh-token-secret") {
        unlock()
        tokenStore().save(token(refresh))
        assertTrue(Files.readString(tokenFile).startsWith(KeyedCredentialProtector.PREFIX))
    }

    /** /devicecode, then /token answering a fresh grant. */
    private fun deviceCodeThenTokenEngine(): MockEngine {
        var call = 0
        val json = headersOf("Content-Type" to listOf("application/json"))
        return MockEngine { _ ->
            call += 1
            when (call) {
                1 ->
                    respond(
                        ByteReadChannel(
                            """{"device_code":"DC-1","user_code":"U-1","verification_uri":"https://microsoft.com/devicelogin",""" +
                                """"expires_in":900,"interval":1,"message":"sign in"}""",
                        ),
                        HttpStatusCode.OK,
                        json,
                    )
                else ->
                    respond(
                        ByteReadChannel(
                            """{"access_token":"${"x".repeat(200)}","token_type":"Bearer","expires_in":3600,""" +
                                """"refresh_token":"rt-fresh","scope":"Files.ReadWrite.All offline_access openid"}""",
                        ),
                        HttpStatusCode.OK,
                        json,
                    )
            }
        }
    }

    private fun factory(engine: MockEngine = deviceCodeThenTokenEngine()): OneDriveProviderFactory =
        object : OneDriveProviderFactory(protector) {
            override fun newOAuthServiceForBegin(
                profileDir: Path,
                config: OneDriveConfig,
            ): OAuthService = OAuthService(config, HttpClient(engine))

            override fun newGraphApiForIdentity(
                profileDir: Path,
                token: Token,
            ): GraphApiService =
                GraphApiService(
                    OneDriveConfig(tokenPath = profileDir),
                    engine = MockEngine { respond(ByteReadChannel(""), HttpStatusCode.NotFound) },
                ) { _, _ -> "t" }
        }

    @Test
    fun `token json is re-sealed on the first unlocked read`() {
        tokenStore(platform).save(token())
        unlock()

        assertEquals(CredentialHealth.Ok, factory().checkCredentialHealth(emptyMap(), dir))

        val onDisk = Files.readString(tokenFile)
        assertTrue(onDisk.startsWith(KeyedCredentialProtector.PREFIX), "token.json is sealed: ${onDisk.take(24)}")
        val platformOnly = platform.decode(tokenFile.toRealPath().toString(), onDisk.removePrefix(KeyedCredentialProtector.PREFIX))
        assertFalse("refresh-token-secret" in platformOnly.plaintext, "the platform layer alone holds only ciphertext")
    }

    @Test
    fun `a sealed token json read locked is a locked health, not signed out or unparseable`() {
        writeSealedToken()
        current = null

        val health = factory().checkCredentialHealth(emptyMap(), dir)

        assertIs<CredentialHealth.Warning>(health)
        assertTrue("token.json" in health.message && "vault" in health.message, "names the file and the vault: ${health.message}")
        assertFalse("could not be parsed" in health.message, health.message)
        unlock()
        assertEquals(CredentialHealth.Ok, factory().checkCredentialHealth(emptyMap(), dir))
    }

    @Test
    fun `a profile with a sealed token json needs the vault`() {
        tokenStore(platform).save(token())
        assertFalse(factory().needsCredentialSeal(dir))
        writeSealedToken()

        assertTrue(factory().needsCredentialSeal(dir))
    }

    @Test
    fun `a refresh's save in a locked process never downgrades a sealed token json`() {
        writeSealedToken()
        val before = Files.readString(tokenFile)
        current = null

        OAuthService(OneDriveConfig(tokenPath = dir, credentialProtector = protector)).use { oauth ->
            assertFailsWith<CredentialLockedException> { runBlocking { oauth.saveToken(token("rotated")) } }
        }

        assertEquals(before, Files.readString(tokenFile))
        unlock()
        assertEquals("refresh-token-secret", tokenStore().load()?.refreshToken)
    }

    @Test
    fun `a device-code sign-in without the key replaces a sealed token json`() {
        writeSealedToken()
        current = null
        val factory = factory()

        val outcome =
            runBlocking {
                val begun = factory.beginInteractiveAuth(dir, emptyMap())
                factory.completeInteractiveAuth(dir, begun.continuationHandle, null, emptyMap())
            }

        assertIs<CompleteAuthResult.Success>(outcome, "$outcome")
        assertTrue(Files.readString(tokenFile).startsWith(FakePlatformProtector.PREFIX), "platform-only until an unlocked read")
        assertEquals("rt-fresh", tokenStore().load()?.refreshToken)
    }

    @Test
    fun `a device-code begin without the key replaces a sealed pending flow`() {
        unlock()
        val first = runBlocking { factory().beginInteractiveAuth(dir, emptyMap()) }
        OneDriveDeviceFlowRegistry.remove(first.continuationHandle)?.oauthService?.close()
        assertTrue(Files.readString(pendingFile).startsWith(KeyedCredentialProtector.PREFIX), "the pending device flow is sealed")
        current = null

        val begun = runBlocking { factory().beginInteractiveAuth(dir, emptyMap()) }

        assertTrue(Files.readString(pendingFile).startsWith(FakePlatformProtector.PREFIX))
        OneDriveDeviceFlowRegistry.remove(begun.continuationHandle)?.oauthService?.close()
    }

    @Test
    fun `completing a sealed pending flow in a locked process refuses as locked`() {
        unlock()
        val begun = runBlocking { factory().beginInteractiveAuth(dir, emptyMap()) }
        // Another process: the flow is only on disk.
        OneDriveDeviceFlowRegistry.remove(begun.continuationHandle)?.oauthService?.close()
        current = null

        val e =
            assertFailsWith<CredentialLockedException> {
                runBlocking { factory().completeInteractiveAuth(dir, begun.continuationHandle, null, emptyMap()) }
            }
        assertTrue(PENDING_DEVICE_FLOW_FILE in e.message.orEmpty(), e.message)
    }

    @Test
    fun `the default OneDrive protector is the keyed seal`() {
        val provider = OneDriveProviderFactory().create(emptyMap(), dir) as OneDriveProvider
        assertIs<KeyedCredentialProtector>(provider.config.credentialProtector)
    }
}
