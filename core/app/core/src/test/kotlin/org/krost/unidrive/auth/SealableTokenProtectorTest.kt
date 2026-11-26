package org.krost.unidrive.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#797: the protector of the OAuth token files is the keyed seal on every host, over the platform
 * default where there is one and over the owner-only file where there is none (Linux). Without a key it writes what
 * the store wrote before.
 */
class SealableTokenProtectorTest {
    @Serializable
    data class Tok(
        val accessToken: String,
        val refreshToken: String,
    )

    private lateinit var dir: Path
    private var current: CredentialSealKey? = null
    private val value = Tok("access-token-value", "refresh-token-secret")
    private val file: Path get() = dir.resolve("token.json")

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("sealable-token-protector")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun store() =
        CredentialStore(dir, "token.json", serializer<Tok>(), protector = KeyedCredentialProtector(OwnerOnlyFileProtector) { current })

    @Test
    fun `the token protector of every host is the keyed seal`() {
        for (os in listOf("Linux", "Windows 11", "Mac OS X")) {
            assertIs<KeyedCredentialProtector>(CredentialProtectors.sealableAtRestDefault(os), os)
        }
    }

    @Test
    fun `over the owner-only file an unsealed token is written as before`() {
        store().save(value)

        assertTrue("refresh-token-secret" in Files.readString(file), "no key: the owner-only plaintext file of before")
        assertEquals(value, store().load())
    }

    @Test
    fun `over the owner-only file a sealed token holds only ciphertext and is locked without the key`() {
        val secret = CredentialSealKey.generateSecret()
        current = CredentialSealKey(secret, enabled = true)
        store().save(value)
        val onDisk = Files.readString(file)
        assertTrue(onDisk.startsWith(KeyedCredentialProtector.PREFIX))
        assertFalse("refresh-token-secret" in onDisk)

        current = null
        assertFailsWith<CredentialLockedException> { store().load() }
        assertFailsWith<CredentialLockedException> { store().save(value) }
        assertEquals(onDisk, Files.readString(file), "a locked save does not downgrade the sealed file")

        current = CredentialSealKey(secret, enabled = true)
        assertEquals(value, store().load())
    }
}
