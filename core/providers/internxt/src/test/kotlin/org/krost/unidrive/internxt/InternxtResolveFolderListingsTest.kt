package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// gkrost/unidrive-cli#441 item 8: finding one child folder of a path segment must not read the combined folders+files body
// (about 105 s and 30 MB for a folder of 29k files); only the folder stream is read.
class InternxtResolveFolderListingsTest {
    private fun folder(
        uuid: String,
        name: String,
        parent: String,
    ) = FakeMoveItem(uuid, name, parent, isFolder = true)

    private fun file(
        uuid: String,
        name: String,
        parent: String,
    ) = FakeMoveItem(uuid, name, parent, isFolder = false, type = "txt")

    private fun drive() =
        FakeMoveDrive(
            listOf(
                folder("A", "a", "root"),
                folder("B", "b", "A"),
                folder("C", "c", "B"),
                file("f1", "big1", "root"),
                file("f2", "big2", "A"),
                file("f3", "big3", "B"),
                file("f4", "inside", "C"),
            ),
        )

    @Test
    fun `resolving a path reads the folder streams alone and no file listing`() =
        runTest {
            val drive = drive()

            internxtProviderOn(drive.engine).listChildren("/a/b/c")

            // The walk: one folder stream per segment. The only combined listing is the one of the folder asked for.
            assertEquals(
                listOf(
                    "folders/v2/content/root/folders",
                    "folders/v2/content/A/folders",
                    "folders/v2/content/B/folders",
                    "folders/content/C",
                ),
                drive.reads,
            )
        }

    @Test
    fun `a second resolve of the same path reads nothing for the walk`() =
        runTest {
            val drive = drive()
            val provider = internxtProviderOn(drive.engine)
            provider.listChildren("/a/b/c")
            drive.reads.clear()

            provider.listChildren("/a/b/c")

            assertEquals(listOf("folders/content/C"), drive.reads, "the walk comes from the folder cache")
        }
}
