package org.krost.unidrive.onedrive

import org.krost.unidrive.UnidriveJson
import org.krost.unidrive.onedrive.model.DriveItem
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * gkrost/unidrive-cli#417: the read-ahead budget of the download URL cache — a claim reserves room until its batch answers,
 * claim time prunes expired entries, and a publication over a stale observation is dropped with the pending marker kept.
 */
class DownloadUrlCacheTest {
    private val clock = AtomicLong(1_000_000L)

    private fun decode(json: String): DriveItem = UnidriveJson.decodeFromString(DriveItem.serializer(), json)

    private fun item(
        id: String,
        parent: String = "P",
        dl: String? = "https://cdn/$id",
    ) = """{"id":"$id","name":"$id.bin","size":4,"file":{"hashes":{"quickXorHash":"QX$id"}},"parentReference":{"id":"$parent"}${dl?.let { ""","@microsoft.graph.downloadUrl":"$it"""" } ?: ""}}"""

    @Test
    fun `a claim reserves read-ahead room until its batch answers`() {
        val cache = DownloadUrlCache({ clock.get() })
        cache.remember((1..32).map { decode(item("id-$it", dl = null)) })

        val first = cache.claimSiblings("P", "current", max = 20, aheadMax = 25)
        assertEquals(20, first.size)
        assertEquals(5, cache.claimSiblings("P", "current", max = 20, aheadMax = 25).size, "a batch is cut to the room left")
        assertEquals(0, cache.claimSiblings("P", "current", max = 20, aheadMax = 25).size, "a fully reserved budget starts none")

        cache.releaseClaims(first.take(5))
        assertEquals(5, cache.claimSiblings("P", "current", max = 20, aheadMax = 25).size, "released reservations fund a new claim")

        val published =
            cache.put(
                first.map { decode(item(it.id, dl = "https://cdn/${it.id}-b")) },
                first.associate { it.id to it.revision },
                prefetched = true,
            )
        assertEquals(first.map { it.id }.toSet(), published.keys)
        assertEquals(0, cache.claimSiblings("P", "current", max = 20, aheadMax = 25).size, "published entries keep occupying the budget")

        cache.fresh(published.keys.first())
        assertEquals(1, cache.claimSiblings("P", "current", max = 20, aheadMax = 25).size, "a download using an entry frees its slot")
    }

    @Test
    fun `claim time prunes expired entries before measuring the budget`() {
        val cache = DownloadUrlCache({ clock.get() })
        val items = (1..25).map { decode(item("held-$it")) }
        cache.put(items, items.associate { it.id to cache.revision(it.id) }, prefetched = true)
        cache.remember((1..25).map { decode(item("pending-$it", dl = null)) })

        clock.addAndGet(46 * 60 * 1000L) // the read-ahead expires
        assertEquals(20, cache.claimSiblings("P", "current", max = 20, aheadMax = 25).size, "the expired read-ahead was pruned")
        assertEquals(5, cache.claimSiblings("P", "current", max = 20, aheadMax = 25).size, "the remaining room serves the rest")
    }

    @Test
    fun `a publication over a stale observation is dropped and the pending marker survives`() {
        val cache = DownloadUrlCache({ clock.get() })
        cache.remember(listOf(decode(item("id-1", dl = null))))
        val revision = cache.revision("id-1")
        cache.remember(listOf(decode(item("id-1", dl = null)))) // a newer observation for the same id

        val published = cache.put(listOf(decode(item("id-1"))), mapOf("id-1" to revision), prefetched = true)
        assertEquals(emptyMap<String, DownloadUrlCache.HeldEntry>(), published, "the stale publication did not land")
        assertEquals(listOf("id-1"), cache.claimSiblings("P", "current", 20, aheadMax = 10).map { it.id }, "the id is still pending for a fresh batch")
    }
}
