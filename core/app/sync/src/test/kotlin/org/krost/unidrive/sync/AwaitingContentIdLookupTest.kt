package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #841 (#742 finding 8): the rows of the planned downloads that have no row at their path yet (every one of a first
 * sync, a renamed file) are looked up by remote id in one batched lookup per pass, not one point query per download.
 */
class AwaitingContentIdLookupTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")

    @BeforeTest
    fun setUp() {
        syncRoot = TestScratch.directory("awaiting-ids-root")
        db = StateDatabase(TestScratch.directory("awaiting-ids-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engine() =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
        )

    private fun item(name: String) =
        CloudItem(
            id = "id-$name",
            name = name,
            path = "/$name",
            size = 1,
            isFolder = false,
            modified = t0,
            created = t0,
            hash = null,
            mimeType = "application/octet-stream",
        )

    @Test
    fun `downloads without a row at their path are looked up by id in one batch, not one query each`() =
        runTest {
            val n = 1_200
            provider.deltaItems = (1..n).map { item("f$it.bin") }
            for (i in 1..n) provider.files["/f$i.bin"] = byteArrayOf(1)
            val eng = engine()
            val lookups = mutableListOf<Int>()
            eng.awaitingContentIdLookup = { ids ->
                lookups += ids.size
                db.getEntriesByRemoteIds(ids)
            }
            eng.syncOnce(skipTransfers = true)

            assertEquals(listOf(n), lookups, "one batched by-id lookup of every planned download without a row")
        }
}
