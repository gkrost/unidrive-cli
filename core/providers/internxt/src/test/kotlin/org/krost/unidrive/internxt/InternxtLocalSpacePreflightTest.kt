package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.InsufficientLocalSpaceException
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#441 finding 10: the upload stages a full encrypted copy of the file next to the profile. Before that
 * copy is written, the volume must have room for it plus a margin ([InternxtProvider.STAGING_SPACE_MARGIN_BYTES]); without
 * it the upload fails with the typed [InsufficientLocalSpaceException] (not a permanent refusal: it can go once space is
 * freed), before anything is staged or sent.
 */
class InternxtLocalSpacePreflightTest {
    private val content = "hello world"

    private fun withDrive(block: suspend (UploadDrive, InternxtProvider, Path) -> Unit) =
        runTest {
            val tmp = TestScratch.directory("ud-space-")
            try {
                val drive = UploadDrive()
                val provider = rootedInternxtProvider(tmp, drive.engine)
                try {
                    block(drive, provider, tmp)
                } finally {
                    provider.close()
                }
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }

    private fun local(tmp: Path): Path = tmp.resolve("f.txt").also { Files.writeString(it, content) }

    @Test
    fun `an upload without room to stage its ciphertext fails typed before anything is staged or sent`() =
        withDrive { drive, provider, tmp ->
            provider.usableSpaceOf = { content.length + InternxtProvider.STAGING_SPACE_MARGIN_BYTES - 1 }

            val failure = assertFailsWith<InsufficientLocalSpaceException> { provider.upload(local(tmp), "/f.txt", existingRemoteId = null, onProgress = null) }

            assertEquals(content.length + InternxtProvider.STAGING_SPACE_MARGIN_BYTES, failure.requiredBytes)
            assertTrue(drive.requests.none { "/files/start" in it || it.startsWith("POST /drive/files") }, "nothing is sent: ${drive.requests}")
            val staged = tmp.resolve("upload-tombstones")
            assertTrue(!Files.exists(staged) || Files.list(staged).use { s -> s.noneMatch { it.toString().endsWith(".enc") } }, "no ciphertext is staged")
        }

    @Test
    fun `an upload with exactly the room it needs goes through`() =
        withDrive { drive, provider, tmp ->
            provider.usableSpaceOf = { content.length + InternxtProvider.STAGING_SPACE_MARGIN_BYTES }

            val item = provider.upload(local(tmp), "/f.txt", existingRemoteId = null, onProgress = null)

            assertEquals("f.txt", item.name)
            assertEquals(1, drive.createBodies.size)
        }

    @Test
    fun `an unreadable free-space figure does not block the upload`() =
        withDrive { drive, provider, tmp ->
            provider.usableSpaceOf = { throw java.io.IOException("statvfs failed") }

            provider.upload(local(tmp), "/f.txt", existingRemoteId = null, onProgress = null)

            assertEquals(1, drive.createBodies.size)
        }
}
