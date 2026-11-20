package org.krost.unidrive.s3

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    private fun ageRecords(days: Long) {
        val old = FileTime.from(Instant.now().minus(days, ChronoUnit.DAYS))
        Files.list(dir.resolve("s3-uploads")).use { it.toList() }.filter { it.fileName.toString().endsWith(".upload") }
            .forEach { Files.setLastModifiedTime(it, old) }
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
}
