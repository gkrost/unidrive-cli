package org.krost.unidrive.sync

import org.krost.unidrive.io.OwnerOnly
import org.krost.unidrive.io.grantProblem
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

class VaultTest {
    private fun tempVaultPath() = Files.createFile(TestScratch.directory("vault-test").resolve("vault.enc"))

    @Test
    fun `init creates vault file`() {
        val path = tempVaultPath()
        Files.deleteIfExists(path)
        val vault = Vault(path)
        assertFalse(vault.exists())
        vault.init("testpassword".toCharArray())
        assertTrue(vault.exists())
        Files.deleteIfExists(path)
    }

    @Test
    fun `read after write round-trips credentials`() {
        val path = tempVaultPath()
        val vault = Vault(path)
        val pass = "my-passphrase!".toCharArray()
        val data =
            mapOf(
                "hetzner-s3" to mapOf("access_key_id" to "AKIA123", "secret_access_key" to "secret"),
                "my-sftp" to mapOf("password" to "hunter2"),
            )
        vault.write(pass, data)
        val read = vault.read("my-passphrase!".toCharArray())
        assertEquals(data, read)
        Files.deleteIfExists(path)
    }

    @Test
    fun `wrong passphrase throws AEADBadTagException`() {
        val path = tempVaultPath()
        val vault = Vault(path)
        vault.write("correct-pass".toCharArray(), mapOf("p" to mapOf("k" to "v")))
        assertFails {
            vault.read("wrong-pass!".toCharArray())
        }.also { e ->
            assertTrue(
                e is AEADBadTagException || e.cause is AEADBadTagException,
                "Expected AEADBadTagException but got ${e::class.simpleName}",
            )
        }
        Files.deleteIfExists(path)
    }

    @Test
    fun `empty vault round-trips to empty map`() {
        val path = tempVaultPath()
        val vault = Vault(path)
        val pass = "testpassword".toCharArray()
        vault.init(pass)
        val data = vault.read("testpassword".toCharArray())
        assertEquals(emptyMap(), data)
        Files.deleteIfExists(path)
    }

    @Test
    fun `multiple profiles coexist`() {
        val path = tempVaultPath()
        val vault = Vault(path)
        val pass = "longpassphrase".toCharArray()
        val data =
            mapOf(
                "profile-a" to mapOf("key1" to "val1"),
                "profile-b" to mapOf("key2" to "val2", "key3" to "val3"),
                "profile-c" to mapOf("password" to "abc"),
            )
        vault.write(pass, data)
        assertEquals(data, vault.read("longpassphrase".toCharArray()))
        Files.deleteIfExists(path)
    }

    @Test
    fun `delete removes file`() {
        val path = tempVaultPath()
        val vault = Vault(path)
        vault.init("testpassword".toCharArray())
        assertTrue(vault.exists())
        vault.delete()
        assertFalse(vault.exists())
    }

    @Test
    fun `minimum passphrase length enforced`() {
        val path = tempVaultPath()
        Files.deleteIfExists(path)
        val vault = Vault(path)
        assertFails {
            vault.init("short".toCharArray())
        }.also { e ->
            assertTrue(e is IllegalArgumentException)
            assertTrue(e.message!!.contains("at least"))
        }
        Files.deleteIfExists(path)
    }

    @Test
    fun `overwrite vault with new data`() {
        val path = tempVaultPath()
        val vault = Vault(path)
        val pass = "testpassword".toCharArray()

        vault.write(pass, mapOf("old" to mapOf("k" to "v")))
        vault.write("testpassword".toCharArray(), mapOf("new" to mapOf("k2" to "v2")))

        val read = vault.read("testpassword".toCharArray())
        assertEquals(mapOf("new" to mapOf("k2" to "v2")), read)
        assertFalse(read.containsKey("old"))
        Files.deleteIfExists(path)
    }

    @Test
    fun `a reader of an older generation leaves the newer X-tra marker intact`() {
        val path = vaultFolder().resolve("vault.enc")
        val pass = "testpassword".toCharArray()
        val reader = Vault(path)
        val writer = Vault(path)
        val withXtra = mapOf(Vault.XTRA_ENTRY to mapOf("passphrase" to "test-xtra-password"))
        writer.write(pass, emptyMap())

        val decrypted = CountDownLatch(1)
        val resume = CountDownLatch(1)
        Executors.newSingleThreadExecutor().use { worker ->
            val oldRead = worker.submit<Map<String, Map<String, String>>> {
                reader.read(pass) {
                    decrypted.countDown()
                    check(resume.await(10, TimeUnit.SECONDS))
                }
            }
            try {
                assertTrue(decrypted.await(5, TimeUnit.SECONDS))
                writer.write(pass, withXtra)
                assertTrue(writer.carriesXtra)
            } finally {
                resume.countDown()
            }
            assertTrue(oldRead.get(5, TimeUnit.SECONDS).isEmpty())
            assertTrue(writer.carriesXtra)
        }
    }

