package org.krost.unidrive.dropbox

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.CompleteAuthResult
import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.auth.CredentialLockedException
import org.krost.unidrive.auth.CredentialSeal
import org.krost.unidrive.auth.CredentialSealKey
import org.krost.unidrive.auth.CredentialStore
import org.krost.unidrive.auth.KeyedCredentialProtector
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
 * gkrost/unidrive-cli#797: Dropbox's token.json and the pending no-redirect sign-in (pending_dropbox_flow.json) under the
 * vault-key seal, inside the platform layer. One test per invariant, as OneDriveSealedTokenTest.
 */
class DropboxSealedTokenTest {
    private val dir: Path = TestScratch.directory("dropbox-sealed-token")
    private val platform = FakePlatformProtector()
    private var current: CredentialSealKey? = null
    private val protector = KeyedCredentialProtector(platform) { current }
    private val secret = CredentialSealKey.generateSecret()
    private val profile = mapOf("client_id" to "app-key-from-profile")
    private val tokenFile get() = dir.resolve(DropboxAuth.TOKEN_FILE)
    private val pendingFile get() = dir.resolve(DropboxProviderFactory.PENDING_FLOW_FILE)
    private val tokenResponse =
        """{"access_token":"${"a".repeat(40)}","expires_in":14400,"refresh_token":"rt-fresh","account_id":"dbid:a"}"""

    @AfterTest
    fun tearDown() {
        CredentialSeal.clear()
        dir.toFile().deleteRecursively()
    }

    private fun unlock() {
        current = CredentialSealKey(secret, enabled = true)
    }

    private fun token(refresh: String = "refresh-token-secret") = DropboxToken("a".repeat(40), System.currentTimeMillis() + 3_600_000, refresh, "dbid:a")

    private fun tokenStore(p: org.krost.unidrive.auth.CredentialProtector = protector) =
        CredentialStore(dir, DropboxAuth.TOKEN_FILE, DropboxToken.serializer(), protector = p)

    private fun writeSealedToken() {
        unlock()
        tokenStore().save(token())
        assertTrue(Files.readString(tokenFile).startsWith(KeyedCredentialProtector.PREFIX))
    }

    private fun factory(
        fake: FakeDropbox = FakeDropbox().also { it.json("oauth2/token", tokenResponse) },
        p: org.krost.unidrive.auth.CredentialProtector = protector,
    ): DropboxProviderFactory =
        object : DropboxProviderFactory(p) {
            override fun newHttpClientForBegin() = io.ktor.client.HttpClient(fake.engine)
        }

    @Test
    fun `token json is re-sealed on the first unlocked read`() {
        tokenStore(platform).save(token())
        unlock()

        assertEquals(CredentialHealth.Ok, factory().checkCredentialHealth(profile, dir))

        val onDisk = Files.readString(tokenFile)
        assertTrue(onDisk.startsWith(KeyedCredentialProtector.PREFIX), "token.json is sealed: ${onDisk.take(24)}")
        val platformOnly = platform.decode(tokenFile.toRealPath().toString(), onDisk.removePrefix(KeyedCredentialProtector.PREFIX))
        assertFalse("refresh-token-secret" in platformOnly.plaintext, "the platform layer alone holds only ciphertext")
    }

    @Test
    fun `a sealed token json read locked is a locked health, not signed out or unparseable`() {
        writeSealedToken()
        current = null

        val health = factory().checkCredentialHealth(profile, dir)

        assertIs<CredentialHealth.Warning>(health)
        assertTrue("token.json" in health.message && "vault" in health.message, "names the file and the vault: ${health.message}")
        assertFalse("could not be parsed" in health.message, health.message)
        unlock()
        assertEquals(CredentialHealth.Ok, factory().checkCredentialHealth(profile, dir))
    }

    @Test
    fun `a profile with a sealed token json needs the vault`() {
        tokenStore(platform).save(token())
        assertFalse(factory().needsCredentialSeal(dir))
        writeSealedToken()

        assertTrue(factory().needsCredentialSeal(dir))
    }

    @Test
    fun `a refresh in a locked process never downgrades a sealed token json`() {
        writeSealedToken()
        val before = Files.readString(tokenFile)
        val fake = FakeDropbox()
        fake.json("oauth2/token", """{"access_token":"${"b".repeat(40)}","expires_in":14400,"refresh_token":"rotated","account_id":"dbid:a"}""")
        val auth = DropboxAuth(DropboxConfig(appKey = "key", tokenPath = dir, credentialProtector = protector), io.ktor.client.HttpClient(fake.engine))
        runBlocking { auth.load() }
        current = null

        assertFailsWith<CredentialLockedException> { runBlocking { auth.accessToken(forceRefresh = true) } }

        assertEquals(before, Files.readString(tokenFile))
        unlock()
        assertEquals("refresh-token-secret", tokenStore().load()?.refreshToken)
    }

    @Test
    fun `a sign-in without the key replaces a sealed token json`() {
        writeSealedToken()
        current = null
        val factory = factory()

        val outcome =
            runBlocking {
                val begun = factory.beginInteractiveAuth(dir, profile)
                factory.completeInteractiveAuth(dir, begun.continuationHandle, "the-pasted-code", profile)
            }

        assertIs<CompleteAuthResult.Success>(outcome, "$outcome")
        assertTrue(Files.readString(tokenFile).startsWith(FakePlatformProtector.PREFIX), "platform-only until an unlocked read")
        assertEquals("rt-fresh", tokenStore().load()?.refreshToken)
    }

    @Test
    fun `a begin without the key replaces a sealed pending flow`() {
        unlock()
        runBlocking { factory().beginInteractiveAuth(dir, profile) }
        assertTrue(Files.readString(pendingFile).startsWith(KeyedCredentialProtector.PREFIX), "the pending sign-in is sealed")
        current = null

        runBlocking { factory().beginInteractiveAuth(dir, profile) }

        assertTrue(Files.readString(pendingFile).startsWith(FakePlatformProtector.PREFIX))
    }

    @Test
    fun `completing a sealed pending flow in a locked process refuses as locked`() {
        unlock()
        val begun = runBlocking { factory().beginInteractiveAuth(dir, profile) }
        current = null

        val e =
            assertFailsWith<CredentialLockedException> {
                runBlocking { factory().completeInteractiveAuth(dir, begun.continuationHandle, "the-pasted-code", profile) }
            }
        assertTrue(DropboxProviderFactory.PENDING_FLOW_FILE in e.message.orEmpty(), e.message)
    }

    @Test
    fun `the default Dropbox protector is the keyed seal`() {
        CredentialSeal.install(CredentialSealKey(secret, enabled = true))
        val fake = FakeDropbox().also { it.json("oauth2/token", tokenResponse) }
        val factory =
            object : DropboxProviderFactory() {
                override fun newHttpClientForBegin() = io.ktor.client.HttpClient(fake.engine)
            }

        runBlocking {
            val begun = factory.beginInteractiveAuth(dir, profile)
            assertIs<CompleteAuthResult.Success>(factory.completeInteractiveAuth(dir, begun.continuationHandle, "the-pasted-code", profile))
        }

        assertTrue(Files.readString(tokenFile).startsWith(KeyedCredentialProtector.PREFIX), "sealed under the process's key")
    }
}
