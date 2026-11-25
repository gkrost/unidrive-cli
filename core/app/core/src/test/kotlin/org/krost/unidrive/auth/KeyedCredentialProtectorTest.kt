package org.krost.unidrive.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import org.krost.unidrive.testing.FakePlatformProtector
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#797 (P3): the keyed seal over the platform protector. One test per invariant:
 * the platform layer alone recovers only ciphertext; a missing or wrong key is a [CredentialProtectionException]
 * naming the file, never "signed out"; migration runs in both directions, survives a crash mid-way, and a locked
 * process never rewrites a sealed file as platform-only.
 */
class KeyedCredentialProtectorTest {
    @Serializable
    data class Creds(
        val jwt: String,
        val mnemonic: String,
    )

    private lateinit var dir: Path
    private val platform = FakePlatformProtector()
    private var current: CredentialSealKey? = null
    private val secret = CredentialSealKey.generateSecret()
    private val value = Creds(jwt = "jwt-value", mnemonic = "synthetic mnemonic words for the seal test")

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("keyed-protector-test")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun keyed(): KeyedCredentialProtector = KeyedCredentialProtector(platform) { current }

    private fun store(protector: CredentialProtector? = keyed()): CredentialStore<Creds> =
        CredentialStore(dir, "credentials.json", serializer<Creds>(), protector = protector)

    private val file: Path get() = dir.resolve("credentials.json")

    private fun onDisk(): String = Files.readString(file)

    private fun unlock(enabled: Boolean = true) {
        current = CredentialSealKey(secret, enabled)
    }

    @Test
    fun `a sealed file cannot be recovered with the platform protector alone`() {
        unlock()
        store().save(value)
        assertTrue(onDisk().startsWith(KeyedCredentialProtector.PREFIX), "the on-disk marker is the keyed one: ${onDisk().take(24)}")
        val platformOnly = platform.decode(file.toRealPath().toString(), onDisk()).plaintext
        assertFalse(platformOnly.contains(value.mnemonic), "the platform layer alone must not yield the mnemonic")
        val e = assertFailsWith<CredentialProtectionException> { store(platform).load() }
        assertTrue(e.message!!.contains("credentials.json"), "the refusal names the file: ${e.message}")
    }

    @Test
    fun `a sealed file without the vault key is a protection error naming the file, not signed out`() {
        unlock()
        store().save(value)
        current = null
        val e = assertFailsWith<CredentialProtectionException> { store().load() }
        assertTrue(e.message!!.contains("credentials.json"), "the refusal names the file: ${e.message}")
        assertTrue(e.message!!.contains("vault.unlock"), "the refusal says how to unlock: ${e.message}")
    }

    @Test
    fun `a sealed file under a wrong key is refused naming the file`() {
        unlock()
        store().save(value)
        current = CredentialSealKey(CredentialSealKey.generateSecret(), enabled = true)
        val e = assertFailsWith<CredentialProtectionException> { store().load() }
        assertTrue(e.message!!.contains("credentials.json"), "the refusal names the file: ${e.message}")
        assertTrue(onDisk().startsWith(KeyedCredentialProtector.PREFIX), "a wrong key never rewrites the file")
    }

    @Test
    fun `an unlocked load reads back what was sealed`() {
        unlock()
        store().save(value)
        assertEquals(value, store().load())
    }

    @Test
    fun `a platform-only file is re-sealed on the first unlocked load`() {
        store(platform).save(value)
        assertTrue(onDisk().startsWith(FakePlatformProtector.PREFIX))
        unlock()
        assertEquals(value, store().load())
        assertTrue(onDisk().startsWith(KeyedCredentialProtector.PREFIX), "migrated up: ${onDisk().take(24)}")
        assertEquals(value, store().load(), "the re-sealed file reads back")
        assertNoResidue()
    }

    @Test
    fun `a plaintext legacy file is sealed on the first unlocked load`() {
        Files.writeString(file, """{"jwt":"jwt-value","mnemonic":"${value.mnemonic}"}""")
        unlock()
        assertEquals(value, store().load())
        assertTrue(onDisk().startsWith(KeyedCredentialProtector.PREFIX))
        assertFalse(onDisk().contains(value.mnemonic))
        assertEquals(value, store().load(), "the sealed file reads back")
    }

    @Test
    fun `turning sealing off rewrites the file platform-only on the next unlocked load`() {
        unlock()
        store().save(value)
        unlock(enabled = false)
        assertEquals(value, store().load())
        assertTrue(onDisk().startsWith(FakePlatformProtector.PREFIX), "migrated down: ${onDisk().take(24)}")
        assertEquals(value, store(platform).load(), "the platform protector alone reads it again")
        assertNoResidue()
    }

    @Test
    fun `a crash during migration keeps the old file and the next unlocked load finishes it`() {
        store(platform).save(value)
        val before = onDisk()
        unlock()
        platform.failProtects = 1
        assertFailsWith<CredentialProtectionException> { store().load() }
        assertEquals(before, onDisk(), "the interrupted migration left the old file untouched")
        assertNoResidue()
        assertEquals(value, store().load())
        assertTrue(onDisk().startsWith(KeyedCredentialProtector.PREFIX), "the retry sealed it")
        assertEquals(value, store().load(), "the re-sealed file reads back")
    }

    @Test
    fun `a locked process does not rewrite a sealed file platform-only`() {
        unlock()
        store().save(value)
        val sealed = onDisk()
        current = null
        assertFailsWith<CredentialProtectionException> { store().save(value.copy(jwt = "refreshed")) }
        assertEquals(sealed, onDisk(), "the sealed file is unchanged")
    }

    @Test
    fun `without a vault key and no sealed file the platform protection is unchanged`() {
        store().save(value)
        assertTrue(onDisk().startsWith(FakePlatformProtector.PREFIX))
        assertEquals(value, store().load())
        assertTrue(onDisk().startsWith(FakePlatformProtector.PREFIX), "no migration without a key")
    }

    @Test
    fun `lock after unlock refuses the read again`() {
        unlock()
        store().save(value)
        assertEquals(value, store().load())
        current = null
        assertFailsWith<CredentialProtectionException> { store().load() }
    }

    private fun assertNoResidue() {
        val names = Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() }
        assertEquals(listOf("credentials.json"), names, "no temp or backup copy left behind")
    }
}
