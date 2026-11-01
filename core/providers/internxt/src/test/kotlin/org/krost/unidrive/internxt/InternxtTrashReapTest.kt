package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `/files` index a poll reads lags a web-UI trash by an hour or more (#14): the trashed row arrives in the sync
 * stream with a bumped `updatedAt` but `status=EXISTS` and no `removed`/`deleted` flag, so nothing in the stream says
 * it is gone. The folder-contents endpoint reflects the same trash at once (measured live, #14's evidence): a changed
 * row the poll still sees as live is therefore verified against its parent folder's content, and a row the folder no
 * longer holds is delivered as a tombstone. A folder row is verified the same way. A content call that fails concludes
 * nothing — eventual reaping stays the answer then, as it is today.
 */
class InternxtTrashReapTest {
    // The root's content without f1: the trash the /files index has not recorded yet.
    private val rootWithoutF1 = mapOf(ROOT to """{"children":[],"files":[]}""")

    @Test
    fun `a file the sync stream still reports as existing but its folder no longer holds is reaped`() =
        runTest {
            val drive =
                DriveMock(
                    folderPages = listOf(emptyList()),
                    filePages = listOf(listOf(fileRow("f1", "2026-10-01T10:05:00Z"))),
                    tree = rootWithoutF1,
                )

            val page = provider(drive.engine).delta("2026-10-01T10:00:00Z", null, quiet())

            val f1 = page.items.single { it.id == "f1" }
            assertTrue(f1.deleted, "the row the folder no longer holds is delivered as a tombstone: ${essence(f1)}")
            assertEquals("/f1.txt", f1.path, "the tombstone names the path the engine must reap")
            assertTrue(drive.other.any { it.contains("/drive/folders/content/$ROOT") }, "the parent folder was asked: ${drive.other}")
        }

    @Test
    fun `a file its folder still holds stays live even though the stream re-delivered it`() =
        runTest {
            val drive =
                DriveMock(
                    folderPages = listOf(emptyList()),
                    filePages = listOf(listOf(fileRow("f1", "2026-10-01T10:05:00Z"))),
                    tree = mapOf(ROOT to """{"children":[],"files":[${fileRow("f1", "2026-10-01T10:05:00Z")}]}"""),
                )

            val page = provider(drive.engine).delta("2026-10-01T10:00:00Z", null, quiet())

            assertFalse(page.items.single { it.id == "f1" }.deleted, "a modified file is not a deletion")
        }

    @Test
    fun `a folder the sync stream still reports as existing but its parent no longer holds is reaped`() =
        runTest {
            val drive =
                DriveMock(
                    folderPages = listOf(listOf(folder("d1", "Docs", ROOT))),
                    filePages = listOf(emptyList()),
                    tree = rootWithoutF1,
                )

            val page = provider(drive.engine).delta("2026-10-01T10:00:00Z", null, quiet())

            assertTrue(page.items.single { it.id == "d1" }.deleted, "the folder row the root no longer holds is a tombstone")
        }

    @Test
    fun `a parent listing that fails concludes nothing and the row stays live`() =
        runTest {
            for (status in listOf(500, 503, 404)) {
                val drive =
                    DriveMock(
                        folderPages = listOf(emptyList()),
                        filePages = listOf(listOf(fileRow("f1", "2026-10-01T10:05:00Z"))),
                        contentStatus = status,
                    )

                val page = provider(drive.engine).delta("2026-10-01T10:00:00Z", null, quiet())

                assertFalse(
                    page.items.single { it.id == "f1" }.deleted,
                    "a $status from the folder content must not reap anything (eventual reaping answers later)",
                )
            }
        }

    @Test
    fun `rows below one parent are verified with one content call and the parent is the row's own`() =
        runTest {
            // Two changed files in d1, one in root, one already tombstoned by the stream (no check needed).
            fun rowInD1(
                uuid: String,
                updatedAt: String,
                status: String = "EXISTS",
            ) = """{"uuid":"$uuid","plainName":"$uuid","type":"txt","size":"1","folderUuid":"d1",""" +
                """"status":"$status","modificationTime":"2026-09-01T10:00:00.123Z","updatedAt":"$updatedAt"}"""
            val feed =
                listOf(
                    fileRow("fa", "2026-10-01T10:05:00Z"),
                    rowInD1("fd", "2026-10-01T10:06:00Z"),
                    rowInD1("fe", "2026-10-01T10:07:00Z"),
                    rowInD1("ft", "2026-10-01T10:08:00Z", status = "TRASHED"),
                )
            val drive =
                DriveMock(
                    folderPages = listOf(listOf(folder("d1", "Docs", ROOT))),
                    filePages = listOf(feed),
                    // d1 still holds fd and fe (ft with it, whatever the index says — it is a tombstone already);
                    // root still holds nothing at all (fa reaped).
                    tree =
                        mapOf(
                            ROOT to """{"children":[${folder("d1", "Docs", ROOT)}],"files":[]}""",
                            "d1" to """{"children":[],"files":[${rowInD1("fd", "2026-10-01T10:06:00Z")},${rowInD1("fe", "2026-10-01T10:07:00Z")}]}""",
                        ),
                )

            val page = provider(drive.engine).delta("2026-10-01T09:00:00Z", null, quiet())

            assertTrue(page.items.single { it.id == "fa" }.deleted, "fa is gone from the root")
            assertFalse(page.items.single { it.id == "fd" }.deleted, "fd is still in its folder")
            assertFalse(page.items.single { it.id == "fe" }.deleted, "fe is still in its folder")
            assertTrue(page.items.single { it.id == "ft" }.deleted, "the stream's own tombstone stays one")
            val contentCalls = drive.other.count { it.contains("/drive/folders/content/") }
            assertEquals(2, contentCalls, "one call per distinct parent (root and d1): ${drive.other}")
        }
}
