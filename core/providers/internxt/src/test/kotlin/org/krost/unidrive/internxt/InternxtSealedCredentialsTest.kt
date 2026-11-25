package org.krost.unidrive.internxt

import kotlinx.serialization.encodeToString
import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.UnidriveJson
import org.krost.unidrive.auth.CredentialSealKey
import org.krost.unidrive.auth.CredentialStore
import org.krost.unidrive.auth.KeyedCredentialProtector
import org.krost.unidrive.internxt.model.InternxtCredentials
import org.krost.unidrive.testing.FakePlatformProtector
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#797 (P3): the Internxt mnemonic (credentials.json) under the keyed seal. Locked, the factory's
 * credential reads refuse with the file named; unlocked, they read the credential; the existing platform-only file is
 * re-sealed on the first unlocked read, and the default factory protector is the keyed one.
 */
class InternxtSealedCredentialsTest {
    private val profileDir = TestScratch.directory("internxt-sealed-test")
    private val platform = FakePlatformProtector()
    private var current: CredentialSealKey? = null
    private val factory = InternxtProviderFactory(credentialProtector = KeyedCredentialProtector(platform) { current })
    private val credentials =
        InternxtCredentials(jwt = "jwt", mnemonic = "synthetic mnemonic for the seal test", rootFolderId = "root", email = "person@example.com")
    private val file get() = profileDir.resolve("credentials.json")

    @AfterTest
    fun tearDown() {
        profileDir.toFile().deleteRecursively()
    }

    private fun writePlatformOnly() {
        CredentialStore(profileDir, "credentials.json", InternxtCredentials.serializer(), protector = platform).save(credentials)
    }

    @Test
    fun `the mnemonic is re-sealed on the first unlocked read`() {
        writePlatformOnly()
        current = CredentialSealKey(CredentialSealKey.generateSecret(), enabled = true)
        assertEquals(CredentialHealth.Ok, factory.checkCredentialHealth(emptyMap(), profileDir))
        val onDisk = Files.readString(file)
        assertTrue(onDisk.startsWith(KeyedCredentialProtector.PREFIX), "credentials.json is sealed: ${onDisk.take(24)}")
        assertFalse(platform.decode(file.toRealPath().toString(), onDisk).plaintext.contains(credentials.mnemonic))
        assertEquals("internxt (person@example.com)", factory.describeConnection(emptyMap(), profileDir), "the re-sealed file reads back")
    }

    @Test
    fun `locked the sealed credential is refused by name, unlocked it reads`() {
        val secret = CredentialSealKey.generateSecret()
        writePlatformOnly()
        current = CredentialSealKey(secret, enabled = true)
        factory.checkCredentialHealth(emptyMap(), profileDir) // seals
        current = null
        val locked = factory.checkCredentialHealth(emptyMap(), profileDir)
        // Locked is neither signed out (Missing) nor corrupt: a front-end must not offer a sign-in it could not store.
        assertIs<CredentialHealth.Warning>(locked)
        assertTrue(locked.message.contains("credentials.json") && locked.message.contains("vault"), "names the file and the vault: ${locked.message}")
        assertTrue(factory.describeConnection(emptyMap(), profileDir).startsWith("internxt (credentials unavailable"))
        current = CredentialSealKey(secret, enabled = true)
        assertEquals(CredentialHealth.Ok, factory.checkCredentialHealth(emptyMap(), profileDir))
        assertEquals("internxt (person@example.com)", factory.describeConnection(emptyMap(), profileDir))
    }

    @Test
    fun `the default Internxt protector is the keyed seal over the platform one`() {
        assertIs<KeyedCredentialProtector>(InternxtConfig().credentialProtector)
    }

    @Test
    fun `a plaintext credentials file keeps reading without the seal`() {
        Files.writeString(file, UnidriveJson.encodeToString(credentials))
        assertEquals(CredentialHealth.Ok, factory.checkCredentialHealth(emptyMap(), profileDir))
    }
}
