package org.krost.unidrive.s3

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.krost.unidrive.ProviderException
import org.krost.unidrive.testing.TestScratch

/** #748 finding 5: a multipart upload whose completion is refused for good is aborted, not left open and billed. */
class S3MultipartCompleteRefusedTest {
    private val dir = TestScratch.directory("s3-complete-refused")
    private val mib = 1024L * 1024

    private fun provider(fake: FakeS3) =
        S3Provider(S3Config("b", "us-east-1", "http://localhost:9000", "AK", "SK", dir, multipartThreshold = mib, minPartSize = mib), fake.engine)

    private fun file() = Files.createTempFile(dir, "big", ".bin").also { Files.write(it, ByteArray((2 * mib).toInt()) { i -> i.toByte() }) }

    @Test
    fun `a completion the key's change refuses aborts the open multipart upload`() =
        runTest {
            val fake = FakeS3()
            fake.failures += Triple({ r: FakeS3.Request -> r.query.containsKey("uploadId") && !r.query.containsKey("partNumber") && r.method.value == "POST" }, 412, "PreconditionFailed")

            assertFailsWith<ProviderException> { provider(fake).upload(file(), "/big.bin") }

            assertEquals(0, fake.openUploads, "the refused upload is aborted")
        }
}
