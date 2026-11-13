package org.krost.unidrive.s3

import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The parts of one multipart upload go up several at a time, bounded by the upload concurrency, and complete in order. */
class S3ParallelPartsTest {
    private val dir = Files.createTempDirectory("s3-parallel-parts")
    private val mib = 1024L * 1024

    private fun provider(fake: FakeS3) =
        S3Provider(S3Config("b", "us-east-1", "http://localhost:9000", "AK", "SK", dir, multipartThreshold = mib, minPartSize = mib), fake.engine)

    // Counts the part PUTs inside the fake at once; each is held a moment so the overlap shows.
    private fun FakeS3.recordOverlap(): AtomicInteger {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        beforeBody = { method, _ ->
            if (method == HttpMethod.Put) {
                peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                Thread.sleep(150)
                active.decrementAndGet()
            }
        }
        return peak
    }

    private fun file(parts: Int) =
        Files.createTempFile(dir, "big", ".bin").also { f -> Files.write(f, ByteArray((parts * mib).toInt()) { (it % 249).toByte() }) }

    @Test
    fun `parts overlap up to the default concurrency and the object is whole`() =
        runTest {
            val fake = FakeS3()
            val peak = fake.recordOverlap()
            val local = file(8)

            provider(fake).upload(local, "/big.bin")

            assertTrue(peak.get() in 2..S3Provider.DEFAULT_PART_CONCURRENCY, "parts in flight at once: ${peak.get()}")
            assertTrue(fake.objects.getValue("big.bin").bytes!!.contentEquals(Files.readAllBytes(local)))
            assertEquals(0, fake.openUploads)
        }

    @Test
    fun `the upload concurrency bounds the parts in flight`() =
        runTest {
            val fake = FakeS3()
            val peak = fake.recordOverlap()
            val p = provider(fake)
            p.overrideUploadConcurrency(2)

            p.upload(file(6), "/big.bin")

            assertEquals(2, peak.get())
            assertEquals(2, p.multipartUploadConcurrency())
        }

    @Test
    fun `a concurrency out of range is refused`() {
        val p = provider(FakeS3())
        assertFailsWith<IllegalArgumentException> { p.overrideUploadConcurrency(0) }
        assertFailsWith<IllegalArgumentException> { p.overrideUploadConcurrency(S3Api.MAX_CONCURRENCY + 1) }
    }

    @Test
    fun `a failed part leaves the upload open and the next attempt resumes it`() =
        runTest {
            val fake = FakeS3()
            val local = file(5)
            val p = provider(fake)
            fake.failures += Triple({ r: FakeS3.Request -> r.query["partNumber"] == "4" }, 400, "RequestTimeout")

            assertFailsWith<S3Exception> { p.upload(local, "/big.bin") }
            assertEquals(1, fake.openUploads)
            val before = fake.requests.filter { it.query.containsKey("partNumber") }.map { it.query.getValue("partNumber") }
            p.upload(local, "/big.bin")

            val after = fake.requests.filter { it.query.containsKey("partNumber") }.map { it.query.getValue("partNumber") }.drop(before.size)
            assertTrue("4" in after, "the failed part is sent again: $after")
            assertEquals(1, fake.count { it.query.containsKey("uploads") }, "one upload, resumed")
            assertTrue(fake.objects.getValue("big.bin").bytes!!.contentEquals(Files.readAllBytes(local)))
        }
}
