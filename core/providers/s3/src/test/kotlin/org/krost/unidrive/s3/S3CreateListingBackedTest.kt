package org.krost.unidrive.s3

import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * gkrost/unidrive-cli#573, profile key `s3_create_from_listing = true`: a create on a vendor that refuses `If-None-Match` is
 * backed by the listing that made the engine plan it, not by a HEAD before the write. [S3CreateGuardTest] has the single-part
 * form and the default (the HEAD); this keeps the multipart form, where the guard rode on the CompleteMultipartUpload and its
 * refusal costs a HEAD before the retry unless the profile opted in.
 */
class S3CreateListingBackedTest {
    private val dir = Files.createTempDirectory("s3-create-listing")
    private val mib = 1024L * 1024

    private fun provider(
        fake: FakeS3,
        createFromListing: Boolean = true,
    ) = S3Provider(
        S3Config("b", "us-east-1", "http://localhost:9000", "AK", "SK", dir, multipartThreshold = mib, minPartSize = mib, createFromListing = createFromListing),
        fake.engine,
    )

    private fun local(
        name: String,
        bytes: ByteArray,
    ) = dir.resolve(name).also { Files.write(it, bytes) }

    private fun completes(fake: FakeS3, key: String) =
        fake.requests.filter { it.method == HttpMethod.Post && it.key == key && "uploadId" in it.query }

    private fun refusal(fake: FakeS3) {
        fake.failures += Triple({ r: FakeS3.Request -> r.headers.containsKey("if-none-match") }, 501, "NotImplemented")
    }

    @Test
    fun `a multipart create on a refusing vendor completes once more without the header, and no HEAD guards it`() =
        runTest {
            val fake = FakeS3()
            refusal(fake)
            val bytes = ByteArray((2.5 * mib).toInt()) { (it % 251).toByte() }

            provider(fake).upload(local("big.bin", bytes), "/big.bin")

            assertEquals(listOf("*", null), completes(fake, "big.bin").map { it.headers["if-none-match"] }, "one refused complete, one without the header")
            assertEquals(1, fake.requests.count { it.method == HttpMethod.Head && it.key == "big.bin" }, "only the post-upload HEAD, no guard HEAD")
            assertEquals(bytes.size.toLong(), fake.objects.getValue("big.bin").size)
        }

    @Test
    fun `after the refusal a multipart create carries no header at all`() =
        runTest {
            val fake = FakeS3()
            refusal(fake)
            val s3 = provider(fake)
            val first = ByteArray((2.5 * mib).toInt()) { 1 }
            val second = ByteArray((2.5 * mib).toInt()) { 2 }

            s3.upload(local("one.bin", first), "/one.bin")
            s3.upload(local("two.bin", second), "/two.bin")

            assertEquals(listOf(null), completes(fake, "two.bin").map { it.headers["if-none-match"] }, "the refusal learned on one create covers the next")
            assertEquals(6, fake.requests.count { it.key == "two.bin" }, "create, three parts, complete, the post-upload HEAD")
            assertEquals(second.size.toLong(), fake.objects.getValue("two.bin").size)
        }

    @Test
    fun `a multipart create replaces a taken key under the opt-in, the documented trade-off`() =
        runTest {
            val fake = FakeS3()
            refusal(fake)
            fake.put("big.bin", "theirs")
            val bytes = ByteArray((2.5 * mib).toInt()) { (it % 251).toByte() }

            provider(fake).upload(local("big.bin", bytes), "/big.bin")

            assertEquals(bytes.size.toLong(), fake.objects.getValue("big.bin").size, "no HEAD guard, so the listing's word is taken")
        }

    @Test
    fun `without the opt-in a multipart create on a refusing vendor is guarded by a HEAD before the retried complete`() =
        runTest {
            val fake = FakeS3()
            refusal(fake)
            val bytes = ByteArray((2.5 * mib).toInt()) { (it % 251).toByte() }

            provider(fake, createFromListing = false).upload(local("big.bin", bytes), "/big.bin")

            assertEquals(listOf("*", null), completes(fake, "big.bin").map { it.headers["if-none-match"] })
            assertEquals(2, fake.requests.count { it.method == HttpMethod.Head && it.key == "big.bin" }, "the guard HEAD and the post-upload HEAD")
        }
}
