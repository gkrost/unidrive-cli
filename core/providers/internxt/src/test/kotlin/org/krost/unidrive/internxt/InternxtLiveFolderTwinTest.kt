package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ProviderException
import org.krost.unidrive.internxt.model.InternxtFile
import org.krost.unidrive.internxt.model.InternxtFolder
import org.krost.unidrive.testing.TestScratch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#747 finding 1 (remainder): every name match against the folders of a listing counts LIVE folders only,
 * and two live folders of one name are ambiguous, never resolved by picking whichever the listing shows first. A trashed
 * folder must neither be adopted by a folder create nor block the keep-both ladder of a file create (both the bridge
 * create and the empty-file create).
 */
class InternxtLiveFolderTwinTest {
    private val log = LoggerFactory.getLogger(InternxtLiveFolderTwinTest::class.java)

    private fun withDrive(block: suspend (UploadDrive, InternxtProvider, java.nio.file.Path) -> Unit) =
        runTest {
            val tmp = TestScratch.directory("ud-live-twin-")
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

    @Test
    fun `a folder create answered 409 with two live twins fails as ambiguous instead of picking one`() =
        withDrive { drive, provider, _ ->
            drive.folders += UploadDriveFolder("live-a", "docs", "root-folder-uuid")
            drive.folders += UploadDriveFolder("live-b", "docs", "root-folder-uuid")

            val failure = assertFailsWith<ProviderException> { provider.createFolder("/docs") }

            assertTrue("ambiguous" in failure.message.orEmpty(), "got: ${failure.message}")
            assertTrue(drive.requests.none { it.startsWith("PUT ") || it.startsWith("DELETE ") }, "no write request: ${drive.requests}")
        }

    @Test
    fun `a file-create collision whose only same-named folder is trashed runs the content ladder`() {
        val live = InternxtFile(uuid = "f1", plainName = "a", type = "txt", size = "3", folderUuid = "p")
        val trashed = InternxtFolder(uuid = "d1", plainName = "a.txt", parentUuid = "p", status = "TRASHED")

        val pick = pickCreateCollisionCandidate(listOf(live), listOf(trashed), "a.txt", "/a.txt", log)

        assertIs<CreateCollisionPick.Candidate>(pick)
        assertEquals("f1", pick.file.uuid)
    }

    @Test
    fun `a file-create collision with a live same-named folder is still refused`() {
        val live = InternxtFile(uuid = "f1", plainName = "a", type = "txt", size = "3", folderUuid = "p")
        val folder = InternxtFolder(uuid = "d1", plainName = "a.txt", parentUuid = "p")

        assertEquals(CreateCollisionPick.Refuse, pickCreateCollisionCandidate(listOf(live), listOf(folder), "a.txt", "/a.txt", log))
    }

    @Test
    fun `an empty-file create answered 409 whose only same-named folder is trashed lands a conflict copy`() =
        withDrive { drive, provider, tmp ->
            drive.files += UploadDriveFile("held", "x", "txt", 5L, "bucket-old", "root-folder-uuid")
            drive.folders += UploadDriveFolder("trashed-x", "x.txt", "root-folder-uuid", status = "TRASHED")
            val local = Files.createFile(tmp.resolve("x.txt"))

            val item = provider.upload(local, "/x.txt", existingRemoteId = null, onProgress = null)

            assertNotEquals("held", item.id, "the non-empty remote is kept, not adopted")
            assertNotEquals("x.txt", item.name, "the empty file lands under a conflict name: ${item.name}")
            assertEquals(2, drive.createBodies.size, "the refused create, then the conflict copy: ${drive.createBodies}")
            assertEquals("TRASHED", drive.folders.single().status, "the trashed folder is left alone")
        }
}
