package org.krost.unidrive.dropbox

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.toByteArray
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.krost.unidrive.http.UploadWatchdogException
import org.krost.unidrive.http.UploadWatchdogLimits
import org.krost.unidrive.http.progressTrackingBody
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A content-host upload that Dropbox never answers is cut by the write-progress watchdog after the response wait, not
 * after the whole request budget (tens of minutes at the floor throughput).
 */
class DropboxUploadWatchdogTest {
    @Test
    fun `an upload whose answer never comes is cut after the response wait`() {
        val engine =
            MockEngine { request ->
                request.body.toByteArray()
                awaitCancellation()
            }
        val api =
            DropboxApi(
                DropboxConfig(appKey = "key", tokenPath = Files.createTempDirectory("dbx-watchdog")),
                { _, _ -> "tok" },
                engine = engine,
                uploadWatchdog = UploadWatchdogLimits(idleWindowMs = 2_000, responseWaitFloorMs = 300, tickMs = 25),
            )

        val cut =
            runBlocking {
                withTimeout(30_000) {
                    assertFailsWith<UploadWatchdogException> {
                        api.contentWatched("files/upload", DropboxApi.obj("path" to "/a.bin"), 4L) { progressTrackingBody(byteArrayOf(1, 2, 3, 4), it) }
                    }
                }
            }
        api.close()

        assertEquals(UploadWatchdogException.Reason.NO_RESPONSE, cut.reason)
    }
}
