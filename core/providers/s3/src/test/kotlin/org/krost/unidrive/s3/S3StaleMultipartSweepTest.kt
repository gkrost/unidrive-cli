package org.krost.unidrive.s3

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import io.ktor.http.HttpMethod
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/** #748 finding 5: a multipart upload whose record is old is aborted when the next multipart upload opens; a fresh one is not. */
class S3StaleMultipartSweepTest {
    private val dir = TestScratch.directory("s3-stale-sweep")
    private val mib = 1024L * 1024

    private fun provider(fake: FakeS3) =
        S3Provider(S3Config("b", "us-east-1", "http://localhost:9000", "AK", "SK", dir, multipartThreshold = mib, minPartSize = mib), fake.engine)

    private fun file(name: String) = dir.resolve(name).also { Files.write(it, ByteArray((2 * mib).toInt()) { i -> i.toByte() }) }

    private suspend fun leaveOpen(fake: FakeS3, name: String) {
        fake.failures += Triple({ r: FakeS3.Request -> r.query.containsKey("partNumber") }, 403, "AccessDenied")
        assertFailsWith<Exception> { provider(fake).upload(file(name), "/$name") }
    }

    // Each record is a small JSON file; ageing them rewrites each entry's save time.
    private fun ageRecords(days: Long) {
        val old = Instant.now().minus(days, ChronoUnit.DAYS).toEpochMilli()
        Files.list(dir.resolve("s3-uploads")).use { it.toList() }.filter { it.fileName.toString().endsWith(".json") }.forEach { file ->
            Files.writeString(file, Files.readString(file).replace(Regex("\"savedAtMillis\":\\d+"), "\"savedAtMillis\":$old"))
        }
    }

    @Test
    fun `an upload record older than the age limit is aborted when the next multipart upload opens`() =
        runTest {
            val fake = FakeS3()
            leaveOpen(fake, "old.bin")
            assertEquals(1, fake.openUploads, "the failed upload stays open for its resume")
            ageRecords(30)

            provider(fake).upload(file("next.bin"), "/next.bin")

            assertEquals(0, fake.openUploads, "the old upload is aborted and the new one completed")
        }

    @Test
    fun `a recent upload record is left open for its resume`() =
        runTest {
            val fake = FakeS3()
            leaveOpen(fake, "recent.bin")
            ageRecords(2)

            provider(fake).upload(file("next.bin"), "/next.bin")

            assertEquals(1, fake.openUploads, "the recent upload is not touched")
        }

    private fun records() = MultipartStore(dir.resolve("s3-uploads")).all().size

    @Test
    fun `a stale upload whose abort fails keeps its record`() =
        runTest {
            val fake = FakeS3()
            leaveOpen(fake, "old.bin")
            ageRecords(30)
            fake.failures += Triple({ r: FakeS3.Request -> r.method == HttpMethod.Delete && r.key == "old.bin" }, 403, "AccessDenied") // not retried

            provider(fake).upload(file("next.bin"), "/next.bin")

            assertEquals(1, fake.openUploads, "the abort failed, so the old upload is still open")
            assertEquals(1, records(), "its record stays, the one handle a later sweep can abort it with")
        }

    @Test
    fun `an upload that opens during the sweep waits for it`() {
        // A's sweep aborts B's stale upload. B (the same unchanged file, so it would resume) opens while that abort is in flight.
        // Without the wait, B resumes the upload the sweep is aborting and its parts then fail.
        val fake = FakeS3()
        runBlocking { leaveOpen(fake, "old.bin") }
        ageRecords(30)
        val old = dir.resolve("old.bin")
        val abortEntered = CountDownLatch(1)
        val resumedDuringAbort = AtomicBoolean(false)
        val bListedParts = CountDownLatch(1)
        val first = AtomicBoolean(true)
        fake.onRequest = { r ->
            if (r.method == HttpMethod.Get && r.key == "old.bin" && r.query.containsKey("uploadId")) bListedParts.countDown()
            if (r.method == HttpMethod.Delete && r.key == "old.bin" && first.compareAndSet(true, false)) {
                abortEntered.countDown()
                resumedDuringAbort.set(bListedParts.await(2, TimeUnit.SECONDS))
            }
        }
        val p = provider(fake)
        val next = file("next.bin")
        runBlocking(Dispatchers.IO) {
            withTimeout(30_000) {
                val a = async { p.upload(next, "/next.bin") }
                assertTrue(abortEntered.await(10, TimeUnit.SECONDS), "A's sweep reached the abort")
                val b = async { p.upload(old, "/old.bin") }
                listOf(a, b).awaitAll()
            }
        }
        assertEquals(false, resumedDuringAbort.get(), "B did not resume the upload while the sweep aborted it")
        assertEquals(0, fake.openUploads)
    }
}