    // -- archive#1053: the write is atomic and owner-only from the first byte -----------------------------------------

    private val oldData = mapOf("old" to mapOf("secret_access_key" to "old-secret"))
    private val newData = mapOf("new" to mapOf("secret_access_key" to "new-secret"))

    /** A folder of its own, so the temp files of a write can be counted. */
    private fun vaultFolder(): Path = TestScratch.directory("vault-atomic-")

    private fun filesIn(folder: Path): List<String> = Files.list(folder).use { stream -> stream.map { it.fileName.toString() }.sorted().toList() }

    @Test
    fun `a write that dies after a partial write leaves the old vault readable`() {
        val folder = vaultFolder()
        val path = folder.resolve("vault.enc")
        Vault(path).write("old-passphrase".toCharArray(), oldData)
        val before = Files.readAllBytes(path)

        val crashing =
            Vault(path) { tmp ->
                // Half the new vault reached the disk, then the process died (here: the exception ends the write).
                val half = Files.readAllBytes(tmp).copyOf(40)
                Files.write(tmp, half)
                throw IOException("simulated crash")
            }
        assertFailsWith<IOException> { crashing.write("new-passphrase".toCharArray(), newData) }

        assertEquals(oldData, Vault(path).read("old-passphrase".toCharArray()), "the old vault still decrypts")
        assertTrue(before.contentEquals(Files.readAllBytes(path)), "and is byte for byte what it was")
        assertEquals(listOf("vault.enc", "vault.enc.lock"), filesIn(folder), "no temp file is left behind")
        folder.toFile().deleteRecursively()
    }

    @Test
    fun `a new vault that does not decrypt is refused before it replaces the old one`() {
        val folder = vaultFolder()
        val path = folder.resolve("vault.enc")
        Vault(path).write("old-passphrase".toCharArray(), oldData)

        val damaged =
            Vault(path) { tmp ->
                val bytes = Files.readAllBytes(tmp)
                bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte() // a bit flips on the way to the disk
                Files.write(tmp, bytes)
            }
        assertFailsWith<IOException> { damaged.write("new-passphrase".toCharArray(), newData) }

        assertEquals(oldData, Vault(path).read("old-passphrase".toCharArray()))
        assertEquals(listOf("vault.enc", "vault.enc.lock"), filesIn(folder))
        folder.toFile().deleteRecursively()
    }

    @Test
    fun `a first write that fails leaves no vault at all, not a torn one`() {
        val folder = vaultFolder()
        val path = folder.resolve("vault.enc")

        assertFailsWith<IOException> { Vault(path) { throw IOException("simulated crash") }.init("new-passphrase".toCharArray()) }

        assertFalse(Vault(path).exists())
        assertEquals(listOf("vault.enc.lock"), filesIn(folder))
        folder.toFile().deleteRecursively()
    }

    @Test
    fun `the temp file is owner-only before the secrets are written, and so is the vault after`() {
        val folder = vaultFolder()
        val path = folder.resolve("vault.enc")
        var tmpProblem: String? = "hook not run"

        Vault(path) { tmp -> tmpProblem = OwnerOnly.grantProblem(tmp) }.write("new-passphrase".toCharArray(), newData)

        assertNull(tmpProblem, "the file the secrets go into is not reachable by anyone else")
        assertNull(OwnerOnly.grantProblem(path), "nor is the vault it became")
        assertEquals(newData, Vault(path).read("new-passphrase".toCharArray()))
        folder.toFile().deleteRecursively()
    }

    @Test
    fun `a successful rewrite under a new passphrase replaces the old vault`() {
        val folder = vaultFolder()
        val path = folder.resolve("vault.enc")
        Vault(path).write("old-passphrase".toCharArray(), oldData)

        Vault(path).write("new-passphrase".toCharArray(), oldData)

        assertEquals(oldData, Vault(path).read("new-passphrase".toCharArray()))
        assertFails { Vault(path).read("old-passphrase".toCharArray()) }
        assertEquals(listOf("vault.enc", "vault.enc.lock"), filesIn(folder))
        folder.toFile().deleteRecursively()
    }
}
