package org.krost.unidrive.hydration

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.engine.FileEvents
import org.krost.unidrive.sync.FakeCloudProvider
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#630: a rename applied through the mount names the old and the new path in the daemon log (one INFO line)
 * and in the file-event trail (`file_event phase=rename`), so that a rename mix-up can be reconstructed from the log alone.
 */
class HydrationRenameTrailTest {
    private val infoLogger = LoggerFactory.getLogger(HydrationImpl::class.java) as Logger
    private val eventLogger = LoggerFactory.getLogger(FileEvents.LOGGER_NAME) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private var savedEventLevel: Level? = null

    private val provider = FakeCloudProvider()
    private val db = StateDatabase(TestScratch.directory("unidrive-630-db").resolve("state.db"), inMemory = true)
    private lateinit var engine: SyncEngine
    private lateinit var hydration: HydrationImpl

    @BeforeTest
    fun setUp() {
        savedEventLevel = eventLogger.level
        eventLogger.level = Level.DEBUG
        appender.start()
        infoLogger.addAppender(appender)
        eventLogger.addAppender(appender)
        db.initialize()
        engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = TestScratch.directory("unidrive-630-sync"),
                cacheRoot = TestScratch.directory("unidrive-630-cache"),
            )
        hydration = HydrationImpl(syncEngine = engine, stateDb = db)
    }

    @AfterTest
    fun tearDown() {
        infoLogger.detachAppender(appender)
        eventLogger.detachAppender(appender)
        appender.stop()
        eventLogger.level = savedEventLevel
        db.close()
    }

    private fun row(
        path: String,
        folder: Boolean = false,
    ) = SyncEntry(
        path = path, remoteId = "id-$path", remoteHash = null, remoteSize = if (folder) 0L else 5L,
        remoteModified = Instant.parse("2026-11-11T09:00:00Z"), localMtime = null, localSize = null, isFolder = folder,
        isPinned = false, isHydrated = false, lastSynced = Instant.now(),
    )

    private fun renameLines() = appender.list.map { it.formattedMessage }.filter { it.startsWith("rename applied through the mount") }

    private fun renameEvents() = appender.list.map { it.formattedMessage }.filter { it.startsWith("file_event phase=rename ") }

    @Test
    fun `a file renamed through the mount names the old and the new path once`() = runTest {
        provider.files["/x/Dates.7z"] = "bytes".toByteArray()
        db.upsertEntry(row("/x", folder = true))
        db.upsertEntry(row("/x/Dates.7z"))

        assertEquals(RenameResult.Ok, hydration.rename("/x/Dates.7z", "/x/_Dates.7z"))

        assertEquals(listOf("rename applied through the mount: file /x/Dates.7z -> /x/_Dates.7z"), renameLines())
        assertEquals(
            listOf("""file_event phase=rename path="/x/_Dates.7z" from=/x/Dates.7z kind=file via=mount"""),
            renameEvents(),
        )
    }

    @Test
    fun `a folder rename is one line, not one per row below it`() = runTest {
        provider.files["/d/a.txt"] = "a".toByteArray()
        provider.files["/d/b.txt"] = "b".toByteArray()
        db.upsertEntry(row("/d", folder = true))
        db.upsertEntry(row("/d/a.txt"))
        db.upsertEntry(row("/d/b.txt"))

        assertEquals(RenameResult.Ok, hydration.rename("/d", "/e"))

        assertEquals(listOf("rename applied through the mount: folder /d -> /e"), renameLines())
        assertEquals(1, renameEvents().size)
        assertTrue(renameEvents().single().contains("kind=folder"))
    }

    @Test
    fun `a rename whose cache copy cannot follow yet is reported as applied, with the copy pending`() = runTest {
        provider.files["/a.txt"] = "bytes".toByteArray()
        db.upsertEntry(row("/a.txt"))
        val copy = engine.resolveCachePath("/a.txt")
        Files.createDirectories(copy.parent)
        Files.writeString(copy, "bytes")
        // A directory with a file in it holds the new name in the cache: the copy's move fails (as a sharing violation would).
        val blocker = engine.resolveCachePath("/b.txt")
        Files.createDirectories(blocker)
        Files.writeString(blocker.resolve("held"), "x")

        hydration.rename("/a.txt", "/b.txt")

        assertEquals(listOf("rename applied through the mount: file /a.txt -> /b.txt (its cache copy follows later)"), renameLines())
        assertTrue(renameEvents().single().contains("stage=cache_pending"), renameEvents().toString())
    }

    @Test
    fun `a refused rename writes no rename line`() = runTest {
        db.upsertEntry(row("/a.txt"))
        db.upsertEntry(row("/b.txt"))

        assertEquals(RenameResult.NewPathExists, hydration.rename("/a.txt", "/b.txt"))

        assertTrue(renameLines().isEmpty())
        assertTrue(renameEvents().isEmpty())
    }
}
