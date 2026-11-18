package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.RemoteConflictException
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Live finding F1 (2026-11-18, mount mode): a replace landed over a NEWER remote version. A replace that carries a write
 * token ("uuid|modificationTime|size") is refused with [RemoteConflictException] when the file's current meta does not
 * match it, before any byte is staged or sent. The modification time is compared to the whole second (the server spells
 * the same instant with and without milliseconds, depending on the answer) and the size exactly. Without a token the
 * replace is unchanged.
 */
class InternxtWriteTokenPreconditionTest {
    private fun withDrive(block: suspend (UploadDrive, InternxtProvider, Path) -> Unit) =
        runTest {
            val tmp = TestScratch.directory("ud-write-token-")
            try {
                val drive = UploadDrive()
                // The remote: modified 10:00:00.000, 3 bytes.
                drive.files += UploadDriveFile("file-r", "f", "txt", 3L, "old-entry", "root-folder-uuid")
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

    private fun local(tmp: Path): Path = tmp.resolve("f.txt").also { Files.writeString(it, "hello world") }

    private fun UploadDrive.nothingSent() =
        assertTrue(requests.none { "/files/start" in it } && replaceFileIds.isEmpty() && shardPutLengths.isEmpty(), "nothing sent: $requests")

    @Test
    fun `a replace whose token names an older version is refused before any byte is sent`() =
        withDrive { drive, provider, tmp ->
            assertFailsWith<RemoteConflictException> {
                provider.upload(local(tmp), "/f.txt", existingRemoteId = "file-r", ifMatchETag = "file-r|2026-10-01T09:59:31.000Z|3", onProgress = null)
            }
            drive.nothingSent()
            assertEquals("old-entry", drive.files.single().fileId)
        }

    @Test
    fun `a replace whose token names the same time but another size is refused before any byte is sent`() =
        withDrive { drive, provider, tmp ->
            assertFailsWith<RemoteConflictException> {
                provider.upload(local(tmp), "/f.txt", existingRemoteId = "file-r", ifMatchETag = "file-r|2026-10-01T10:00:00.000Z|14", onProgress = null)
            }
            drive.nothingSent()
        }

    @Test
    fun `a matching token spelled without milliseconds lets the replace go`() =
        withDrive { drive, provider, tmp ->
            provider.upload(local(tmp), "/f.txt", existingRemoteId = "file-r", ifMatchETag = "file-r|2026-10-01T10:00:00Z|3", onProgress = null)

            assertEquals(listOf<String?>("bucket-entry-1"), drive.replaceFileIds.toList())
        }

    @Test
    fun `a matching token spelled exactly lets the replace go`() =
        withDrive { drive, provider, tmp ->
            provider.upload(local(tmp), "/f.txt", existingRemoteId = "file-r", ifMatchETag = "file-r|2026-10-01T10:00:00.000Z|3", onProgress = null)

            assertEquals(1, drive.replaceFileIds.size)
        }

    @Test
    fun `a replace without a token is unchanged`() =
        withDrive { drive, provider, tmp ->
            provider.upload(local(tmp), "/f.txt", existingRemoteId = "file-r", onProgress = null)

            assertEquals(1, drive.replaceFileIds.size)
        }

    @Test
    fun `the token comparison is to the whole second and the exact size`() {
        assertTrue(sameWriteToken("u|2026-10-01T10:00:00.000Z|3", "u|2026-10-01T10:00:00Z|3"))
        assertTrue(sameWriteToken("u|2026-10-01T10:00:00.999Z|3", "u|2026-10-01T10:00:00Z|3"))
        assertTrue(!sameWriteToken("u|2026-10-01T10:00:01.000Z|3", "u|2026-10-01T10:00:00Z|3"))
        assertTrue(!sameWriteToken("u|2026-10-01T10:00:00Z|3", "u|2026-10-01T10:00:00Z|4"))
        assertTrue(!sameWriteToken("u|2026-10-01T10:00:00Z|3", "v|2026-10-01T10:00:00Z|3"))
        assertTrue(sameWriteToken("u||3", "u||3"), "no time on either side: compared as written")
        assertTrue(!sameWriteToken("u|not-a-time|3", "u|2026-10-01T10:00:00Z|3"))
    }
}
