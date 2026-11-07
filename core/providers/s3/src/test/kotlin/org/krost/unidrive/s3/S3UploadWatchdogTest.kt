package org.krost.unidrive.s3

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.toByteArray
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.krost.unidrive.http.UploadWatchdogException
import org.krost.unidrive.http.UploadWatchdogLimits
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * An S3 PUT or part PUT that the endpoint never answers is cut by the write-progress watchdog after the response wait,
 * not after the whole request budget (tens of minutes at the floor throughput).
 */
class S3UploadWatchdogTest {
    private val dir = Files.createTempDirectory("s3-watchdog")

    private fun api(): S3Api {
        val engine =
            MockEngine { request ->
                request.body.toByteArray()
                awaitCancellation()
            }
        return S3Api(
            S3Config("b", "us-east-1", "http://localhost:9000", "AK", "SK", dir),
            engine,
            uploadWatchdog = UploadWatchdogLimits(idleWindowMs = 2_000, responseWaitFloorMs = 300, tickMs = 25),
        )
    }

    private fun cutBy(block: suspend (S3Api) -> Unit): UploadWatchdogException {
        val api = api()
        try {
            return runBlocking { withTimeout(30_000) { assertFailsWith<UploadWatchdogException> { block(api) } } }
        } finally {
            api.close()
        }
    }

    @Test
    fun `a single PUT whose answer never comes is cut after the response wait`() {
        val file = dir.resolve("a.bin").also { Files.write(it, ByteArray(1024)) }

        val cut = cutBy { it.put("a.bin", file) }

        assertEquals(UploadWatchdogException.Reason.NO_RESPONSE, cut.reason)
    }

    @Test
    fun `a part PUT whose answer never comes is cut after the response wait`() {
        val file = dir.resolve("big.bin").also { Files.write(it, ByteArray(4096)) }

        val cut = cutBy { it.uploadPart("big.bin", "up-1", 2, file, 1024, 2048, ByteArray(16)) }

        assertEquals(UploadWatchdogException.Reason.NO_RESPONSE, cut.reason)
    }
}
