package org.krost.unidrive.tracking

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** A remote tombstone is resolved by the remote-id index, not by walking the whole tracking set per tombstone. */
class TrackingEngineTombstoneLookupTest {
    private lateinit var workDir: Path
    private lateinit var syncRoot: Path
    private lateinit var dbPath: Path
    private lateinit var provider: FakeTrackingProvider

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("tracking-tombstone")
        syncRoot = workDir.resolve("sync-root").also { Files.createDirectories(it) }
        dbPath = workDir.resolve("tracking.db")
        provider = FakeTrackingProvider().apply { incrementalAware = true }
    }

    @AfterTest
    fun tearDown() {
        workDir.toFile().deleteRecursively()
    }

    private class CountingTrackingSet(private val inner: TrackingSet) : TrackingSet by inner {
        var pathsCalls = 0

        override fun paths(): Set<String> {
            pathsCalls++
            return inner.paths()
        }
    }

    @Test
    fun `tombstones resolve by remote id and each one deletes only its file`() {
        for (i in 0 until 12) provider.files["/d/f$i.bin"] = "payload-$i".toByteArray()
        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            TrackingEngine(provider, tracking, syncRoot).syncOnce()
            for (i in 0 until 5) provider.incrementalDeletes += "/d/f$i.bin"
            val counting = CountingTrackingSet(tracking)
            val permissive = BatchGuard(maxDeleteRatio = 1.0, maxDeleteAbsolute = Int.MAX_VALUE)

            TrackingEngine(provider, counting, syncRoot, batchGuard = permissive).syncOnce()

            for (i in 0 until 5) assertFalse(Files.exists(syncRoot.resolve("d/f$i.bin")), "f$i.bin was tombstoned")
            for (i in 5 until 12) assertEquals("payload-$i", Files.readString(syncRoot.resolve("d/f$i.bin")))
            assertEquals(1, counting.pathsCalls, "the pass lists the tracked paths once, not once per tombstone")
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `pathsByRemoteId filters by provider and id`() {
        val tracking = SqliteTrackingSet(dbPath).also { it.initialize() }
        try {
            fun row(path: String, provider: String, id: String?) =
                TrackingRecord(path, provider, id, TrackState.TrackedSynced, "h", 1, "e", 1, java.time.Instant.now())
            tracking.upsert(row("/a", "p", "id-1"))
            tracking.upsert(row("/b", "p", "id-2"))
            tracking.upsert(row("/c", "other", "id-1"))
            tracking.upsert(row("/d", "p", null))
            assertEquals(listOf("/a"), tracking.pathsByRemoteId("p", "id-1"))
            assertEquals(emptyList(), tracking.pathsByRemoteId("p", "id-9"))
        } finally {
            tracking.close()
        }
    }
}
