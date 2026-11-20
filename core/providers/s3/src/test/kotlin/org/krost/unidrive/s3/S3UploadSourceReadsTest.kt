package org.krost.unidrive.s3

import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * gkrost/unidrive-cli#748 finding 7: a single-part upload reads the source in one hashing pass (SHA-256 proof plus the
 * Content-MD5) and once more as the PUT body; S3 checks that body against the MD5, so no third pass hashes it again. A
 * multipart upload takes its part MD5s from that same pass (#845), so only the digest pass and the final re-hash read it
 * besides the bodies.
 */
class S3UploadSourceReadsTest {
    private val dir = TestScratch.directory("s3-source-reads")
    private val mib = 1024L * 1024

    private fun provider(
        fake: FakeS3,
        reads: MutableList<Path>,
    ) = S3Provider(
        S3Config("b", "us-east-1", "http://localhost:9000", "AK", "SK", dir, multipartThreshold = mib, minPartSize = mib),
        fake.engine,
    ).also { p -> p.sourceReadObserver = { file -> reads.add(file) } }

    @Test
    fun `a single-part upload reads its source in one hashing pass and sends it once`() =
        runTest {
            val fake = FakeS3()
            val reads = mutableListOf<Path>()
            val file = dir.resolve("small.bin").also { Files.write(it, ByteArray(4096) { b -> b.toByte() }) }

            provider(fake, reads).upload(file, "/small.bin")

            assertEquals(1, reads.size, "one hashing pass over the source, got: $reads")
            assertEquals(1, fake.requests.count { it.method == HttpMethod.Put && it.key == "small.bin" }, "the body goes out once")
        }

    @Test
    fun `a multipart upload hashes the source in the digest pass for its part MD5s too and keeps the final re-hash`() =
        runTest {
            val fake = FakeS3()
            val reads = mutableListOf<Path>()
            val file = dir.resolve("big.bin").also { Files.write(it, ByteArray((2.5 * mib).toInt()) { b -> (b % 251).toByte() }) }

            provider(fake, reads).upload(file, "/big.bin")

            assertEquals(2, reads.size, "digest pass (SHA-256 and part MD5s) and the final re-hash, got: $reads")
        }

    @Test
    fun `the part MD5s taken in the digest pass are the ones S3 verifies each part body against`() =
        runTest {
            val fake = FakeS3()
            val reads = mutableListOf<Path>()
            val bytes = ByteArray((2.5 * mib).toInt()) { b -> (b % 241).toByte() }
            val file = dir.resolve("parts.bin").also { Files.write(it, bytes) }

            provider(fake, reads).upload(file, "/parts.bin")

            // FakeS3 answers 400 BadDigest to a part whose Content-MD5 is not the MD5 of its body, so a part MD5 cut at the wrong
            // offset fails the upload; the stored object must be the file.
            assertEquals(3, fake.requests.count { it.method == HttpMethod.Put && it.query.containsKey("partNumber") })
            assertEquals(bytes.toList(), fake.objects.getValue("parts.bin").bytes!!.toList())
        }
}
