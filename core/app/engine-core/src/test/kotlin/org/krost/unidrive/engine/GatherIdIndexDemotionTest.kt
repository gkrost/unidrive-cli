package org.krost.unidrive.engine

import org.krost.unidrive.CloudItem
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** gkrost/unidrive-cli#845 (#971 review): an id demoted from a key by another id's win is withdrawn like a retracted one. */
class GatherIdIndexDemotionTest {
    @Test
    fun `a live occupant displaced into the collision losers by another id is withdrawn`() {
        val changes = HashMap<String, CloudItem>()
        val keyToPath = HashMap<String, String>()
        val withdrawn = mutableListOf<String>()
        // The smaller id wins the key, as the real winner rule ends on.
        val admit = { item: CloudItem ->
            val incumbent = changes[item.path]
            if (incumbent == null || incumbent.id == item.id || item.id < incumbent.id) {
                changes[item.path] = item
                keyToPath[item.path] = item.path
                item
            } else {
                incumbent
            }
        }
        val index = GatherIdIndex(changes, keyToPath, HashMap(), { it }, admit) { withdrawn.add(it.id) }

        index.admitLatest(item("B", "/p"))
        assertEquals(emptyList(), withdrawn, "the first occupant is not withdrawn")
        index.admitLatest(item("C", "/p"))
        assertEquals(emptyList(), withdrawn, "a newcomer that loses is no withdrawal of the occupant")
        index.admitLatest(item("A", "/p"))
        assertEquals(listOf("B"), withdrawn, "the occupant A displaced is withdrawn")
    }

    private fun item(
        id: String,
        path: String,
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast("/"),
        path = path,
        size = 3,
        isFolder = false,
        modified = Instant.parse("2026-03-28T12:00:00Z"),
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = "hash-$id",
        mimeType = null,
    )
}
