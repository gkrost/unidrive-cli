package org.krost.unidrive.dropbox

import io.ktor.client.plugins.HttpTimeoutCapability
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.HttpDefaults
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/**
 * The client's flat `HttpDefaults.REQUEST_TIMEOUT_MS` (10 minutes) is counted up to the end of the `execute` block, so
 * it cuts a download that needs longer however steadily the bytes arrive: 5 GiB at 6 MiB/s is 14 minutes, and every
 * retry meets the same cap. The Dropbox download is therefore unbounded at the request level and sizes the cap of its
 * body from the response's own Content-Length (no metadata request first, #841).
 */
class DropboxDownloadTimeoutTest {
    @Test
    fun `a download of a big file is not under the flat 10 minute request cap`() =
        runTest {
            val dir = TestScratch.directory("dbx-dl-timeout")
            val fake = FakeDropbox()
            val fiveGiB = 5L * 1024 * 1024 * 1024
            fake.on("files/download") {
                FakeDropbox.Answer(
                    bytes = ByteArray(4),
                    headers = mapOf(DropboxApi.API_RESULT to FakeDropbox.file("/big.bin", "id:1", size = fiveGiB)),
                )
            }
            val provider = FakeDropbox.provider(fake, dir)

            // The 4-byte answer is shorter than the declared size: the outcome does not matter, the request's timeout does.
            runCatching { provider.download("/big.bin", dir.resolve("big.bin")) }

            val download = fake.engine.requestHistory.last { it.url.encodedPath.endsWith("/files/download") }
            val timeouts =
                assertNotNull(download.getCapabilityOrNull(HttpTimeoutCapability), "the download carries a request timeout")
            assertTrue(
                timeouts.requestTimeoutMillis!! > HttpDefaults.REQUEST_TIMEOUT_MS,
                "a 5 GiB download is not under the flat ${HttpDefaults.REQUEST_TIMEOUT_MS} ms " +
                    "(got ${timeouts.requestTimeoutMillis} ms)",
            )
        }
}
