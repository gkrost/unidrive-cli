package org.krost.unidrive.tracking

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import org.krost.unidrive.testing.TestScratch

/** Shared fixture for engine tests that run against a [HashlessFakeProvider] with a real SQLite tracking set. */
abstract class HashlessEngineTestBase {
    protected lateinit var workDir: Path
    protected lateinit var syncRoot: Path
    protected lateinit var dbPath: Path
    protected lateinit var provider: HashlessFakeProvider

    protected val permissive = BatchGuard(maxDeleteRatio = 1.0, maxDeleteAbsolute = Int.MAX_VALUE)

    @BeforeTest
    fun setUpHashlessFixture() {
        workDir = TestScratch.directory("tracking-hashless")
        syncRoot = workDir.resolve("sync-root").also { Files.createDirectories(it) }
        dbPath = workDir.resolve("tracking.db")
        provider = HashlessFakeProvider()
    }

    @AfterTest
    fun tearDownHashlessFixture() {
        workDir.toFile().deleteRecursively()
    }

    protected fun open() = SqliteTrackingSet(dbPath).also { it.initialize() }

    protected fun engine(
        tracking: TrackingSet,
        guard: BatchGuard = permissive,
        conflictLog: org.krost.unidrive.sync.ConflictLog? = null,
    ) = TrackingEngine(provider, tracking, syncRoot, batchGuard = guard, conflictLog = conflictLog)
}
