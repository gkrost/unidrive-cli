package org.krost.unidrive.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.sync.model.SyncEntry
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#394: the line format of the per-file events, their levels, the bound on the remote-change lines and the
 * per-pass summary; and gkrost/unidrive-cli#393: the recorded hash of sent bytes is taken from the provider's pass when it
 * has one, so the file is not read again.
 */
class FileEventsTest {
    private val logger = LoggerFactory.getLogger(FileEvents.LOGGER_NAME) as Logger
    private lateinit var appender: ListAppender<ILoggingEvent>
    private var savedLevel: Level? = null

    @BeforeTest
    fun attach() {
        savedLevel = logger.level
        appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
    }

    @AfterTest
    fun detach() {
        logger.detachAppender(appender)
        logger.level = savedLevel
    }

    private fun lines(level: Level): List<String> = appender.list.filter { it.level == level }.map { it.formattedMessage }

    @Test
    fun `an upload event is one key=value line with the path quoted`() {
        logger.level = Level.DEBUG

        FileEvents.event(FileEvents.Phase.UPLOAD_DONE, "/Pix/a b.jpg", bytes = 1_048_576, ms = 812, retries = 0)

        assertEquals(
            listOf("""file_event phase=upload_done path="/Pix/a b.jpg" bytes=1048576 ms=812 retries=0"""),
            lines(Level.DEBUG),
        )
    }

