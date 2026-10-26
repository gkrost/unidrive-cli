package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CapabilityResult
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Candidate P8 of the audit: the cursor the first gather hands the engine is the client's clock. A poll asks for the rows
 * changed since that cursor minus a window of [InternxtProvider.REWIND_CURSOR_SECONDS]; the server compares it with its own
 * `updatedAt`. With the client's clock ahead by more than the window, a row changed on the server after the gather is older
 * than the cursor and no poll returns it.
 */
class InternxtCursorSkewTest {
    @Test
    fun `a change made after the gather is returned although the client clock runs ten minutes ahead of the server`() =
        runTest {
            val tmp = Files.createTempDirectory("ud-cursor-skew-")
            try {
                val serverNow = Instant.now().minus(10, ChronoUnit.MINUTES)
                val drive = UploadDrive()
                val provider = rootedInternxtProvider(tmp, drive.engine)
                try {
                    val cursor = ((provider.deltaFromLatest(null)) as CapabilityResult.Success).value.cursor
                    // One minute of the server's time after the gather.
                    drive.files +=
                        UploadDriveFile("late-1", "late", "txt", 1, "f", "root-folder-uuid", updatedAt = serverNow.plusSeconds(60).toString())
                    val page = provider.delta(cursor, null, null)
                    assertTrue(page.items.any { it.name == "late.txt" }, "the poll since '$cursor' missed a change made one minute after it, by the server's clock")
                    assertEquals(1, page.items.size)
                } finally {
                    provider.close()
                }
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }
}
