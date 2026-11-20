package org.krost.unidrive.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.CloudRelocatorTest.FakeProvider
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * #743 finding 9: the walk launched one coroutine per file and parked it on the transfer semaphore, so it enumerated
 * the whole source tree far ahead of the transfers and a migration of millions of files held millions of coroutines.
 * A file now takes its transfer slot before its coroutine is launched: while every slot is busy the walk waits.
 */
class CloudRelocatorBoundedWalkTest {
    private val source = FakeProvider("source")
    private val target = FakeProvider("target")

    private fun file(path: String) =
        CloudItem(
            id = "id-$path",
            name = path.substringAfterLast("/"),
            path = path,
            size = 1,
            isFolder = false,
            modified = Instant.parse("2026-03-28T12:00:00Z"),
            created = Instant.parse("2026-03-28T10:00:00Z"),
            hash = "hash-$path",
            mimeType = "application/octet-stream",
        )

    private fun tree(): List<String> {
        val rootFiles = (1..6).map { "/f$it.txt" }
        source.children["/"] = rootFiles.map { file(it) } + file("/sub").copy(isFolder = true, size = 0, hash = null)
        source.children["/sub"] = listOf(file("/sub/s.txt"))
        val all = rootFiles + "/sub/s.txt"
        for (path in all) source.fileContents[path] = ByteArray(1)
        return all
    }

    // Holds the first transfers until [slots] of them are inside their download, then reports whether the walk listed
    // the next source folder meanwhile (it may not, with every slot busy), and lets them all go.
    private fun holdUntilSlotsFull(slots: Int): () -> Boolean {
        val full = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        var walkedAhead = false
        source.afterDownload = {
            if (started.incrementAndGet() == slots) {
                val deadline = System.nanoTime() + 500_000_000L
                while (source.listChildrenCount < 2 && System.nanoTime() < deadline) Thread.sleep(10)
                walkedAhead = source.listChildrenCount >= 2
                full.complete(Unit)
            }
            full.await()
        }
        return { walkedAhead }
    }

    @Test
    fun `while every transfer slot is busy the walk does not run ahead of the transfers`() =
        runTest {
            tree()
            val walkedAhead = holdUntilSlotsFull(slots = 2)

            CloudRelocator(source, target, maxConcurrentTransfers = 2).migrate("/", "/", knownTotalSize = 7L, knownTotalFiles = 7).toList()

            assertFalse(walkedAhead(), "the walk listed /sub while both slots were busy")
        }

    @Test
    fun `a walk bounded by its transfer slots still transfers every file`() =
        runTest {
            val all = tree()
            holdUntilSlotsFull(slots = 2)

            val events = CloudRelocator(source, target, maxConcurrentTransfers = 2).migrate("/", "/", knownTotalSize = 7L, knownTotalFiles = 7).toList()

            assertEquals(0, events.filterIsInstance<MigrateEvent.Completed>().single().errorCount)
            assertEquals(all.toSet(), target.uploadedPaths.toSet())
        }
}
