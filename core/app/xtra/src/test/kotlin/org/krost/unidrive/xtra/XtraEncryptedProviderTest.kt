package org.krost.unidrive.xtra

import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class XtraEncryptedProviderTest {

    private fun keys(dir: Path): Lazy<XtraKeyManager> {
        val km = XtraKeyManager(dir.resolve("xtra.key"))
        km.generate("testpass1234".toCharArray())
        return lazy { km }
    }

    @Test
    fun `upload encrypts and download decrypts round-trip`() {
        val tmpDir = Files.createTempDirectory("xtra-provider-test")
        val storage = mutableMapOf<String, ByteArray>()
        val xtra = XtraEncryptedProvider(InMemoryProvider(storage), keys(tmpDir))

        val localFile = tmpDir.resolve("test.txt")
        Files.writeString(localFile, "Hello, X-tra!")

        kotlinx.coroutines.runBlocking { xtra.upload(localFile, "/test.txt") }

        // The stored bytes carry the XTRA header, not the plaintext.
        val stored = storage["/test.txt"]!!
        assertEquals("XTRA", String(stored.copyOfRange(0, 4)))

        val dest = tmpDir.resolve("downloaded.txt")
        kotlinx.coroutines.runBlocking { xtra.download("/test.txt", dest) }
        assertEquals("Hello, X-tra!", Files.readString(dest))
    }

    @Test
    fun `downloadWithHash answers the plaintext bytes and digest`() {
        val tmpDir = Files.createTempDirectory("xtra-provider-test")
        val storage = mutableMapOf<String, ByteArray>()
        val xtra = XtraEncryptedProvider(InMemoryProvider(storage), keys(tmpDir))

        val localFile = tmpDir.resolve("hashed.txt")
        val original = "hash me".toByteArray()
        Files.write(localFile, original)
        kotlinx.coroutines.runBlocking { xtra.upload(localFile, "/hashed.txt") }

        val dest = tmpDir.resolve("hashed-dl.txt")
        val receipt = kotlinx.coroutines.runBlocking { xtra.downloadWithHash("/hashed.txt", dest) }
        assertEquals(original.size.toLong(), receipt.bytesWritten)
        assertEquals(
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)),
            receipt.plaintextSha256,
        )
        assertContentEquals(original, Files.readAllBytes(dest))
    }

    @Test
    fun `downloadById and downloadByIdWithHash also decrypt`() {
        val tmpDir = Files.createTempDirectory("xtra-provider-test")
        val storage = mutableMapOf<String, ByteArray>()
        val xtra = XtraEncryptedProvider(InMemoryProvider(storage), keys(tmpDir))

        val localFile = tmpDir.resolve("byid.txt")
        Files.writeString(localFile, "ById test")
        kotlinx.coroutines.runBlocking { xtra.upload(localFile, "/byid.txt") }

        val dest = tmpDir.resolve("byid-dl.txt")
        kotlinx.coroutines.runBlocking { xtra.downloadById("id-1", "/byid.txt", dest) }
        assertEquals("ById test", Files.readString(dest))

        val dest2 = tmpDir.resolve("byid-dl2.txt")
        val receipt = kotlinx.coroutines.runBlocking { xtra.downloadByIdWithHash("id-1", "/byid.txt", dest2) }
        assertEquals("ById test", Files.readString(dest2))
        assertEquals(9L, receipt.bytesWritten)
    }

    @Test
    fun `a file without the XTRA header fails its download`() {
        val tmpDir = Files.createTempDirectory("xtra-provider-test")
        val storage = mutableMapOf<String, ByteArray>()
        storage["/plain.txt"] = "not encrypted".toByteArray()
        val xtra = XtraEncryptedProvider(InMemoryProvider(storage), keys(tmpDir))

        val dest = tmpDir.resolve("plain.txt")
        assertFailsWith<ProviderException> {
            kotlinx.coroutines.runBlocking { xtra.download("/plain.txt", dest) }
        }
    }

    @Test
    fun `empty file round-trip`() {
        val tmpDir = Files.createTempDirectory("xtra-provider-test")
        val storage = mutableMapOf<String, ByteArray>()
        val xtra = XtraEncryptedProvider(InMemoryProvider(storage), keys(tmpDir))

        val emptyFile = tmpDir.resolve("empty.dat")
        Files.createFile(emptyFile)

        kotlinx.coroutines.runBlocking { xtra.upload(emptyFile, "/empty.dat") }

        val stored = storage["/empty.dat"]!!
        assertEquals("XTRA", String(stored.copyOfRange(0, 4)))

        val dest = tmpDir.resolve("empty-dl.dat")
        kotlinx.coroutines.runBlocking { xtra.download("/empty.dat", dest) }
        assertEquals(0L, Files.size(dest))
    }

    @Test
    fun `large file round-trip`() {
        val tmpDir = Files.createTempDirectory("xtra-provider-test")
        val storage = mutableMapOf<String, ByteArray>()
        val xtra = XtraEncryptedProvider(InMemoryProvider(storage), keys(tmpDir))

        val localFile = tmpDir.resolve("large.bin")
        val original = ByteArray(500_000).also { java.security.SecureRandom().nextBytes(it) }
        Files.write(localFile, original)

        kotlinx.coroutines.runBlocking { xtra.upload(localFile, "/large.bin") }
        val dest = tmpDir.resolve("large-dl.bin")
        kotlinx.coroutines.runBlocking { xtra.download("/large.bin", dest) }
        assertContentEquals(original, Files.readAllBytes(dest))
    }

    @Test
    fun `the key loads lazily, a listing never touches it`() {
        val tmpDir = Files.createTempDirectory("xtra-provider-test")
        val storage = mutableMapOf<String, ByteArray>()
        var loaded = false
        val keys = lazy { loaded = true; XtraKeyManager(tmpDir.resolve("xtra.key")) }
        val xtra = XtraEncryptedProvider(InMemoryProvider(storage), keys)

        kotlinx.coroutines.runBlocking { xtra.listChildren("/") }
        assertEquals(false, loaded)

        // A download reaches for the key (and then fails the unencrypted bytes the store holds).
        storage["/plain.txt"] = "not encrypted".toByteArray()
        assertFailsWith<ProviderException> {
            kotlinx.coroutines.runBlocking { xtra.download("/plain.txt", tmpDir.resolve("out")) }
        }
        assertEquals(true, loaded)
    }

    @Test
    fun `upload forwards the replace and conditional-write parameters`() {
        val tmpDir = Files.createTempDirectory("xtra-provider-test")
        val inner = InMemoryProvider(mutableMapOf<String, ByteArray>())
        val xtra = XtraEncryptedProvider(inner, keys(tmpDir))

        val localFile = tmpDir.resolve("fwd.txt")
        Files.writeString(localFile, "forwarded")

        val item = kotlinx.coroutines.runBlocking { xtra.upload(localFile, "/fwd.txt", existingRemoteId = "uuid-7", ifMatchETag = "etag-9") }
        assertEquals("uuid-7", item.id)
        assertEquals("etag-9", inner.lastUploadIfMatch)
    }
}

