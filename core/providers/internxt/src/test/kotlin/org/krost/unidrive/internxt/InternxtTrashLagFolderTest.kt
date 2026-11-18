package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ParentGoneException
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Live finding E3 (test drive 2, 2026-11-18): after this provider trashed a folder, Internxt kept listing it as live for 40 to
 * 160 s. A remake of the folder answered 409, and the 409 recovery adopted the folder being trashed, so the next upload
 * below it failed again. The provider remembers the folders it trashed or deleted itself for a while: it never adopts one
 * after a 409 (it waits and repeats the create, a few times, then fails with [FolderNameHeldByTrashException]), and a path
 * walk never resolves to one. A live twin it did not trash is adopted as before.
 */
class InternxtTrashLagFolderTest {
    private fun withDrive(block: suspend (UploadDrive, InternxtProvider, Path) -> Unit) =
        runTest {
            val tmp = TestScratch.directory("ud-trash-lag-")
            try {
                val drive = UploadDrive()
                drive.folders += UploadDriveFolder("t1-old", "t1", "root-folder-uuid")
                val provider = rootedInternxtProvider(tmp, drive.engine)
                provider.api.trashedFolders.createRetryDelaysMs = listOf(1_000L, 2_000L, 4_000L)
                try {
                    block(drive, provider, tmp)
                } finally {
                    provider.close()
                }
            } finally {
                tmp.toFile().deleteRecursively()
            }
        }

    private fun UploadDrive.folderCreates() = requests.count { it == "POST /drive/folders" }

    @Test
    fun `a 409 whose only match is a folder this provider trashed is not adopted, the create is repeated until it goes`() =
        withDrive { drive, provider, _ ->
            provider.delete("/t1", null)
            assertEquals(listOf("t1-old"), drive.trashed.toList())
            // The trash lands at the server's pace: the old folder leaves the listing before the third create.
            drive.onFolderCreate = { call -> if (call == 3) drive.folders.removeIf { it.uuid == "t1-old" } }

            val folder = provider.createFolder("/t1")

            assertNotEquals("t1-old", folder.id, "the folder being trashed is never adopted")
            assertEquals(3, drive.folderCreates())
        }

    @Test
    fun `a 409 that stays held by a folder this provider trashed fails typed and retryable, not as parent gone`() =
        withDrive { drive, provider, _ ->
            provider.delete("/t1", null)

            val failure = assertFailsWith<FolderNameHeldByTrashException> { provider.createFolder("/t1") }

            assertFalse((failure as Throwable) is ParentGoneException)
            assertEquals(4, drive.folderCreates(), "the first create and three repeats")
        }

    @Test
    fun `a live twin this provider did not trash is adopted as before`() =
        withDrive { drive, provider, _ ->
            val folder = provider.createFolder("/t1")

            assertEquals("t1-old", folder.id)
            assertEquals(1, drive.folderCreates())
        }

    @Test
    fun `an upload never resolves its parent to a folder this provider trashed`() =
        withDrive { drive, provider, tmp ->
            provider.delete("/t1", null)
            val local = tmp.resolve("a.txt").also { Files.writeString(it, "hello") }

            assertFailsWith<ParentGoneException> { provider.upload(local, "/t1/a.txt", existingRemoteId = null, onProgress = null) }

            assertTrue(drive.files.none { it.folderUuid == "t1-old" }, "nothing lands in the folder being trashed")
        }
}
