package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CapabilityResult
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#181: the cursor a gather hands the engine is a time. A poll asks for the rows changed since that cursor
 * minus a window of [InternxtProvider.REWIND_CURSOR_SECONDS]; the server compares it with its own `updatedAt`. A cursor taken
 * from the client's clock, with that clock ahead of the server's by more than the window, lies after a change made on the
 * server since the gather, and no poll returns it. The cursor comes from the server's time (the `Date` header of its answers).
 */
class InternxtCursorSkewTest {
    private val behind = Duration.ofMinutes(10)

    private fun serverNow(): Instant = Instant.now().minus(behind)

    private fun withProvider(
        drive: UploadDrive,
        block: suspend (InternxtProvider) -> Unit,
    ) = runTest {
        val tmp = Files.createTempDirectory("ud-cursor-skew-")
        try {
            val provider = rootedInternxtProvider(tmp, drive.engine)
            try {
                block(provider)
            } finally {
                provider.close()
            }
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    private fun lateFile(updatedAt: Instant) =
        UploadDriveFile("late-1", "late", "txt", 1, "f", "root-folder-uuid", updatedAt = updatedAt.toString())

    @Test
    fun `a change made after the fast bootstrap is returned although the client clock runs ten minutes ahead of the server`() {
        val drive = UploadDrive(serverClock = ::serverNow)
        withProvider(drive) { provider ->
            val cursor = ((provider.deltaFromLatest(null)) as CapabilityResult.Success).value.cursor
            drive.files += lateFile(serverNow().plusSeconds(60))
            val page = provider.delta(cursor, null, null)
            assertTrue(page.items.any { it.name == "late.txt" }, "the poll since '$cursor' missed a change made one minute after it, by the server's clock")
            assertEquals(1, page.items.size)
        }
    }

    @Test
    fun `a change made after a gather of an empty account is returned although the client clock runs ten minutes ahead`() {
        val drive = UploadDrive(serverClock = ::serverNow)
        withProvider(drive) { provider ->
            val cursor = provider.delta(null, null, null).cursor
            assertTrue(Instant.parse(cursor).isBefore(Instant.now().minus(8, ChronoUnit.MINUTES)), "the cursor '$cursor' is the client's clock")
            drive.files += lateFile(serverNow().plusSeconds(60))
            val page = provider.delta(cursor, null, null)
            assertTrue(page.items.any { it.name == "late.txt" }, "the poll since '$cursor' missed a change made one minute after the gather")
        }
    }

    @Test
    fun `a server that sends no time leaves the client clock as the cursor`() {
        val drive = UploadDrive()
        withProvider(drive) { provider ->
            val before = Instant.now()
            val cursor = ((provider.deltaFromLatest(null)) as CapabilityResult.Success).value.cursor
            val taken = Instant.parse(cursor)
            assertTrue(!taken.isBefore(before.minusSeconds(1)) && !taken.isAfter(Instant.now().plusSeconds(1)), "cursor '$cursor'")
        }
    }
}
