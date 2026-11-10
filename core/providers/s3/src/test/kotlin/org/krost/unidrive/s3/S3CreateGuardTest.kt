package org.krost.unidrive.s3

import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.RemoteConflictException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * gkrost/unidrive-cli#57: a create must not replace an object that appeared after the plan. The PUT (or the multipart complete)
 * carries `If-None-Match: *`, and a taken key is a conflict. A vendor that does not implement the header answers 501, or a 400
 * whose code names the header: the header is then not sent again and the listing that made the engine plan the create backs the
 * write (#573's relaxation). Any other 400 is a real failure, and a vendor that supports conditional writes never loses its
 * guard to one.
 */
class S3CreateGuardTest {
    private val dir = Files.createTempDirectory("s3-create-guard")
    private val mib = 1024L * 1024

    private fun provider(
        fake: FakeS3,
        threshold: Long = S3Config.MULTIPART_THRESHOLD,
        vendor: S3Config.Vendor = S3Config.Vendor.of("http://localhost:9000"),
    ) = S3Provider(
        S3Config("b", "us-east-1", "http://localhost:9000", "AK", "SK", dir, multipartThreshold = threshold, minPartSize = mib, vendor = vendor),
        fake.engine,
    )

    private fun local(
        name: String,
        bytes: ByteArray,
    ) = dir.resolve(name).also { Files.write(it, bytes) }

    private fun puts(fake: FakeS3) = fake.requests.filter { it.method == HttpMethod.Put && it.key == "a.txt" }

    @Test
    fun `a create carries If-None-Match and a taken key is a conflict that leaves the object`() =
        runTest {
            val fake = FakeS3()
            val s3 = provider(fake)

            s3.upload(local("a.txt", "mine".toByteArray()), "/a.txt")
            assertEquals("*", puts(fake).single().headers["if-none-match"])
            assertEquals("mine", fake.text("a.txt"))

            fake.put("a.txt", "theirs")
            assertFailsWith<RemoteConflictException> { s3.upload(local("a.txt", "mine again".toByteArray()), "/a.txt") }
            assertEquals("theirs", fake.text("a.txt"))
        }

    @Test
    fun `a modify is not a create`() =
        runTest {
            val fake = FakeS3()
            fake.put("a.txt", "old")

            provider(fake).upload(local("a.txt", "new".toByteArray()), "/a.txt", existingRemoteId = "a.txt")

            assertNull(puts(fake).single().headers["if-none-match"])
            assertEquals("new", fake.text("a.txt"))
        }

    @Test
    fun `a multipart create carries the guard on its complete`() =
        runTest {
            val fake = FakeS3()
            fake.put("big.bin", "theirs")
            val bytes = ByteArray((2.5 * mib).toInt()) { (it % 251).toByte() }

            assertFailsWith<RemoteConflictException> { provider(fake, threshold = mib).upload(local("big.bin", bytes), "/big.bin") }

            val complete = fake.requests.last { it.method == HttpMethod.Post && "uploadId" in it.query }
            assertEquals("*", complete.headers["if-none-match"])
            assertEquals("theirs", fake.text("big.bin"))
        }

    @Test
    fun `a vendor that answers 501 to the header loses it, and a create is then backed by the listing alone`() =
        runTest {
            val fake = FakeS3()
            fake.failures += Triple({ r: FakeS3.Request -> r.headers.containsKey("if-none-match") }, 501, "NotImplemented")
            val s3 = provider(fake)

            s3.upload(local("a.txt", "mine".toByteArray()), "/a.txt")
            assertEquals("mine", fake.text("a.txt"))

            // gkrost/unidrive-cli#573: an object that appeared after the plan's listing is replaced, not refused — the race
            // a HEAD before the PUT would only narrow, never close.
            s3.upload(local("a.txt", "mine again".toByteArray()), "/a.txt")
            assertEquals("mine again", fake.text("a.txt"))
            assertEquals(listOf("*", null, null), puts(fake).map { it.headers["if-none-match"] }, "one refused attempt with it, none after")
            assertEquals(2, fake.requests.count { it.method == HttpMethod.Head && it.key == "a.txt" }, "only the post-upload HEAD, no guard HEAD")
        }

    @Test
    fun `a 400 whose code names the header falls back like a 501`() =
        runTest {
            val fake = FakeS3()
            fake.failures += Triple({ r: FakeS3.Request -> r.headers.containsKey("if-none-match") }, 400, "InvalidIfNoneMatch")
            val s3 = provider(fake)

            s3.upload(local("a.txt", "mine".toByteArray()), "/a.txt")
            assertEquals("mine", fake.text("a.txt"))

            s3.upload(local("a.txt", "mine again".toByteArray()), "/a.txt")
            assertEquals("mine again", fake.text("a.txt"), "the listing backs the create, the taken key is replaced")
            assertEquals(listOf("*", null, null), puts(fake).map { it.headers["if-none-match"] }, "one refused attempt with it, none after")
        }

    @Test
    fun `a 400 that is a real failure does not retire the guard`() =
        runTest {
            val fake = FakeS3()
            fake.failures += Triple({ r: FakeS3.Request -> r.headers.containsKey("if-none-match") }, 400, "RequestTimeout")

            assertFailsWith<S3Exception> { provider(fake).upload(local("a.txt", "mine".toByteArray()), "/a.txt") }
            assertNull(fake.text("a.txt"), "a real 400 failure writes nothing and keeps the guard")

            provider(fake).upload(local("a.txt", "mine".toByteArray()), "/a.txt")
            assertEquals("*", puts(fake).last().headers["if-none-match"], "the next create still carries the guard")
        }

    @Test
    fun `a vendor with conditional writes never retires the guard on a refused create`() =
        runTest {
            val fake = FakeS3()
            fake.failures += Triple({ r: FakeS3.Request -> r.headers.containsKey("if-none-match") }, 400, "InvalidIfNoneMatch")

            val s3 = provider(fake, vendor = S3Config.Vendor("aws", 10_000, conditionalWrites = true))

            assertFailsWith<S3Exception> { s3.upload(local("a.txt", "mine".toByteArray()), "/a.txt") }
            assertNull(fake.text("a.txt"))
        }
}
