package org.krost.unidrive.sync

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.engine.FileEvents
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #742 finding 6: the remote_change log lines of a pass take their rows from one batched lookup (chunked by
 * [Reconciler.ENTRY_LOOKUP_CHUNK] inside), not from one single-row query per reported file.
 */
class RemoteChangeLogLookupTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private val eventLogger = LoggerFactory.getLogger(FileEvents.LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>().apply { start() }
    private var savedLevel: Level? = null
    private val t0 = Instant.parse("2026-01-01T00:00:00Z")

    @BeforeTest
    fun setUp() {
        syncRoot = TestScratch.directory("f6-root")
        db = StateDatabase(TestScratch.directory("f6-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
        savedLevel = eventLogger.level
        eventLogger.level = Level.DEBUG
        eventLogger.addAppender(appender)
    }

    @AfterTest
    fun tearDown() {
        eventLogger.detachAppender(appender)
        eventLogger.level = savedLevel
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

    private fun item(
        name: String,
        size: Long,
        deleted: Boolean = false,
    ) = CloudItem(
        id = "id-$name",
        name = name,
        path = "/$name",
        size = size,
        isFolder = false,
        modified = t0,
        created = t0,
        hash = null,
        mimeType = "application/octet-stream",
        deleted = deleted,
    )

    private fun row(
        name: String,
        size: Long,
    ) = SyncEntry(
        path = "/$name",
        remoteId = "id-$name",
        remoteHash = null,
        remoteSize = size,
        remoteModified = t0,
        localMtime = null,
        localSize = null,
        isFolder = false,
        isPinned = false,
        isHydrated = false,
        lastSynced = t0,
    )

    private fun remoteChangeLines(): List<String> =
        appender.list.map { it.formattedMessage }.filter { it.startsWith("file_event phase=remote_change") }

    @Test
    fun `remote change logging looks rows up in one batch, not one query per reported file`() =
        runTest {
            val n = 1_200
            provider.deltaItems = (1..n).map { item("f$it.bin", 1) }
            val eng = engine()
            val lookups = mutableListOf<Int>()
            eng.remoteChangeRowLookup = { paths ->
                lookups += paths.size
                Reconciler.entriesByPathsChunked(db, paths)
            }
            eng.syncOnce(skipTransfers = true)

            assertEquals(listOf(n), lookups, "one batched lookup of all reported files")
        }

    @Test
    fun `remote change logging still tells new, changed and deleted files apart`() =
        runTest {
            db.upsertEntry(row("changed.bin", 1))
            db.upsertEntry(row("same.bin", 1))
            db.upsertEntry(row("gone.bin", 1))
            provider.deltaItems =
                listOf(item("new.bin", 3), item("changed.bin", 2), item("same.bin", 1), item("gone.bin", 1, deleted = true))
            engine().syncOnce(skipTransfers = true)

            val lines = remoteChangeLines()
            val all = lines.joinToString("\n")
            assertTrue(lines.any { it.contains("\"/new.bin\"") && it.endsWith("state=new") }, all)
            assertTrue(lines.any { it.contains("\"/changed.bin\"") && it.endsWith("state=changed") }, all)
            assertTrue(lines.any { it.contains("\"/gone.bin\"") && it.endsWith("state=deleted") }, all)
            assertTrue(lines.none { it.contains("\"/same.bin\"") }, all)
        }
}
