package org.krost.unidrive.s3

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/** A multipart upload recorded by the old per-file store resumes from the parts S3 holds after the upgrade (#441 item 14). */
class S3LegacyRecordResumeTest {
    private val dir = TestScratch.directory("s3-legacy-resume")
    private val mib = 1024L * 1024

    private fun provider(fake: FakeS3) =
        S3Provider(S3Config("b", "us-east-1", "http://localhost:9000", "AK", "SK", dir, multipartThreshold = mib, minPartSize = mib), fake.engine)

    @Test
    fun old_format_multipart_upload_resumes_from_its_saved_parts_after_the_upgrade() =
        runTest {
            val fake = FakeS3()
            val local = TestScratch.file(dir, "big", ".bin").also { Files.write(it, ByteArray((5 * mib).toInt()) { i -> (i % 249).toByte() }) }
            fake.failures += Triple({ r: FakeS3.Request -> r.query["partNumber"] == "4" }, 400, "RequestTimeout")
            assertFailsWith<S3Exception> { provider(fake).upload(local, "/big.bin") }
            assertEquals(1, fake.openUploads)

            // Turn the record the failed attempt left into the old format, as an upgraded profile has it.
            val uploads = dir.resolve("s3-uploads")
            val record = checkNotNull(MultipartStore(uploads).find("big.bin"))
            Files.list(uploads).use { it.toList() }.forEach(Files::delete)
            writeLegacyRecord(uploads, record)

            val sentBefore = fake.requests.count { it.query.containsKey("partNumber") }
            provider(fake).upload(local, "/big.bin")

            val resent = fake.requests.filter { it.query.containsKey("partNumber") }.drop(sentBefore).map { it.query.getValue("partNumber") }
            assertTrue("4" in resent, "the part that failed is sent again: $resent")
            assertTrue(resent.size < 5, "the parts S3 already held are not sent again: $resent")
            assertEquals(1, fake.count { it.query.containsKey("uploads") }, "one multipart upload, the old one, resumed")
            assertEquals(0, fake.openUploads)
            assertTrue(fake.objects.getValue("big.bin").bytes!!.contentEquals(Files.readAllBytes(local)))
        }
}
