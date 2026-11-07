package org.krost.unidrive.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.krost.unidrive.CloudItem
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// gkrost/unidrive-cli#419: the engine calls a provider from several threads at once (CloudRelocator downloads on
// Dispatchers.IO, up to four at a time; RelocateCommand runs the whole migrate there). The fake's call histories were
// plain ArrayLists and HashMaps, and two threads adding to an ArrayList at once lose an entry or throw
// "ArrayIndexOutOfBoundsException: Index 1 out of bounds for length 0" from its grow step: the failure #419 saw,
// wrapped by the relocator's per-file catch into "Failed to migrate ...", which then made the delete gate refuse.
class FakeCloudProviderConcurrencyTest {
    private val threads = 8
    private val callsPerThread = 2_000

    @Test
    fun `concurrent transfers on a shared fake lose no call and throw nothing`() {
        val provider = FakeCloudProvider()
        val dir = Files.createTempDirectory("ud-fake-concurrency")
        try {
            val local = dir.resolve("src.bin").also { Files.write(it, byteArrayOf(1)) }
            repeat(threads) { provider.files["/t$it.bin"] = byteArrayOf(2) }
            runBlocking {
                (0 until threads)
                    .map { t ->
                        async(Dispatchers.IO) {
                            repeat(callsPerThread) { i ->
                                provider.download("/t$t.bin", dir.resolve("d-$t.bin"))
                                provider.upload(local, "/u-$t-$i.bin", null, null, null)
                                provider.deleteById("id-$t-$i", "/u-$t-$i.bin")
                                provider.listChildren("/t$t")
                            }
                        }
                    }.awaitAll()
            }
            val total = threads * callsPerThread
            assertEquals(total, provider.downloadByPathCalls.size, "every download is recorded")
            assertEquals(total, provider.uploadAttempts.size, "every upload attempt is recorded")
            assertEquals(total, provider.uploadedPaths.size, "every upload is recorded")
            assertEquals(total, provider.uploadIfMatches.size, "every upload precondition is recorded")
            assertEquals(total, provider.deleteByIdCalls.size, "every id delete is recorded")
            assertEquals(total, provider.deletedPaths.size, "every delete is recorded")
            assertEquals(total, provider.listChildrenCalls.size, "every listing is recorded")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a wide migrate on the IO dispatcher, as RelocateCommand runs it, transfers every file without an error`() {
        val source = FakeCloudProvider()
        val target = FakeCloudProvider()
        val folders = 20
        val perFolder = 25
        source.childrenByParent["/docs"] = (0 until folders).map { folder("/docs/f$it") }
        for (f in 0 until folders) {
            source.childrenByParent["/docs/f$f"] =
                (0 until perFolder).map { i ->
                    val path = "/docs/f$f/n-$f-$i.txt"
                    source.files[path] = "x$i".toByteArray()
                    file(path, size = "x$i".length.toLong())
                }
        }
        val events =
            runBlocking {
                withContext(Dispatchers.IO) {
                    CloudRelocator(source, target).migrate("/docs", "/docs").toList()
                }
            }
        val errors = events.filterIsInstance<MigrateEvent.Error>()
        assertTrue(errors.isEmpty(), "no file fails to migrate; errors=${errors.take(3)}")
        val completed = events.filterIsInstance<MigrateEvent.Completed>().single()
        assertEquals(folders * perFolder, completed.doneFiles)
        assertEquals(folders * perFolder, source.downloadByPathCalls.size, "every download is recorded")
        assertEquals(folders * perFolder, target.uploadedPaths.size, "every upload is recorded")
    }

    private fun folder(path: String) = item(path, size = 0, isFolder = true)

    private fun file(
        path: String,
        size: Long,
    ) = item(path, size = size, isFolder = false)

    private fun item(
        path: String,
        size: Long,
        isFolder: Boolean,
    ) = CloudItem(
        id = "src-$path",
        name = path.substringAfterLast("/"),
        path = path,
        size = size,
        isFolder = isFolder,
        modified = Instant.parse("2026-03-28T12:00:00Z"),
        created = Instant.parse("2026-03-28T10:00:00Z"),
        hash = null,
        mimeType = null,
    )
}
