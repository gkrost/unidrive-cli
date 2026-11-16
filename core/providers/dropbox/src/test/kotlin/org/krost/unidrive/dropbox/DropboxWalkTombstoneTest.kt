package org.krost.unidrive.dropbox

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.dropbox.FakeDropbox.Companion.deleted
import org.krost.unidrive.dropbox.FakeDropbox.Companion.file
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A deleted entry names no item, so it carries an empty id and pairs with the row by path (DropboxTombstoneIdTest). The
 * one exception is a single delta walk: a path created and then deleted inside it is one item that came and went, so the
 * tombstone carries the id of the entry seen earlier in the same walk. Without it the engine reads a live item next to
 * a delete of a path that has no row, and the live one wins whatever the order (gkrost/unidrive-cli#743). The walk's map
 * lives for one walk: a later poll, a probe and a restart never feed it.
 */
class DropboxWalkTombstoneTest {
    private val dir = Files.createTempDirectory("dropbox-walk-tombstone")

    private fun provider(fake: FakeDropbox) = FakeDropbox.provider(fake, dir)

    private fun page(
        vararg entries: String,
        cursor: String,
        more: Boolean = false,
    ) = """{"entries":[${entries.joinToString(",")}],"cursor":"$cursor","has_more":$more}"""

    @Test
    fun `an item created and deleted in one page gets its id on the tombstone`() =
        runTest {
            val fake = FakeDropbox()
            val provider = provider(fake)
            fake.json("files/list_folder/continue", page(file("/tmp.txt", "id:7"), deleted("/TMP.txt"), cursor = "c2"))

            val items = provider.delta("c1").items

            assertEquals(listOf(false, true), items.map { it.deleted })
            assertEquals("id:7", items[1].id, "the tombstone is that of the item the same walk created")
        }

    @Test
    fun `an item created on one page and deleted on the next gets its id on the tombstone`() =
        runTest {
            val fake = FakeDropbox()
            val provider = provider(fake)
            fake.json("files/list_folder/continue", page(file("/tmp.txt", "id:7"), cursor = "c2", more = true))
            fake.json("files/list_folder/continue", page(deleted("/tmp.txt"), cursor = "c3"))

            provider.delta("c1")
            val tombstone = provider.delta("c2").items.single()

            assertTrue(tombstone.deleted)
            assertEquals("id:7", tombstone.id)
        }

    @Test
    fun `a delete that comes before the create carries no id`() =
        runTest {
            val fake = FakeDropbox()
            val provider = provider(fake)
            fake.json("files/list_folder/continue", page(deleted("/a.txt"), file("/a.txt", "id:8"), cursor = "c2"))

            val items = provider.delta("c1").items

            assertEquals("", items[0].id, "no live entry was seen before it in the walk")
            assertEquals("id:8", items[1].id)
        }

    @Test
    fun `the id is used once - a second delete of the path carries none`() =
        runTest {
            val fake = FakeDropbox()
            val provider = provider(fake)
            fake.json("files/list_folder/continue", page(file("/a.txt", "id:7"), deleted("/a.txt"), deleted("/a.txt"), cursor = "c2"))

            assertEquals(listOf("id:7", "id:7", ""), provider.delta("c1").items.map { it.id })
        }

    @Test
    fun `an item seen in an earlier walk gives its tombstone no id`() =
        runTest {
            val fake = FakeDropbox()
            val provider = provider(fake)
            fake.json("files/list_folder", page(file("/a.txt", "id:7"), cursor = "c1"))
            provider.delta(null)
            fake.json("files/list_folder/continue", page(deleted("/a.txt"), cursor = "c2"))

            assertEquals("", provider.delta("c1").items.single().id, "the walk that saw id:7 ended; a later poll's delete is a delete of the path")
        }

    @Test
    fun `a probe never feeds the walk`() =
        runTest {
            val fake = FakeDropbox()
            val provider = provider(fake)
            fake.json("files/list_folder/continue", page(file("/other.txt", "id:1"), cursor = "c2", more = true))
            fake.json("files/list_folder/continue", page(deleted("/a.txt"), cursor = "c3"))
            fake.json("files/get_metadata", file("/a.txt", "id:B"))
            provider.delta("c1")
            provider.getMetadata("/a.txt") // in the middle of the walk
            assertEquals("", provider.delta("c2").items.single().id, "the probe's id is no evidence of what the walk's delete removed")
        }

    @Test
    fun `a new walk forgets the last one`() =
        runTest {
            val fake = FakeDropbox()
            val provider = provider(fake)
            // the first walk ends having seen id:7; the second one only deletes the path
            fake.json("files/list_folder/continue", page(file("/a.txt", "id:7"), cursor = "c2"))
            fake.json("files/list_folder/continue", page(deleted("/a.txt"), cursor = "c3"))
            provider.delta("c1")

            assertEquals("", provider.delta("c2").items.single().id)
        }

    @Test
    fun `a full-listing walk feeds the map with nothing`() =
        runTest {
            val fake = FakeDropbox()
            val provider = provider(fake)
            fake.json("files/list_folder", page(file("/a.txt", "id:1"), file("/b.txt", "id:2"), cursor = "k1", more = true))
            fake.json("files/list_folder/continue", page(file("/c.txt", "id:3"), cursor = "k2"))

            provider.delta(null)
            assertEquals(0, provider.walkEntries, "a full listing's tombstones are dropped anyway: no entry per file of the drive (page 1)")
            provider.delta("k1")
            assertEquals(0, provider.walkEntries, "(last page)")
        }

    @Test
    fun `the map holds the change feed while an incremental walk runs and nothing after its last page`() =
        runTest {
            val fake = FakeDropbox()
            val provider = provider(fake)
            fake.json("files/list_folder/continue", page(file("/a.txt", "id:1"), cursor = "c2", more = true))
            fake.json("files/list_folder/continue", page(file("/b.txt", "id:2"), cursor = "c3"))

            provider.delta("c1")
            assertEquals(1, provider.walkEntries, "the walk is in progress: its entries pair a later delete")
            provider.delta("c2")
            assertEquals(0, provider.walkEntries, "the walk ended: an idle daemon keeps nothing")
        }
}