    @Test
    fun `quotes, backslashes and line breaks in a name cannot break the line apart`() {
        logger.level = Level.DEBUG

        FileEvents.event(FileEvents.Phase.PICKUP, "/x\"y\\z\nw")

        assertEquals(listOf("""file_event phase=pickup path="/x\"y\\z\nw""""), lines(Level.DEBUG))
    }

    @Test
    fun `a name that looks like a URL loses its query and an extra value is redacted too`() {
        logger.level = Level.DEBUG

        FileEvents.event(
            FileEvents.Phase.UPLOAD_FAILED,
            "/dump/https://host.invalid/f?sig=TOPSECRET&x=1",
            extra = listOf("error" to "IOException", "remote" to "https://up.invalid/put?tempauth=ALSOSECRET"),
        )

        val line = lines(Level.DEBUG).single()
        assertFalse("TOPSECRET" in line, line)
        assertFalse("ALSOSECRET" in line, line)
        assertTrue("error=IOException" in line, line)
    }

    @Test
    fun `an extra value with blanks is quoted and a null one is left out`() {
        logger.level = Level.DEBUG

        FileEvents.event(FileEvents.Phase.REMOTE_CHANGE, "/a", extra = listOf("note" to "two words", "gone" to null, "state" to "new"))

        assertEquals(listOf("""file_event phase=remote_change path="/a" note="two words" state=new"""), lines(Level.DEBUG))
    }

    @Test
    fun `at INFO the events write nothing but the tally still counts them`() {
        logger.level = Level.INFO
        val tally = FileEventTally()

        FileEvents.event(FileEvents.Phase.UPLOAD_DONE, "/a", bytes = 10, ms = 5, tally = tally)

        assertTrue(appender.list.isEmpty())
        assertTrue(tally.summaryOrNull("s1")!!.contains("uploads=1 upload_bytes=10 upload_ms=5"))
    }

    @Test
    fun `byte-level events are TRACE only`() {
        logger.level = Level.DEBUG
        FileEvents.trace(FileEvents.Phase.UPLOAD_PROGRESS, "/a", 4_194_304, listOf("total" to 9_000_000L))
        assertTrue(appender.list.isEmpty())

        logger.level = Level.TRACE
        FileEvents.trace(FileEvents.Phase.UPLOAD_PROGRESS, "/a", 4_194_304, listOf("total" to 9_000_000L))
        assertEquals(
            listOf("""file_trace phase=upload_progress path="/a" bytes=4194304 total=9000000"""),
            lines(Level.TRACE),
        )
    }

    @Test
    fun `the summary adds up the pass and a quiet pass writes none`() {
        logger.level = Level.INFO
        val tally = FileEventTally()
        assertNull(tally.summaryOrNull("s0"))

        FileEvents.event(FileEvents.Phase.UPLOAD_DONE, "/a", bytes = 100, ms = 40, retries = 1, tally = tally)
        FileEvents.event(FileEvents.Phase.UPLOAD_DONE, "/b", bytes = 50, ms = 10, retries = 0, tally = tally)
        FileEvents.event(FileEvents.Phase.UPLOAD_FAILED, "/c", bytes = 7, ms = 1, retries = 2, tally = tally)
        FileEvents.event(FileEvents.Phase.REMOTE_CHANGE, "/d", bytes = 3, tally = tally)
        FileEvents.event(FileEvents.Phase.HASH_DONE, "/a", bytes = 100, ms = 4, tally = tally)

        FileEvents.summary("scan1", tally)

        assertEquals(
            listOf(
                "file_summary scan=scan1 remote_changes=1 uploads=2 upload_bytes=150 upload_ms=50 upload_failed=1 retries=1 hash_bytes=100 hash_ms=4",
            ),
            lines(Level.INFO),
        )

        tally.reset()
        appender.list.clear()
        FileEvents.summary("scan2", tally)
        assertTrue(appender.list.isEmpty())
    }

    @Test
    fun `the remote-change lines of a pass are bounded and the rest is counted`() {
        val tally = FileEventTally()
        var allowed = 0
        repeat((FileEventTally.REMOTE_CHANGE_LINES_PER_PASS + 5).toInt()) { if (tally.mayLogRemoteChange()) allowed++ }

        assertEquals(FileEventTally.REMOTE_CHANGE_LINES_PER_PASS.toInt(), allowed)
        assertEquals(5L, tally.remoteChangeLinesSuppressed())
        tally.reset()
        assertTrue(tally.mayLogRemoteChange())
    }

    @Test
    fun `failed attempts of a path count up and a success forgets them`() {
        val tally = FileEventTally()
        assertEquals(0, tally.attemptsFailed("/a"))
        tally.noteFailure("/a")
        tally.noteFailure("/a")
        assertEquals(2, tally.attemptsFailed("/a"))
        tally.noteSuccess("/a")
        assertEquals(0, tally.attemptsFailed("/a"))
    }

    // ---- gkrost/unidrive-cli#393: the hash of the sent bytes comes from the provider's pass ----

    private fun rowFor(
        file: java.nio.file.Path,
        remoteHash: String? = null,
    ) = SyncEntry(
        path = "/f.bin",
        remoteId = "id",
        remoteHash = remoteHash,
        remoteSize = Files.size(file),
        remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
        localMtime = Files.getLastModifiedTime(file).toMillis(),
        localSize = Files.size(file),
        isFolder = false,
        isPinned = false,
        isHydrated = true,
        lastSynced = Instant.now(),
    )

    private val noLog = LoggerFactory.getLogger("test")

    @Test
    fun `a hash the provider reported is recorded without reading the file`() {
        val file = Files.createTempFile("ud-393-", ".bin").also { Files.write(it, "payload".toByteArray()) }
        val sent = Files.getLastModifiedTime(file).toMillis() to Files.size(file)
        var told: Pair<Boolean, Long>? = null

        // A marker no read of the file can produce: the row carries it only if the file was not hashed again.
        val out = Transfers.withSentHash(rowFor(file), file, sent, null, noLog, sentSha256 = "marker") { fromProvider, ms -> told = fromProvider to ms }

        assertEquals("marker", out.localHash)
        assertEquals(true to 0L, told)
    }

    @Test
    fun `without a reported hash the file is read once for it`() {
        val file = Files.createTempFile("ud-393-", ".bin").also { Files.write(it, "payload".toByteArray()) }
        val sent = Files.getLastModifiedTime(file).toMillis() to Files.size(file)
        var fromProvider: Boolean? = null

        val out = Transfers.withSentHash(rowFor(file), file, sent, null, noLog) { p, _ -> fromProvider = p }

        assertEquals(org.krost.unidrive.sync.HashVerifier.computeSha256Hex(file), out.localHash)
        assertEquals(false, fromProvider)
    }

    @Test
    fun `a reported hash is dropped when the file changed after it was sent`() {
        val file = Files.createTempFile("ud-393-", ".bin").also { Files.write(it, "payload".toByteArray()) }
        val sent = Files.getLastModifiedTime(file).toMillis() to Files.size(file)
        Files.setLastModifiedTime(file, FileTime.fromMillis(sent.first + 60_000))

        val out = Transfers.withSentHash(rowFor(file), file, sent, null, noLog, sentSha256 = "marker")

        assertNull(out.localHash, "an edit that landed during the upload must not be baselined as the sent bytes")
    }

    @Test
    fun `a provider with a content hash needs no local hash at all`() {
        val file = Files.createTempFile("ud-393-", ".bin").also { Files.write(it, "payload".toByteArray()) }
        val sent = Files.getLastModifiedTime(file).toMillis() to Files.size(file)
        var called = false

        val out = Transfers.withSentHash(rowFor(file, remoteHash = "abc"), file, sent, HashAlgorithm.QuickXor, noLog, sentSha256 = "marker") { _, _ -> called = true }

        assertNull(out.localHash)
        assertFalse(called)
    }
}
