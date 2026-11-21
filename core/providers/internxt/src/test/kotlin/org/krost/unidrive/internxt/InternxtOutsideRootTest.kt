package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An account whose drive root is empty and whose only content is a tree below folders outside the root (a device backup
 * of the desktop app, and a tree the device list does not name). A full gather names those items as outside the root,
 * apart from the items below a folder that is gone, with the top-level folders they hang below; it lists none of them
 * and writes nothing. A poll from the cursor the gather handed back, with nothing changed, reads none of the tree again.
 */
class InternxtOutsideRootTest {
    private fun drive() =
        FakeDrive(
            listOf(FakeFolder("B1", "Documents", "DEV", fakeAt("10:05:00"))),
            listOf(
                FakeFile("b0", "top", "DEV", fakeAt("10:01:00")),
                FakeFile("b1", "report", "B1", fakeAt("10:02:00")),
                FakeFile("p1", "holiday", "PH", fakeAt("10:03:00")),
                FakeFile("g1", "lost", "GONE", fakeAt("10:04:00")),
            ),
            pageSize = 100,
            metaOnly = listOf(FakeFolder("DEV", "Laptop", null, fakeAt("09:00:00")), FakeFolder("PH", "Camera", null, fakeAt("09:01:00"))),
            metaGone = setOf("GONE"),
            devices = listOf("DEV"),
        )

    @Test
    fun `a full gather names the tree outside the root apart from the unreachable items, with its top-level folders`() =
        runTest {
            val drive = drive()

            val page = internxtProviderOn(drive.engine).delta(null, null, FakeStaging().context())

            assertTrue(page.complete)
            assertEquals(emptyList(), page.items.map { it.path }, "the drive root is empty")
            assertEquals(setOf("b0", "b1", "B1", "p1"), page.outsideRootIds)
            assertEquals(setOf("DEV", "PH"), page.outsideRootFolders)
            assertEquals(setOf("b0", "b1", "B1", "p1", "g1"), page.unreachableIds, "both kinds keep their rows")
            assertEquals(emptyList(), drive.writes, "nothing is written")
        }

    @Test
    fun `a poll from the gather's cursor with nothing changed reads none of the tree again`() =
        runTest {
            val drive = drive()
            val provider = internxtProviderOn(drive.engine)
            val full = provider.delta(null, null, FakeStaging().context())
            val metaBefore = drive.metaRequests.toList()
            val devicesBefore = drive.deviceRequests.get()

            // The cursor the full gather handed back: nothing changed since.
            val poll = provider.delta(full.cursor, null, FakeStaging().context())

            assertTrue(poll.complete)
            assertEquals(emptyList(), poll.items)
            assertEquals(emptySet(), poll.outsideRootIds, "no item of the tree is delivered again")
            assertEquals(metaBefore, drive.metaRequests.toList(), "no folder of the tree is asked for")
            assertEquals(devicesBefore, drive.deviceRequests.get(), "the device list is not asked again")
            assertEquals(emptyList(), drive.writes)
        }
}
