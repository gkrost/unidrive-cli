package org.krost.unidrive.s3

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ProviderException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * archive#1256 A: S3's answer for a missing key is a ProviderException that carries the HTTP status 404 as `httpStatus`
 * (its own field is called `status`, which the engine's reflective probes never read), the signal the engine's one rule
 * (`RemoteErrors.isNotFound`) reads.
 */
class S3NotFoundSignalTest {
    @Test
    fun `a missing key carries the HTTP status 404 the engine reads`() =
        runTest {
            val dir = Files.createTempDirectory("s3-notfound")
            val provider = S3Provider(S3Config("b", "us-east-1", "http://localhost:9000", "AK", "SK", dir), FakeS3().engine)

            val failure = assertFailsWith<ProviderException> { provider.getMetadata("/missing.txt") }

            assertEquals(404, failure.httpStatus, "S3 threw ${failure.javaClass.name}: ${failure.message}")
        }
}
