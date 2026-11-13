package org.krost.unidrive.xtra

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XtraKeyManagerTest {

    @Test
    fun `generate then load round-trips a wrap`() {
        val dir = Files.createTempDirectory("xtra-key-test")
        val km = XtraKeyManager(dir.resolve("xtra.key"))
        assertFalse(km.exists())
        km.generate("testpass1234".toCharArray())
        assertTrue(km.exists())
        assertTrue(km.isLoaded)

        val fileKey = ByteArray(32) { 7 }
        val wrapped = km.wrapKey(fileKey)
        assertEquals(256, wrapped.size)

        val reloaded = XtraKeyManager(dir.resolve("xtra.key"))
        assertFalse(reloaded.isLoaded)
        reloaded.load("testpass1234".toCharArray())
        assertContentEquals(fileKey, reloaded.unwrapKey(wrapped))
    }

    @Test
    fun `a wrong passphrase fails the load`() {
        val dir = Files.createTempDirectory("xtra-key-test")
        val km = XtraKeyManager(dir.resolve("xtra.key"))
        km.generate("testpass1234".toCharArray())

        val reloaded = XtraKeyManager(dir.resolve("xtra.key"))
        assertFailsWith<Exception> { reloaded.load("wrongpass9999".toCharArray()) }
        assertFalse(reloaded.isLoaded)
    }

    @Test
    fun `a short passphrase is refused`() {
        val dir = Files.createTempDirectory("xtra-key-test")
        val km = XtraKeyManager(dir.resolve("xtra.key"))
        assertFailsWith<IllegalArgumentException> { km.generate("short".toCharArray()) }
    }

    @Test
    fun `changePassphrase keeps the key pair`() {
        val dir = Files.createTempDirectory("xtra-key-test")
        val km = XtraKeyManager(dir.resolve("xtra.key"))
        km.generate("testpass1234".toCharArray())
        val fileKey = ByteArray(32) { 9 }
        val wrapped = km.wrapKey(fileKey)

        km.changePassphrase("newpass12345".toCharArray())

        val reloaded = XtraKeyManager(dir.resolve("xtra.key"))
        reloaded.load("newpass12345".toCharArray())
        assertContentEquals(fileKey, reloaded.unwrapKey(wrapped))
    }

    @Test
    fun `export and import carry the key to a second machine`() {
        val dir = Files.createTempDirectory("xtra-key-test")
        val km = XtraKeyManager(dir.resolve("xtra.key"))
        km.generate("testpass1234".toCharArray())
        val fileKey = ByteArray(32) { 3 }
        val wrapped = km.wrapKey(fileKey)

        val second = XtraKeyManager(dir.resolve("imported.key"))
        second.importKey(km.exportKey())
        second.load("testpass1234".toCharArray())
        assertContentEquals(fileKey, second.unwrapKey(wrapped))
    }

    @Test
    fun `import refuses bytes that cannot be a key`() {
        val dir = Files.createTempDirectory("xtra-key-test")
        val km = XtraKeyManager(dir.resolve("xtra.key"))
        assertFailsWith<IllegalArgumentException> { km.importKey(ByteArray(10)) }
        assertFalse(km.exists())
    }

    // The replaced key decrypts files already in the account; an import (a mistyped --input, the other machine's
    // older key) never silently retires it.
    @Test
    fun `import refuses to replace an existing key without force and keeps no backup then`() {
        val dir = Files.createTempDirectory("xtra-key-test")
        val km = XtraKeyManager(dir.resolve("xtra.key"))
        km.generate("testpass1234".toCharArray())
        val fileKey = ByteArray(32) { 5 }
        val wrapped = km.wrapKey(fileKey)

        val other = XtraKeyManager(dir.resolve("other.key"))
        other.generate("otherpass1234".toCharArray())
        assertFailsWith<IllegalStateException> { other.importKey(km.exportKey()) }

        // The local key is untouched, and no backup of it was moved aside.
        val reloaded = XtraKeyManager(dir.resolve("xtra.key"))
        reloaded.load("testpass1234".toCharArray())
        assertContentEquals(fileKey, reloaded.unwrapKey(wrapped))
        assertTrue(backupNames(dir).isEmpty())    }

    @Test
    fun `a forced import replaces the key and keeps the retired one as a backup`() {
        val dir = Files.createTempDirectory("xtra-key-test")
        val km = XtraKeyManager(dir.resolve("xtra.key"))
        km.generate("testpass1234".toCharArray())
        val fileKey = ByteArray(32) { 11 }
        val wrapped = km.wrapKey(fileKey)

        val exported = km.exportKey()
        km.generate("retiredpass1234".toCharArray())

        val importer = XtraKeyManager(dir.resolve("xtra.key"))
        importer.importKey(exported, force = true)

        // The retired key survives as a .bak beside the new one, still loadable under its passphrase.
        val backup = backupNames(dir).single()
        val retired = XtraKeyManager(dir.resolve(backup))
        retired.load("retiredpass1234".toCharArray())
        assertTrue(retired.isLoaded)

        // The installation's key is the imported one again.
        val reloaded = XtraKeyManager(dir.resolve("xtra.key"))
        reloaded.load("testpass1234".toCharArray())
        assertContentEquals(fileKey, reloaded.unwrapKey(wrapped))
    }

    private fun backupNames(dir: java.nio.file.Path): List<String> =
        Files.newDirectoryStream(dir).use { stream ->
            stream.asIterable().map { it.fileName.toString() }.filter { it.startsWith("xtra.key.bak-") }
        }
}
