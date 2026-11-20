package org.krost.unidrive.dropbox

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.dropbox.FakeDropbox.Companion.deleted
import org.krost.unidrive.dropbox.FakeDropbox.Companion.file
import org.krost.unidrive.testing.TestScratch
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A mid-walk cursor of a full listing says so itself, so the page it opens is still a listing when it is fetched twice
 * (an engine-level retry) or by another process (a restart that resumed a persisted mid-walk cursor): the `deleted`
 * entries of such a page are history, never a deletion to act on (gkrost/unidrive-cli#841; #748 finding 13). If this
 * test is removed or loosened, a resumed listing silently hands history to the engine as deletions.
 */
class DropboxFullListingCursorTest {
    private val dir = TestScratch.directory("dropbox-full-listing-cursor")

    private fun page(
        vararg entries: String,
        cursor: String,
        more: Boolean = false,
    ) = """{"entries":[${entries.joinToString(",")}],"cursor":"$cursor","has_more":$more}"""

    private fun scripted(): FakeDropbox {
        val fake = FakeDropbox()
        fake.json("files/list_folder", page(file("/a.txt", "id:1"), cursor = "k1", more = true))
        fake.json("files/list_folder/continue", page(file("/b.txt", "id:2"), deleted("/old.txt"), cursor = "k2"))
        return fake
    }

    private fun sentCursors(fake: FakeDropbox) = fake.callsTo("files/list_folder/continue").map { it.args!!["cursor"]!!.toString().trim('"') }

    @Test
    fun `a page of a full listing fetched twice drops its deleted entries both times`() =
        runTest {
            val fake = scripted()
            val provider = FakeDropbox.provider(fake, dir)

            val handed = provider.delta(null).cursor!!
            val first = provider.delta(handed)
            val again = provider.delta(handed)

            assertEquals(listOf("id:2"), first.items.map { it.id })
            assertEquals(listOf("id:2"), again.items.map { it.id }, "the retry must not read the history entry as a deletion")
            assertEquals(listOf("k1", "k1"), sentCursors(fake), "Dropbox gets its own cursor, without the marker")
        }

    @Test
    fun `a mid-walk cursor resumed by a fresh provider still reads as a full listing`() =
        runTest {
            val handed = FakeDropbox.provider(scripted(), dir).delta(null).cursor!!
            val fake = scripted()

            val resumed = FakeDropbox.provider(fake, dir).delta(handed)

            assertEquals(listOf("id:2"), resumed.items.map { it.id }, "no deletion may come out of a listing page")
            assertEquals(listOf("k1"), sentCursors(fake))
        }

    @Test
    fun `the cursor of a finished walk is bare and its later pages stay incremental`() =
        runTest {
            val fake = scripted()
            fake.json("files/list_folder/continue", page(deleted("/gone.txt"), cursor = "k3"))
            val provider = FakeDropbox.provider(fake, dir)

            val last = provider.delta(provider.delta(null).cursor!!)
            val incremental = provider.delta(last.cursor)

            assertEquals("k2", last.cursor)
            assertEquals(listOf(true), incremental.items.map { it.deleted })
        }
}
