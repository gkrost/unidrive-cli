package org.krost.unidrive.dropbox

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.dropbox.FakeDropbox.Companion.deleted
import org.krost.unidrive.dropbox.FakeDropbox.Companion.file
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

/**
 * A Dropbox deleted entry carries no id: the API names the path alone. The engine ignores a tombstone whose id differs
 * from the id its row holds at that path (isTombstoneOfOtherItem), so an id the provider invents for a tombstone decides
 * whether a genuine delete is honoured (gkrost/unidrive-cli#743). The id this process last saw at the path is no
 * evidence of what the delete removed: a probe may have seen an item the engine never applied, and a restarted daemon
 * has seen nothing. A Dropbox tombstone therefore carries no id, and pairs with the row by path, as a delete of the
 * path does; its behaviour must not depend on how long the process has been up.
 */
class DropboxTombstoneIdTest {
    private val dir = TestScratch.directory("dropbox-tombstone-id")

    private fun provider(fake: FakeDropbox) = FakeDropbox.provider(fake, dir)

    @Test
    fun `a deletion carries no id although a probe saw another item at its path`() =
        runTest {
            val fake = FakeDropbox()
            val provider = provider(fake)
            // the engine never applied this item (it is not the row's item), but a probe of the path saw it
            fake.json("files/get_metadata", file("/a.txt", "id:B"))
            assertEquals("id:B", provider.getMetadata("/a.txt").id, "setup: the probe saw the live item")
            fake.json("files/list_folder/continue", """{"entries":[${deleted("/a.txt")}],"cursor":"c2","has_more":false}""")

            val tombstone = provider.delta("c1").items.single()

            assertTrue(tombstone.deleted)
            assertEquals("", tombstone.id, "a delete of the path names no item; the probe's id would make the engine ignore it against the row's id")
        }

    @Test
    fun `a deletion reads the same in a long-running process and after a restart`() =
        runTest {
            val running = FakeDropbox()
            val longRunning = provider(running)
            running.json("files/list_folder", """{"entries":[${file("/a.txt", "id:7")}],"cursor":"c1","has_more":false}""")
            longRunning.delta(null)
            running.json("files/list_folder/continue", """{"entries":[${deleted("/a.txt")}],"cursor":"c2","has_more":false}""")

            val restartedFake = FakeDropbox()
            val restarted = provider(restartedFake)
            restartedFake.json("files/list_folder/continue", """{"entries":[${deleted("/a.txt")}],"cursor":"c2","has_more":false}""")

            assertEquals(
                restarted.delta("c1").items.single().id,
                longRunning.delta("c1").items.single().id,
                "the same delete must carry the same id whatever the daemon's uptime",
            )
        }

    @Test
    fun `guard - a deletion is still reported for its path with the path's display case`() =
        runTest {
            val fake = FakeDropbox()
            val provider = provider(fake)
            fake.json("files/list_folder/continue", """{"entries":[${deleted("/Docs/Report.PDF")}],"cursor":"c2","has_more":false}""")

            val tombstone = provider.delta("c1").items.single()

            assertTrue(tombstone.deleted)
            assertEquals("/Docs/Report.PDF", tombstone.path)
            assertEquals("Report.PDF", tombstone.name)
        }
}
