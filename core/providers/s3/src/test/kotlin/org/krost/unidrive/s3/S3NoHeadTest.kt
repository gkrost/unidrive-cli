package org.krost.unidrive.s3

import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * gkrost/unidrive-cli#574, profile key `s3_no_head`: a single-part upload skips the HEAD. The item's `modified` is then the PUT
 * answer's `Date` in whole seconds (null when the answer has none), where the default takes `Last-Modified` from the HEAD.
 */
class S3NoHeadTest {
    private val dir = TestScratch.directory("s3-no-head")
    private val mib = 1024L * 1024

    private fun provider(
        fake: FakeS3,
        noHead: Boolean,
    ) = S3Provider(
        S3Config("b", "us-east-1", "http://localhost:9000", "AK", "SK", dir, multipartThreshold = mib, minPartSize = mib, noHead = noHead),
        fake.engine,
    )

    private fun local(
        name: String,
        bytes: ByteArray,
    ) = dir.resolve(name).also { Files.write(it, bytes) }

    private fun requestsFor(
        fake: FakeS3,
        key: String,
    ) = fake.requests.filter { it.key == key }.map { it.method }

    @Test
    fun `by default a single-part upload is a PUT and a HEAD`() =
        runTest {
            val fake = FakeS3()
            provider(fake, noHead = false).upload(local("a.txt", "hello".toByteArray()), "/a.txt")
            assertEquals(listOf(HttpMethod.Put, HttpMethod.Head), requestsFor(fake, "a.txt"))
        }

    @Test
    fun `with no_head a single-part upload is one request`() =
        runTest {
            val fake = FakeS3()
            provider(fake, noHead = true).upload(local("a.txt", "hello".toByteArray()), "/a.txt")
            assertEquals(listOf(HttpMethod.Put), requestsFor(fake, "a.txt"))
        }

    @Test
    fun `with no_head the recorded modified is the answer's Date, equal to what the next listing reports`() =
        runTest {
            val fake = FakeS3()
            val s3 = provider(fake, noHead = true)
            val item = s3.upload(local("a.txt", "hello".toByteArray()), "/a.txt")

            val listed = s3.delta(null).items.single { it.path == "/a.txt" }
            assertNotNull(item.modified)
            assertEquals(listed.modified, item.modified, "no spurious remote change after the upload (#82 D)")
            assertEquals(5, item.size)
            assertEquals(listed.version, item.version)
        }

    @Test
    fun `with no_head an answer without a Date leaves modified unknown, not invented`() =
        runTest {
            val fake = FakeS3().also { it.putAnswersDate = false }
            val item = provider(fake, noHead = true).upload(local("a.txt", "hello".toByteArray()), "/a.txt")
            assertNull(item.modified)
        }

    @Test
    fun `with no_head the content hash is set only when the answer names a plain encryption`() =
        runTest {
            val unnamed = FakeS3()
            assertNull(provider(unnamed, noHead = true).upload(local("a.txt", "hello".toByteArray()), "/a.txt").hash, "unknown encryption: no hash claim")

            val plain = FakeS3().also { it.putAnswersSse = "AES256" }
            assertNotNull(provider(plain, noHead = true).upload(local("b.txt", "hello".toByteArray()), "/b.txt").hash)

            val kms = FakeS3().also { it.putAnswersSse = "aws:kms" }
            assertNull(provider(kms, noHead = true).upload(local("c.txt", "hello".toByteArray()), "/c.txt").hash, "KMS ETag is not an MD5")
        }

    @Test
    fun `a multipart upload keeps its HEAD under no_head`() =
        runTest {
            val fake = FakeS3()
            val bytes = ByteArray((2.5 * mib).toInt()) { 1 }
            provider(fake, noHead = true).upload(local("big.bin", bytes), "/big.bin")
            assertEquals(1, fake.requests.count { it.method == HttpMethod.Head && it.key == "big.bin" })
        }
}
