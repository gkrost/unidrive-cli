package org.krost.unidrive.xtra

import org.krost.unidrive.Capability
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.FakeCloudProvider
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class XtraEncryptedProviderTest {

    private fun keys(dir: Path): Lazy<XtraKeyManager> {
        val km = XtraKeyManager(dir.resolve("xtra.key"))
        km.generate("testpass1234".toCharArray())
        return lazy { km }
    }

    // The chain the CLI wires: the listings decorator (plaintext sizes) below the cipher.
    private fun provider(
        inner: CloudProvider,
        keys: Lazy<XtraKeyManager>,
    ): CloudProvider {
        val strategy = AesGcmStrategy()
        return XtraEncryptedProvider(XtraPlaintextListings(inner, strategy), keys, strategy)
    }

    @Test
    fun `upload encrypts and download decrypts round-trip`() {
        val tmpDir = Files.createTempDirectory("xtra-provider-test")
        val storage = mutableMapOf<String, ByteArray>()
        val xtra = provider(InMemoryProvider(storage), keys(tmpDir))

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
        val xtra = provider(InMemoryProvider(storage), keys(tmpDir))

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
        val xtra = provider(InMemoryProvider(storage), keys(tmpDir))

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
        val xtra = provider(InMemoryProvider(storage), keys(tmpDir))

        val dest = tmpDir.resolve("plain.txt")
        assertFailsWith<ProviderException> {
            kotlinx.coroutines.runBlocking { xtra.download("/plain.txt", dest) }
        }
    }

    @Test
    fun `empty file round-trip`() {
        val tmpDir = Files.createTempDirectory("xtra-provider-test")
        val storage = mutableMapOf<String, ByteArray>()
        val xtra = provider(InMemoryProvider(storage), keys(tmpDir))

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
        val xtra = provider(InMemoryProvider(storage), keys(tmpDir))

        val localFile = tmpDir.resolve("large.bin")
        val original = ByteArray(500_000).also { java.security.SecureRandom().nextBytes(it) }
        Files.write(localFile, original)

        kotlinx.coroutines.runBlocking { xtra.upload(localFile, "/large.bin") }
        val dest = tmpDir.resolve("large-dl.bin")
        kotlinx.coroutines.runBlocking { xtra.download("/large.bin", dest) }
        assertContentEquals(original, Files.readAllBytes(dest))
    }

    // windows/cli#722: the decryption used to open the real destination, so a ciphertext that failed its tag in a later
    // segment left the earlier segments' plaintext behind, in place of the file the user had.
    private fun stageAndDecryptFails(
        corrupt: (ByteArray) -> ByteArray,
    ) {
        val tmpDir = Files.createTempDirectory("xtra-provider-test")
        val storage = mutableMapOf<String, ByteArray>()
        val xtra = provider(InMemoryProvider(storage), keys(tmpDir))
        val original = ByteArray(200_000) { (it * 31 + 7).toByte() }
        val localFile = tmpDir.resolve("src.bin")
        Files.write(localFile, original)
        kotlinx.coroutines.runBlocking { xtra.upload(localFile, "/f.bin") }
        storage["/f.bin"] = corrupt(storage.getValue("/f.bin"))

        val destDir = Files.createDirectory(tmpDir.resolve("dest"))
        val dest = destDir.resolve("f.bin")
        val previous = "the previous content".toByteArray()
        Files.write(dest, previous)

        val failure = assertFailsWith<org.krost.unidrive.RemoteContentRefusedDownloadException> {
            kotlinx.coroutines.runBlocking { xtra.download("/f.bin", dest) }
        }
        assertEquals("authentication failed", failure.reason)
        assertContentEquals(previous, Files.readAllBytes(dest), "the failed download must leave the previous file")
        assertEquals(listOf("f.bin"), Files.list(destDir).use { l -> l.map { it.fileName.toString() }.toList() }, "no staging file is left")

        // The WithHash variants stage the same way.
        assertFailsWith<org.krost.unidrive.RemoteContentRefusedDownloadException> {
            kotlinx.coroutines.runBlocking { xtra.downloadWithHash("/f.bin", dest) }
        }
        assertContentEquals(previous, Files.readAllBytes(dest))
        assertEquals(listOf("f.bin"), Files.list(destDir).use { l -> l.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `a truncated ciphertext leaves the previous destination untouched and no staging file`() =
        stageAndDecryptFails { it.copyOf(it.size - 20_000) }

    @Test
    fun `a tag failure in the first segment leaves the previous destination untouched`() =
        stageAndDecryptFails { stored -> stored.copyOf().also { it[300] = (it[300].toInt() xor 0x55).toByte() } }

    @Test
    fun `a good download replaces the destination and leaves no staging file`() {
        val tmpDir = Files.createTempDirectory("xtra-provider-test")
        val storage = mutableMapOf<String, ByteArray>()
        val xtra = provider(InMemoryProvider(storage), keys(tmpDir))
        val original = ByteArray(200_000) { (it * 17).toByte() }
        val localFile = tmpDir.resolve("src.bin")
        Files.write(localFile, original)
        kotlinx.coroutines.runBlocking { xtra.upload(localFile, "/g.bin") }

        val destDir = Files.createDirectory(tmpDir.resolve("dest"))
        val dest = destDir.resolve("g.bin")
        Files.write(dest, "old".toByteArray())
        assertEquals(200_000L, kotlinx.coroutines.runBlocking { xtra.download("/g.bin", dest) })
        assertContentEquals(original, Files.readAllBytes(dest))
        val receipt = kotlinx.coroutines.runBlocking { xtra.downloadWithHash("/g.bin", dest, 1_700_000_000_000L) }
        assertEquals(200_000L, receipt.bytesWritten)
        assertEquals(1_700_000_000_000L, Files.getLastModifiedTime(dest).toMillis())
        assertEquals(listOf("g.bin"), Files.list(destDir).use { l -> l.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `the key loads lazily, a listing never touches it`() {
        val tmpDir = Files.createTempDirectory("xtra-provider-test")
        val storage = mutableMapOf<String, ByteArray>()
        var loaded = false
        val keys = lazy { loaded = true; XtraKeyManager(tmpDir.resolve("xtra.key")) }
        val xtra = provider(InMemoryProvider(storage), keys)

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
        val xtra = provider(inner, keys(tmpDir))

        val localFile = tmpDir.resolve("fwd.txt")
        Files.writeString(localFile, "forwarded")

        val item = kotlinx.coroutines.runBlocking { xtra.upload(localFile, "/fwd.txt", existingRemoteId = "uuid-7", ifMatchETag = "etag-9") }
        assertEquals("uuid-7", item.id)
        assertEquals("etag-9", inner.lastUploadIfMatch)
    }

    // The engines' size contracts hold what a listing said against the bytes a download writes (#80, the mirror's
    // completeness check, the warm-cache size match): every item crossing the wrapper up answers the plaintext size.
    @Test
    fun `listings and metadata answer the plaintext size`() {
        val tmpDir = Files.createTempDirectory("xtra-provider-test")
        val inner = FakeCloudProvider()
        val xtra = provider(inner, keys(tmpDir))

        val localFile = tmpDir.resolve("sized.txt")
        val original = "Hello, sized world!"
        Files.writeString(localFile, original)

        // The upload's answer is already plaintext-sized; what the cloud lists for the stored object is not:
        // its own size is the ciphertext's, and its hash names those bytes.
        val uploaded = kotlinx.coroutines.runBlocking { xtra.upload(localFile, "/sized.txt") }
        val listed = uploaded.copy(size = inner.files.getValue("/sized.txt").size.toLong())
        assertTrue(listed.size > original.length, "the test means something only if the wrapper adds bytes: ${listed.size}")
        inner.deltaItems = listOf(listed)
        inner.childrenByParent["/"] = listOf(listed)

        kotlinx.coroutines.runBlocking {
            assertEquals(original.length.toLong(), xtra.getMetadata("/sized.txt").size)
            assertEquals(original.length.toLong(), xtra.delta(null).items.single().size)
            assertEquals(original.length.toLong(), xtra.listChildren("/").single().size)
            assertEquals(original.length.toLong(), xtra.upload(localFile, "/sized.txt").size)
        }
    }

    // The mirror restores the mtime itself only when the receipt says the provider did not apply it; the
    // decryption lands the plaintext in a fresh file, so the wrapper applies the target and says so.
    @Test
    fun `the hash variants apply the target mtime and answer the decrypted stats`() {
        val tmpDir = Files.createTempDirectory("xtra-provider-test")
        val inner = FakeCloudProvider()
        val xtra = provider(inner, keys(tmpDir))

        val localFile = tmpDir.resolve("mtime.txt")
        val original = "timed content"
        Files.write(localFile, original.toByteArray())
        kotlinx.coroutines.runBlocking { xtra.upload(localFile, "/mtime.txt") }

        val target = 1_700_000_000_000L
        val dest = tmpDir.resolve("mtime-dl.txt")
        val receipt = kotlinx.coroutines.runBlocking { xtra.downloadWithHash("/mtime.txt", dest, target) }

        assertEquals(target, Files.getLastModifiedTime(dest).toMillis())
        assertEquals(original.length.toLong(), receipt.bytesWritten)
        assertEquals(receipt.bytesWritten, receipt.stagedSize)
        assertEquals(target, receipt.stagedMtimeMillis)
        assertEquals(true, receipt.mtimeAppliedBeforePlacement)
        assertContentEquals(original.toByteArray(), Files.readAllBytes(dest))
    }

    // The provider's hash answers name the ciphertext, while the bytes the engines see are the plaintext: the
    // wrapper answers "cannot tell" for all of them (the seal's AEAD tag is the integrity check; a tampered
    // stream fails its decrypt, and a content id would name bytes the client does not hold).
    @Test
    fun `the hash answers cross the boundary as cannot tell`() {
        val tmpDir = Files.createTempDirectory("xtra-provider-test")
        val inner = FakeCloudProvider()
        inner.hashAlgorithmOverride = org.krost.unidrive.HashAlgorithm.Sha256Hex
        val xtra = provider(inner, keys(tmpDir))

        assertEquals(null, xtra.hashAlgorithm())
        assertEquals(false, xtra.reportsContentIds)
        kotlinx.coroutines.runBlocking {
            assertEquals(null, xtra.contentMatchesLocal("id-1", "/a.txt", tmpDir.resolve("a.txt")))
        }
    }

    // A share link would serve the stored object, which is the ciphertext — unreadable to whoever follows it.
    // The layer refuses instead of creating one; links that already exist stay listable and revocable.
    @Test
    fun `share refuses instead of handing out a link to ciphertext`() {
        val tmpDir = Files.createTempDirectory("xtra-provider-test")
        val supporting = object : CloudProvider by FakeCloudProvider() {
            override suspend fun share(
                path: String,
                expiryHours: Int,
                password: String?,
            ): CapabilityResult<String> = CapabilityResult.Success("https://share")
        }
        val xtra = provider(supporting, keys(tmpDir))

        val answer = kotlinx.coroutines.runBlocking { xtra.share("/a.txt", 24) }
        assertTrue(answer is CapabilityResult.Unsupported, "$answer")
        assertTrue(answer.reason.contains("X-tra"), answer.reason)
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
