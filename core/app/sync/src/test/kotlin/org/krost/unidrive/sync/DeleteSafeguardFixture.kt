package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/** gkrost/unidrive-cli#267: the two plans that trip the deletion safeguards, shared by the text pin and the typed-outcome tests. */
abstract class DeleteSafeguardFixture {
    protected lateinit var syncRoot: Path
    protected lateinit var db: StateDatabase
    protected lateinit var dbPath: Path
    protected val provider = FakeCloudProvider()

    @BeforeTest
    fun setUp() {
        syncRoot = TestScratch.directory("unidrive-safeguard-text")
        dbPath = TestScratch.directory("unidrive-safeguard-text-db").resolve("state.db")
        db = StateDatabase(dbPath)
        db.initialize()
    }

    @AfterTest
    fun tearDown() = db.close()

    protected fun engine(
        reporter: ProgressReporter = ProgressReporter.Silent,
        db: StateDatabase = this.db,
        maxDeletePercentage: Int = 0,
        maxDeleteAbsolute: Int = 0,
        maxDeletePerSubtreePercent: Int = 0,
    ) = SyncEngine(
        provider = provider,
        db = db,
        syncRoot = syncRoot,
        conflictPolicy = ConflictPolicy.KEEP_BOTH,
        reporter = reporter,
        maxDeletePercentage = maxDeletePercentage,
        maxDeleteAbsolute = maxDeleteAbsolute,
        maxDeletePerSubtreePercent = maxDeletePerSubtreePercent,
    )

    protected fun seed(
        paths: List<String>,
        sizeOf: (Int) -> Long = { 100L },
    ) {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        paths.forEachIndexed { i, path ->
            db.upsertEntry(
                SyncEntry(
                    path = path, remoteId = "id-$i", remoteHash = "h-$i", remoteSize = sizeOf(i), remoteModified = now,
                    localMtime = now.toEpochMilli(), localSize = sizeOf(i), isFolder = false, isPinned = false,
                    isHydrated = true, lastSynced = now,
                ),
            )
        }
        // A cursor keeps the pass incremental, so the vanished local files plan DeleteRemote actions.
        db.setSyncState("delta_cursor", "seeded-cursor")
    }

    /** 100 tracked files, one left on disk: 99 deletes planned. */
    protected fun ninetyNineOfOneHundred() {
        seed((0 until 100).map { "/seeded-$it.txt" })
        Files.writeString(syncRoot.resolve("seeded-0.txt"), "x")
    }

    /** 20 tracked under /Documents (one left on disk) and 9 under /Other (all on disk): 19 deletes, all in /Documents. */
    protected fun nineteenOfTwentyUnderDocuments() {
        seed((0 until 20).map { "/Documents/file-$it.txt" } + (0 until 9).map { "/Other/file-$it.txt" }) { i ->
            if (i < 20) 1000L + i else 5000L + (i - 20)
        }
        Files.createDirectories(syncRoot.resolve("Documents"))
        Files.writeString(syncRoot.resolve("Documents/file-0.txt"), "x".repeat(1000))
        Files.createDirectories(syncRoot.resolve("Other"))
        for (i in 0 until 9) Files.writeString(syncRoot.resolve("Other/file-$i.txt"), "x".repeat(5000 + i))
    }
}
