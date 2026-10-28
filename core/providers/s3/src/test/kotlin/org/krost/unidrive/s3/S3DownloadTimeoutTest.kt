package org.krost.unidrive.s3

import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.HttpDefaults
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The client's flat `HttpDefaults.REQUEST_TIMEOUT_MS` (10 minutes) is counted up to the end of the `execute` block, so
 * it cuts a download that needs longer however steadily the bytes arrive: 5 GiB at 6 MiB/s is 14 minutes, and every
 * retry meets the same cap. OneDrive sizes the cap of a download from the declared size; the S3 GET does too, from
 * the HEAD it makes first.
 */
class S3DownloadTimeoutTest {
    @Test
    fun `a download of a big object is not under the flat 10 minute request cap`() =
        runTest {
            val dir = Files.createTempDirectory("s3-dl-timeout")
            val fake = FakeS3()
            val fiveGiB = 5L * 1024 * 1024 * 1024
            fake.putSized("big.bin", fiveGiB)
            // The fake holds no bytes for the sized object: the GET is refused (non-retryable, no retry loop)
            // and the outcome does not matter, the GET's timeout does.
            fake.failures += Triple({ r: FakeS3.Request -> r.method == HttpMethod.Get }, 403, "AccessDenied")
            val provider = S3Provider(S3Config("b", "us-east-1", "http://localhost:9000", "AK", "SK", dir), fake.engine)

            runCatching { provider.download("/big.bin", dir.resolve("big.bin")) }

            val gets = fake.engine.requestHistory.filter { it.method == HttpMethod.Get }
            assertEquals(1, gets.size, "one GET for the object")
            val timeouts =
                assertNotNull(gets.single().getCapabilityOrNull(HttpTimeoutCapability), "the object GET carries a request timeout")
            assertTrue(
                timeouts.requestTimeoutMillis!! > HttpDefaults.REQUEST_TIMEOUT_MS,
                "a 5 GiB download is sized from its declared size, not the flat ${HttpDefaults.REQUEST_TIMEOUT_MS} ms " +
                    "(got ${timeouts.requestTimeoutMillis} ms)",
            )
        }
}
