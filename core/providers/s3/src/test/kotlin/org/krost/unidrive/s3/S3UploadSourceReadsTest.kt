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
 * multipart upload keeps its re-hash: its parts are checked against MD5s taken in reads of their own.
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
    fun `a multipart upload keeps the digest pass, one MD5 read per part and the final re-hash`() =
        runTest {
            val fake = FakeS3()
            val reads = mutableListOf<Path>()
            val file = dir.resolve("big.bin").also { Files.write(it, ByteArray((2.5 * mib).toInt()) { b -> (b % 251).toByte() }) }

            provider(fake, reads).upload(file, "/big.bin")

            val parts = 3
            assertEquals(1 + parts + 1, reads.size, "digest, one MD5 per part, final re-hash, got: $reads")
        }
}
