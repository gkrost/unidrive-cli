package org.krost.unidrive.xtra

import org.krost.unidrive.Capability
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.FakeCloudProvider
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

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
    ): CloudProvider = provider(inner, AesGcmStrategy(keys))

    private fun provider(
        inner: CloudProvider,
        strategy: XtraEncryptionStrategy,
    ): CloudProvider = XtraEncryptedProvider(XtraPlaintextListings(inner, strategy), strategy)

    @Test
    fun `upload encrypts and download decrypts round-trip`() {
        val tmpDir = TestScratch.directory("xtra-provider-test")
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
        val tmpDir = TestScratch.directory("xtra-provider-test")
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
        val tmpDir = TestScratch.directory("xtra-provider-test")
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
        val tmpDir = TestScratch.directory("xtra-provider-test")
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
        val tmpDir = TestScratch.directory("xtra-provider-test")
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
        val tmpDir = TestScratch.directory("xtra-provider-test")
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
        val tmpDir = TestScratch.directory("xtra-provider-test")
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
        val tmpDir = TestScratch.directory("xtra-provider-test")
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

    // windows/cli#722 review: the staging file is owner-only from the first byte on, and the replacement takes the
    // permissions of the file it replaces instead of the staging file's — the previous file's modes must survive the
    // download. POSIX modes only; Windows has no mode bits to assert (there the staging file is owner-restricted and
    // the moved file keeps that).
    private fun posix(): Boolean = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    private fun downloadOver(
        dest: Path,
        previousPerms: String?,
    ) {
        val tmpDir = TestScratch.directory("xtra-provider-test")
        val storage = mutableMapOf<String, ByteArray>()
        val xtra = provider(InMemoryProvider(storage), keys(tmpDir))
        val original = ByteArray(50_000) { (it * 3).toByte() }
        val localFile = tmpDir.resolve("src.bin")
        Files.write(localFile, original)
        kotlinx.coroutines.runBlocking { xtra.upload(localFile, "/h.bin") }

        Files.write(dest, "the previous content".toByteArray())
        if (previousPerms != null) {
            Files.setPosixFilePermissions(dest, java.nio.file.attribute.PosixFilePermissions.fromString(previousPerms))
        }
        kotlinx.coroutines.runBlocking { xtra.download("/h.bin", dest) }
        assertContentEquals(original, Files.readAllBytes(dest))
        assertEquals(listOf(dest.fileName.toString()), Files.list(dest.parent).use { l -> l.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `the replacement keeps the previous file's permissions instead of the staging file's`() {
        if (!posix()) return
        val dest = TestScratch.directory("xtra-provider-test").resolve("h.bin")

        downloadOver(dest, "rw-------")

        assertEquals(
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(dest),
            "a download must not widen the file it replaces",
        )
    }

    @Test
    fun `the replacement keeps a wider destination's modes`() {
        if (!posix()) return
        val dest = TestScratch.directory("xtra-provider-test").resolve("h.bin")

        downloadOver(dest, "rw-rw-r--")

        assertEquals(
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-r--"),
            Files.getPosixFilePermissions(dest),
            "the staged file's owner-only modes are not final when the previous file was wider",
        )
    }

    @Test
    fun `a download to a destination that does not exist yet lands owner-only`() {
        if (!posix()) return
        val tmpDir = TestScratch.directory("xtra-provider-test")
        val storage = mutableMapOf<String, ByteArray>()
        val xtra = provider(InMemoryProvider(storage), keys(tmpDir))
        val original = ByteArray(50_000) { (it * 11).toByte() }
        val localFile = tmpDir.resolve("src.bin")
        Files.write(localFile, original)
        kotlinx.coroutines.runBlocking { xtra.upload(localFile, "/i.bin") }

        val dest = tmpDir.resolve("fresh").resolve("i.bin")
        kotlinx.coroutines.runBlocking { xtra.download("/i.bin", dest) }
        assertContentEquals(original, Files.readAllBytes(dest))
        assertEquals(
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(dest),
            "the plaintext of the encrypted layer has no previous file to take its permissions from: owner-only",
        )
    }

    @Test
    fun `the key loads lazily, a listing never touches it`() {
        val tmpDir = TestScratch.directory("xtra-provider-test")
        val storage = mutableMapOf<String, ByteArray>()
        var loaded = false
        val keys = lazy {
            loaded = true
            XtraKeyManager(tmpDir.resolve("xtra.key")).also { it.generate("testpass1234".toCharArray()) }
        }
        val xtra = provider(InMemoryProvider(storage), keys)

        kotlinx.coroutines.runBlocking { xtra.listChildren("/") }
        assertEquals(false, loaded)

        // A download of bytes without the header refuses before any key demand: the failure needs no secret.
        storage["/plain.txt"] = "not encrypted".toByteArray()
        assertFailsWith<ProviderException> {
            kotlinx.coroutines.runBlocking { xtra.download("/plain.txt", tmpDir.resolve("out")) }
        }
        assertEquals(false, loaded)

        // The first content operation that keys the content — the wrap of an upload — demands it.
        val localFile = tmpDir.resolve("keyed.txt")
        Files.writeString(localFile, "demand it")
        kotlinx.coroutines.runBlocking { xtra.upload(localFile, "/keyed.txt") }
        assertEquals(true, loaded)
    }

    @Test
    fun `upload forwards the replace and conditional-write parameters`() {
        val tmpDir = TestScratch.directory("xtra-provider-test")
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
        val tmpDir = TestScratch.directory("xtra-provider-test")
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
        val tmpDir = TestScratch.directory("xtra-provider-test")
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
        val tmpDir = TestScratch.directory("xtra-provider-test")
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
        val tmpDir = TestScratch.directory("xtra-provider-test")
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

    // ── The age-v1 algorithm (xtra_algorithm = "age-v1", AgeV1Strategy) through the same decorator ──

    // An age-v1 profile owns no RSA material at all: no key file is generated here, none may be demanded —
    // the identity store is the only key material the algorithm has.
    private fun ageProvider(
        inner: CloudProvider,
        dir: Path,
    ): CloudProvider {
        val store = XtraAgeKeyStore(dir.resolve("xtra-age.key"))
        store.generate("testpass1234".toCharArray())
        val strategy = AgeV1Strategy(lazy { store }, { "testpass1234".toCharArray() })
        return provider(inner, strategy)
    }

    @Test
    fun `age-v1 upload encrypts and download decrypts round-trip`() {
        val tmpDir = TestScratch.directory("xtra-age-provider-test")
        val storage = mutableMapOf<String, ByteArray>()
        val xtra = ageProvider(InMemoryProvider(storage), tmpDir)

        val localFile = tmpDir.resolve("age.txt")
        Files.writeString(localFile, "Hello from age-v1!")
        kotlinx.coroutines.runBlocking { xtra.upload(localFile, "/age.txt") }

        // The stored bytes carry the XTRA header, and under it a standard age file — not the plaintext.
        val stored = storage["/age.txt"]!!
        assertEquals("XTRA", String(stored.copyOfRange(0, 4)))
        assertTrue(
            String(stored, Charsets.ISO_8859_1).substringAfter("XTRA").contains("age-encryption.org/v1"),
            "the body must be a standard age file",
        )

        val dest = tmpDir.resolve("age-dl.txt")
        kotlinx.coroutines.runBlocking { xtra.download("/age.txt", dest) }
        assertEquals("Hello from age-v1!", Files.readString(dest))
    }

    @Test
    fun `age-v1 tampered ciphertext refuses the download and keeps the previous file`() {
        val tmpDir = TestScratch.directory("xtra-age-provider-test")
        val storage = mutableMapOf<String, ByteArray>()
        val xtra = ageProvider(InMemoryProvider(storage), tmpDir)
        val original = ByteArray(200_000) { (it * 29 + 5).toByte() }
        val localFile = tmpDir.resolve("src.bin")
        Files.write(localFile, original)
        kotlinx.coroutines.runBlocking { xtra.upload(localFile, "/a.bin") }
        val stored = storage.getValue("/a.bin")
        stored[stored.size / 2] = (stored[stored.size / 2].toInt() xor 0x55).toByte()

        val dest = tmpDir.resolve("a.bin")
        Files.write(dest, "the previous content".toByteArray())
        val failure = assertFailsWith<org.krost.unidrive.RemoteContentRefusedDownloadException> {
            kotlinx.coroutines.runBlocking { xtra.download("/a.bin", dest) }
        }
        assertEquals("authentication failed", failure.reason)
        assertContentEquals("the previous content".toByteArray(), Files.readAllBytes(dest))
    }

    @Test
    fun `age-v1 listings answer the plaintext size`() {
        val tmpDir = TestScratch.directory("xtra-age-provider-test")
        val inner = FakeCloudProvider()
        val xtra = ageProvider(inner, tmpDir)

        val localFile = tmpDir.resolve("sized.txt")
        val original = "Hello, aged world!"
        Files.writeString(localFile, original)
        kotlinx.coroutines.runBlocking { xtra.upload(localFile, "/sized.txt") }

        // The stored object's size is the ciphertext's (XTRA header + age file); the listing must answer the
        // plaintext size the engines' size contracts hold downloads against.
        val ciphertextSize = inner.files.getValue("/sized.txt").size.toLong()
        assertTrue(ciphertextSize > original.length, "the test means something only if the wrapper adds bytes: $ciphertextSize")
        inner.deltaItems = listOf(
            org.krost.unidrive.CloudItem("id-sized", "sized.txt", "/sized.txt", ciphertextSize, false, null, null, null, null),
        )
        val listed = kotlinx.coroutines.runBlocking { xtra.getMetadata("/sized.txt") }
        assertEquals(original.length.toLong(), listed.size)
    }

    // ── A file of the other algorithm (#710 review): refused off the header, before any key demand ──

    private fun ageStrategy(dir: Path): AgeV1Strategy {
        val store = XtraAgeKeyStore(dir.resolve("xtra-age.key"))
        store.generate("testpass1234".toCharArray())
        return AgeV1Strategy(lazy { store }, { "testpass1234".toCharArray() })
    }

    // The strategies a profile switched away from its files' algorithm wires: any key demand fails the test.
    private fun aesGcmDemandingNoKey() = AesGcmStrategy(lazy { error("the RSA key manager was demanded for a file of another algorithm") })

    private fun ageV1DemandingNoKey() =
        AgeV1Strategy(lazy { error("the age identity was demanded for a file of another algorithm") }, { error("a passphrase was demanded") })

    private fun seedThrough(
        strategy: XtraEncryptionStrategy,
        storage: MutableMap<String, ByteArray>,
        dir: Path,
        content: String,
    ): ByteArray {
        val localFile = dir.resolve("seed-${strategy.algorithmId}.txt")
        Files.writeString(localFile, content)
        kotlinx.coroutines.runBlocking { provider(InMemoryProvider(storage), strategy).upload(localFile, "/f.txt") }
        return storage.getValue("/f.txt")
    }

    private fun assertRefusedAsOtherAlgorithm(
        xtra: CloudProvider,
        dir: Path,
        stored: Byte,
        configured: Byte,
    ) {
        val destDir = Files.createDirectories(dir.resolve("dest"))
        val dest = destDir.resolve("f.txt")
        Files.writeString(dest, "the previous content")
        val downloads =
            listOf<suspend () -> Unit>(
                { xtra.download("/f.txt", dest) },
                { xtra.downloadById("id", "/f.txt", dest) },
                { xtra.downloadWithHash("/f.txt", dest) },
                { xtra.downloadByIdWithHash("id", "/f.txt", dest) },
            )
        for (download in downloads) {
            val failure = assertFailsWith<XtraAlgorithmMismatchException> { kotlinx.coroutines.runBlocking { download() } }
            assertEquals(stored, failure.storedAlgorithmId)
            assertEquals(configured, failure.configuredAlgorithmId)
            // Not a permanent failure: the mirror quarantines those until the item changes, and this one must come back.
            assertTrue(!org.krost.unidrive.PermanentDownloadFailureException::class.java.isInstance(failure))
            assertEquals("the previous content", Files.readString(dest))
            assertEquals(listOf("f.txt"), Files.list(destDir).use { l -> l.map { it.fileName.toString() }.toList() })
        }
    }

    @Test
    fun `an age-v1 file under aes-gcm is refused and no RSA key or passphrase is demanded`() {
        val tmpDir = TestScratch.directory("xtra-mismatch-test")
        val storage = mutableMapOf<String, ByteArray>()
        seedThrough(ageStrategy(tmpDir), storage, tmpDir, "aged")

        assertRefusedAsOtherAlgorithm(provider(InMemoryProvider(storage), aesGcmDemandingNoKey()), tmpDir, stored = 2, configured = 1)
    }

    @Test
    fun `an aes-gcm file under age-v1 is refused and no age identity is demanded`() {
        val tmpDir = TestScratch.directory("xtra-mismatch-test")
        val storage = mutableMapOf<String, ByteArray>()
        seedThrough(AesGcmStrategy(keys(tmpDir)), storage, tmpDir, "sealed")

        assertRefusedAsOtherAlgorithm(provider(InMemoryProvider(storage), ageV1DemandingNoKey()), tmpDir, stored = 1, configured = 2)
    }

    @Test
    fun `the same stored file downloads again once the profile names its algorithm again`() {
        val tmpDir = TestScratch.directory("xtra-mismatch-test")
        val storage = mutableMapOf<String, ByteArray>()
        val age = ageStrategy(tmpDir)
        val aes = AesGcmStrategy(keys(tmpDir))
        val ageFile = seedThrough(age, storage, tmpDir, "written under age-v1")
        val aesFile = seedThrough(aes, storage, tmpDir, "written under aes-gcm")
        val dest = tmpDir.resolve("dl.txt")

        // Switched to aes-gcm, the age file is refused; switched back, the same bytes download.
        storage["/f.txt"] = ageFile
        assertFailsWith<XtraAlgorithmMismatchException> {
            kotlinx.coroutines.runBlocking { provider(InMemoryProvider(storage), aes).download("/f.txt", dest) }
        }
        kotlinx.coroutines.runBlocking { provider(InMemoryProvider(storage), age).download("/f.txt", dest) }
        assertEquals("written under age-v1", Files.readString(dest))

        // And the other way round.
        storage["/f.txt"] = aesFile
        assertFailsWith<XtraAlgorithmMismatchException> {
            kotlinx.coroutines.runBlocking { provider(InMemoryProvider(storage), age).download("/f.txt", dest) }
        }
        kotlinx.coroutines.runBlocking { provider(InMemoryProvider(storage), aes).download("/f.txt", dest) }
        assertEquals("written under aes-gcm", Files.readString(dest))
    }

    // ── The header bound to the aes-gcm seal (#710 review): a changed header byte never yields plaintext ──

    // The stored aes-gcm layout: magic(4) ‖ version(1) ‖ algorithmId(1) ‖ wrappedKeyLen(2) ‖ wrappedKey(256) ‖
    // ivLen(2) ‖ iv(12) ‖ segments.
    private val ivStart = 4 + 1 + 1 + 2 + 256 + 2
    private val headerEnd = ivStart + 12

    private fun flippedDownload(
        index: Int,
        stored: ByteArray,
        xtra: CloudProvider,
        storage: MutableMap<String, ByteArray>,
        dest: Path,
    ): Throwable {
        storage["/f.txt"] = stored.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
        Files.writeString(dest, "the previous content")
        val failure = runCatching { kotlinx.coroutines.runBlocking { xtra.download("/f.txt", dest) } }.exceptionOrNull()
        assertEquals("the previous content", Files.readString(dest), "header byte $index: no plaintext may land")
        return checkNotNull(failure) { "header byte $index changed, yet the download succeeded" }
    }

    private fun storedAesGcmFile(
        tmpDir: Path,
        storage: MutableMap<String, ByteArray>,
    ): Pair<CloudProvider, ByteArray> {
        val xtra = provider(InMemoryProvider(storage), keys(tmpDir))
        val localFile = tmpDir.resolve("src.txt")
        Files.writeString(localFile, "header-bound content")
        kotlinx.coroutines.runBlocking { xtra.upload(localFile, "/f.txt") }
        return xtra to storage.getValue("/f.txt")
    }

    @Test
    fun `a changed byte anywhere in an aes-gcm header fails the download and keeps the previous file`() {
        val tmpDir = TestScratch.directory("xtra-header-binding-test")
        val storage = mutableMapOf<String, ByteArray>()
        val (xtra, stored) = storedAesGcmFile(tmpDir, storage)

        for (index in 0 until headerEnd) flippedDownload(index, stored, xtra, storage, tmpDir.resolve("dl.txt"))
    }

    @Test
    fun `a changed IV byte of an aes-gcm file is a content refusal, the bytes the nonce overwrites included`() {
        val tmpDir = TestScratch.directory("xtra-header-binding-test")
        val storage = mutableMapOf<String, ByteArray>()
        val (xtra, stored) = storedAesGcmFile(tmpDir, storage)

        // Bytes 4..11 of the IV are replaced by the segment counter and the last-segment flag in every nonce, so
        // only the header binding notices a change there.
        for (index in ivStart until headerEnd) {
            val failure = flippedDownload(index, stored, xtra, storage, tmpDir.resolve("dl.txt"))
            val refused = assertIs<org.krost.unidrive.RemoteContentRefusedDownloadException>(failure, "IV byte ${index - ivStart}")
            assertEquals("authentication failed", refused.reason)
        }
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
