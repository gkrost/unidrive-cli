package org.krost.unidrive.internxt

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.InsufficientRemoteSpaceException
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Live finding (2026-11-18, Internxt test account): the bridge answers an upload start with HTTP 420
 * `{"message":"Max space used"}` while the drive's usage still shows free space. That is a refusal for space, typed as
 * [InsufficientRemoteSpaceException]: not retried within the attempt, not a permanent refusal of the bytes (freeing space
 * lets the same upload go), and nothing is sent after the refused start.
 */
class InternxtRemoteSpaceRefusalTest {
    private val maxSpaceUsed = HttpStatusCode(420, "Enhance Your Calm") to """{"message":"Max space used"}"""

    private fun withDrive(
        cutoffMb: Int? = null,
        block: suspend (UploadDrive, InternxtProvider, Path) -> Unit,
    ) = runTest {
        val tmp = TestScratch.directory("ud-remote-space-")
        try {
            val drive = UploadDrive()
            val provider = rootedInternxtProvider(tmp, drive.engine) { c -> cutoffMb?.let { c.copy(uploadCutoffMb = it) } ?: c }
            try {
                block(drive, provider, tmp)
            } finally {
                provider.close()
            }
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    private fun local(tmp: Path): Path = tmp.resolve("f.txt").also { Files.writeString(it, "hello world") }

    @Test
    fun `a single-part start refused 420 Max space used fails typed as remote space, once`() =
        withDrive { drive, provider, tmp ->
            drive.startRefusal = maxSpaceUsed

            assertFailsWith<InsufficientRemoteSpaceException> { provider.upload(local(tmp), "/f.txt", existingRemoteId = null, onProgress = null) }

            assertEquals(1, drive.startCalls.get(), "the refused start is not repeated within the attempt")
            assertTrue(drive.createBodies.isEmpty() && drive.shardPutLengths.isEmpty(), "nothing is sent after the refusal")
        }

    @Test
    fun `a multipart start refused 420 Max space used fails typed as remote space`() =
        withDrive(cutoffMb = 0) { drive, provider, tmp ->
            drive.startRefusal = maxSpaceUsed

            assertFailsWith<InsufficientRemoteSpaceException> { provider.upload(local(tmp), "/f.txt", existingRemoteId = null, onProgress = null) }

            assertEquals(1, drive.startCalls.get())
        }

    @Test
    fun `a 420 with another message keeps its own type`() =
        withDrive { drive, provider, tmp ->
            drive.startRefusal = HttpStatusCode(420, "Enhance Your Calm") to """{"message":"slow down"}"""

            val failure = assertFailsWith<InternxtApiException> { provider.upload(local(tmp), "/f.txt", existingRemoteId = null, onProgress = null) }

            assertEquals(420, failure.statusCode)
        }
}
