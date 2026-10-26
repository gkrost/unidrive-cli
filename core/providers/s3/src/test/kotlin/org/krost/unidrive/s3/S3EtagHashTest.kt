package org.krost.unidrive.s3

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The provider declares `Md5Hex`, so a hash it reports is read as the content's MD5. An ETag is that MD5 only for a
 * plain single-part object: a multipart upload appends `-<parts>`, and SSE-KMS or SSE-C storage (a HEAD says which;
 * a listing does not) replaces it with something else. Reporting such an ETag as a hash made a touched multipart
 * upload run again whole, and made hydration with integrity verification refuse every SSE-KMS file (#82 C).
 */
class S3EtagHashTest {
    private lateinit var dir: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("s3-etag")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun provider(fake: FakeS3) =
        S3Provider(S3Config("b", "us-east-1", "http://localhost:9000", "AK", "SK", dir, multipartThreshold = 0), fake.engine)

    private fun obj(key: String, etag: String, sse: String? = null): FakeS3.Obj {
        val bytes = "$key-bytes".toByteArray()
        return FakeS3.Obj(bytes, bytes.size.toLong(), etag, emptyMap(), Instant.parse("2026-01-01T00:00:00Z"), sse)
    }

    @Test
    fun `a multipart ETag is not reported as a hash`() = runTest {
        val fake = FakeS3()
        fake.objects["mp.txt"] = obj("mp.txt", "d41d8cd98f00b204e9800998ecf8427e-7")
        val provider = provider(fake)

        assertNull(provider.listChildren("/").single().hash, "the listing's multipart ETag is not an MD5")
        assertNull(provider.getMetadata("/mp.txt").hash, "the HEAD's multipart ETag is not an MD5")
        assertNull(provider.delta(null).items.single { it.id == "mp.txt" }.hash)
    }

    @Test
    fun `an upload through multipart reports no hash`() = runTest {
        val fake = FakeS3()
        val provider = provider(fake)
        val local = dir.resolve("local.mp").also { Files.writeString(it, "multipart-content") }

        val item = provider.upload(local, "/mp.txt", existingRemoteId = null, ifMatchETag = null, onProgress = null)

        assertNull(item.hash, "the ETag of a multipart upload carries a part count, not an MD5")
    }

    @Test
    fun `an SSE-KMS or SSE-C object reports no hash where a HEAD can see it`() = runTest {
        val fake = FakeS3()
        fake.objects["kms.txt"] = obj("kms.txt", "opaque-kms-etag", sse = "aws:kms")
        fake.objects["ssec.txt"] = obj("ssec.txt", "opaque-ssec-etag", sse = "aws:kms:dsse")

        val provider = provider(fake)

        assertNull(provider.getMetadata("/kms.txt").hash)
        assertNull(provider.getMetadata("/ssec.txt").hash)
    }

    @Test
    fun `a plain object keeps its MD5`() = runTest {
        val fake = FakeS3()
        fake.put("plain.txt", "PLAIN")
        fake.objects["ssec3.txt"] = obj("ssec3.txt", "anything", sse = "AES256")

        val provider = provider(fake)
        val items = provider.listChildren("/").associateBy { it.id }

        assertEquals(FakeS3.md5Hex("PLAIN".toByteArray()), items.getValue("plain.txt").hash)
        assertEquals("anything", items.getValue("ssec3.txt").hash, "SSE-S3 leaves the ETag the content's MD5")
    }
}
