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
 * whose code names the header: the provider then checks with a HEAD first and stops sending it, whether or not an earlier create
 * went through (the caller offers no proof of absence). Only the profile key `s3_create_from_listing` (#573) drops that HEAD. Any
 * other 400 is a real failure, and a vendor that supports conditional writes never loses its guard to one.
 */
class S3CreateGuardTest {
    private val dir = Files.createTempDirectory("s3-create-guard")
    private val mib = 1024L * 1024

    private fun provider(
        fake: FakeS3,
        threshold: Long = S3Config.MULTIPART_THRESHOLD,
        vendor: S3Config.Vendor = S3Config.Vendor.of("http://localhost:9000"),
        createFromListing: Boolean = false,
    ) = S3Provider(
        S3Config(
            "b", "us-east-1", "http://localhost:9000", "AK", "SK", dir,
            multipartThreshold = threshold, minPartSize = mib, vendor = vendor, createFromListing = createFromListing,
        ),
        fake.engine,
    )

    private fun refuseHeader(fake: FakeS3, status: Int = 501, code: String = "NotImplemented") {
        fake.failures += Triple({ r: FakeS3.Request -> r.headers.containsKey("if-none-match") }, status, code)
    }

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
    fun `a vendor that answers 501 to the header gets a HEAD check instead, and the header is not sent again`() =
        runTest {
            val fake = FakeS3()
            fake.failures += Triple({ r: FakeS3.Request -> r.headers.containsKey("if-none-match") }, 501, "NotImplemented")
            val s3 = provider(fake)

            s3.upload(local("a.txt", "mine".toByteArray()), "/a.txt")
            assertEquals("mine", fake.text("a.txt"))

            fake.put("a.txt", "theirs")
            assertFailsWith<RemoteConflictException> { s3.upload(local("a.txt", "mine again".toByteArray()), "/a.txt") }
            assertEquals("theirs", fake.text("a.txt"))
            assertEquals(listOf("*", null), puts(fake).map { it.headers["if-none-match"] }, "one refused attempt with it, one without")
        }

    // The reviewer's repro on #613: the key is taken before the very first create, the vendor refuses the header, and the caller
    // (a mount's direct upload, or a sync whose listing is stale) offers no proof of absence. Nothing may be replaced.
    @Test
    fun `a taken key is a conflict on the very first create too, where the vendor refuses the header`() =
        runTest {
            val fake = FakeS3()
            refuseHeader(fake)
            fake.put("a.txt", "theirs")

            assertFailsWith<RemoteConflictException> { provider(fake).upload(local("a.txt", "mine".toByteArray()), "/a.txt") }

            assertEquals("theirs", fake.text("a.txt"), "the other device's object is untouched")
            assertEquals(listOf("*"), puts(fake).map { it.headers["if-none-match"] }, "only the refused attempt wrote anything: no unconditional PUT")
        }

    @Test
    fun `a multipart create on a vendor that refuses the header does not replace a taken key either`() =
        runTest {
            val fake = FakeS3()
            refuseHeader(fake)
            fake.put("big.bin", "theirs")
            val bytes = ByteArray((2.5 * mib).toInt()) { (it % 251).toByte() }

            assertFailsWith<RemoteConflictException> { provider(fake, threshold = mib).upload(local("big.bin", bytes), "/big.bin") }

            assertEquals("theirs", fake.text("big.bin"), "the other device's object is untouched")
            val completes = fake.requests.filter { it.method == HttpMethod.Post && it.key == "big.bin" && "uploadId" in it.query }
            assertEquals(listOf("*"), completes.map { it.headers["if-none-match"] }, "the refused complete is the only one: the HEAD stopped the retry")
        }

    // gkrost/unidrive-cli#573: the opt-in. The profile key s3_create_from_listing drops the HEAD guard for a vendor that refuses the header.
    @Test
    fun `with s3_create_from_listing a vendor that refuses the header is written to without a HEAD guard, a taken key included`() =
        runTest {
            val fake = FakeS3()
            refuseHeader(fake)
            fake.put("a.txt", "theirs")
            val s3 = provider(fake, createFromListing = true)

            s3.upload(local("a.txt", "mine".toByteArray()), "/a.txt")
            assertEquals("mine", fake.text("a.txt"), "the documented trade-off: the listing is trusted, the taken key is replaced")
            s3.upload(local("a.txt", "mine again".toByteArray()), "/a.txt")
            assertEquals("mine again", fake.text("a.txt"))
            assertEquals(listOf("*", null, null), puts(fake).map { it.headers["if-none-match"] }, "one refused attempt with it, none after")
            assertEquals(2, fake.requests.count { it.method == HttpMethod.Head && it.key == "a.txt" }, "only the two post-upload HEADs, no guard HEAD")
        }

    @Test
    fun `without the opt-in a refusing vendor costs a guard HEAD before each create`() =
        runTest {
            val fake = FakeS3()
            refuseHeader(fake)
            val s3 = provider(fake)

            s3.upload(local("a.txt", "mine".toByteArray()), "/a.txt")
            s3.upload(local("b.txt", "mine".toByteArray()), "/b.txt")

            assertEquals(listOf("PUT", "HEAD", "PUT", "HEAD"), fake.requests.filter { it.key == "a.txt" }.map { it.method.value }, "refused PUT, guard HEAD, PUT, post-upload HEAD")
            assertEquals(listOf("HEAD", "PUT", "HEAD"), fake.requests.filter { it.key == "b.txt" }.map { it.method.value }, "guard HEAD, PUT, post-upload HEAD")
        }

    @Test
    fun `the opt-in does not weaken a vendor that honours the header`() =
        runTest {
            val fake = FakeS3()
            fake.put("a.txt", "theirs")
            val s3 = provider(fake, vendor = S3Config.Vendor("aws", 10_000, conditionalWrites = true), createFromListing = true)

            assertFailsWith<RemoteConflictException> { s3.upload(local("a.txt", "mine".toByteArray()), "/a.txt") }
            assertEquals("theirs", fake.text("a.txt"))
        }

    @Test
    fun `a 400 whose code names the header falls back like a 501`() =
        runTest {
            val fake = FakeS3()
            fake.failures += Triple({ r: FakeS3.Request -> r.headers.containsKey("if-none-match") }, 400, "InvalidIfNoneMatch")
            val s3 = provider(fake)

            s3.upload(local("a.txt", "mine".toByteArray()), "/a.txt")
            assertEquals("mine", fake.text("a.txt"))

            fake.put("a.txt", "theirs")
            assertFailsWith<RemoteConflictException> { s3.upload(local("a.txt", "mine again".toByteArray()), "/a.txt") }
            assertEquals(listOf("*", null), puts(fake).map { it.headers["if-none-match"] }, "one refused attempt with it, one without")
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