/** The inner provider of the tests: a byte-array store behind the current [CloudProvider] SPI. */
private class InMemoryProvider(private val storage: MutableMap<String, ByteArray>) : CloudProvider {
    override val id = "test"
    override val displayName = "Test"
    override var isAuthenticated = true
    var lastUploadIfMatch: String? = null

    override fun capabilities() = emptySet<Capability>()
    override suspend fun authenticate() {}
    override suspend fun listChildren(path: String) = emptyList<CloudItem>()
    override suspend fun getMetadata(path: String) = item(path, 0)
    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long {
        val data = storage[remotePath] ?: throw ProviderException("Not found: $remotePath")
        Files.write(destination, data)
        return data.size.toLong()
    }

    override suspend fun downloadById(
        remoteId: String,
        remotePath: String,
        destination: Path,
    ): Long = download(remotePath, destination)

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        ifMatchETag: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        lastUploadIfMatch = ifMatchETag
        storage[remotePath] = Files.readAllBytes(localPath)
        return CloudItem(existingRemoteId ?: "new-id", localPath.fileName.toString(), remotePath, Files.size(localPath), false, null, null, null, null)
    }

    override suspend fun delete(remotePath: String, ifMatchETag: String?) {
        storage.remove(remotePath)
    }

    override suspend fun createFolder(path: String) = item(path, 0).let { CloudItem("d-$path", path, path, 0, true, null, null, null, null) }
    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem = item(toPath, 0)

    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((Int) -> Unit)?,
        scanContext: org.krost.unidrive.ScanContext?,
    ): DeltaPage = DeltaPage(emptyList(), "", false)

    override suspend fun quota() = QuotaInfo(0, 0, 0)

    private fun item(
        path: String,
        size: Long,
    ) = CloudItem("id-$path", path, path, size, false, null, null, null, null)
}
