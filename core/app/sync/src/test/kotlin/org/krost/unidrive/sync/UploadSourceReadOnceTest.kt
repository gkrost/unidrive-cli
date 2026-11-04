package org.krost.unidrive.sync

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.ProviderException
import org.krost.unidrive.engine.FileEvents
import org.krost.unidrive.sync.model.ConflictPolicy
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#393: a file is read from its source exactly once per upload. The provider reads it to send it (and, for
 * a hashless provider such as Internxt, hashes it in that same pass); the engine records the hash the provider reported and
 * does not read the file a second time. The fake provider counts its reads, and reports a marker hash no read of the file
 * could produce, so a second read by the engine shows as a row that carries the real hash instead of the marker.
 *
 * gkrost/unidrive-cli#394: the same runs write the per-file events, with the byte counts of the files.
 */
class UploadSourceReadOnceTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private val eventLogger = LoggerFactory.getLogger(FileEvents.LOGGER_NAME) as Logger
    private val summaryAppender = ListAppender<ILoggingEvent>().apply { start() }
    private var savedLevel: Level? = null

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("ud-393-root")
        db = StateDatabase(Files.createTempDirectory("ud-393-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
        savedLevel = eventLogger.level
        eventLogger.level = Level.DEBUG
        eventLogger.addAppender(summaryAppender)
    }

    @AfterTest
    fun tearDown() {
        eventLogger.detachAppender(summaryAppender)
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

    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    private fun events(): List<String> = summaryAppender.list.map { it.formattedMessage }

    private suspend fun uploadThree(eng: SyncEngine): Map<String, Int> {
        provider.deltaItems = emptyList()
        eng.syncOnce()
        val sizes = mapOf("one.bin" to 1000, "two.bin" to 2048, "three.bin" to 1)
        for ((name, size) in sizes) Files.write(syncRoot.resolve(name), ByteArray(size) { (it % 251).toByte() })
        eng.syncOnce()
        return sizes
    }

    @Test
    fun `a hashless provider that hashes while sending is read once per file and the engine reuses its hash`() =
        runTest {
            provider.reportSentSha256 = { "marker-${it.size}" }
            val sizes = uploadThree(engine())

            assertEquals(sizes.size, provider.uploadSourceReads.get(), "one read of the source per uploaded file")
            for ((name, size) in sizes) {
                assertEquals("marker-$size", db.getEntry("/$name")?.localHash, "the engine took the provider's hash of $name; it read nothing")
            }
        }

    @Test
    fun `a reported hash that is the real SHA-256 lands in the row as it is`() =
        runTest {
            provider.reportSentSha256 = { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
            uploadThree(engine())

            assertEquals(sha256(syncRoot.resolve("two.bin")), db.getEntry("/two.bin")?.localHash)
        }

    @Test
    fun `a provider that reports no hash still gets one, from a single read by the engine`() =
        runTest {
            val sizes = uploadThree(engine())

            assertEquals(sizes.size, provider.uploadSourceReads.get())
            assertEquals(sha256(syncRoot.resolve("one.bin")), db.getEntry("/one.bin")?.localHash)
        }

    @Test
    fun `a provider with a content hash is read once and the engine records no local hash`() =
        runTest {
            provider.hashAlgorithmOverride = HashAlgorithm.QuickXor
            provider.reportSentSha256 = { "marker" }
            val sizes = uploadThree(engine())

            assertEquals(sizes.size, provider.uploadSourceReads.get())
            assertNull(db.getEntry("/one.bin")?.localHash, "a provider hash compares the bytes; no SHA-256 column is needed")
        }

    @Test
    fun `a file that changed while it was sent gets no hash, whatever the provider reported`() =
        runTest {
            provider.reportSentSha256 = { "marker" }
            provider.duringUpload = { path -> Files.setLastModifiedTime(path, FileTime.fromMillis(Files.getLastModifiedTime(path).toMillis() + 60_000)) }
            val eng = engine()
            provider.deltaItems = emptyList()
            eng.syncOnce()
            Files.write(syncRoot.resolve("busy.bin"), ByteArray(100) { 7 })
            eng.syncOnce()

            val row = db.getEntry("/busy.bin")
            assertNotNull(row?.remoteId)
            assertNull(row.localHash, "the edit that landed mid-upload must stay visible to the next scan")
        }

    // ---- gkrost/unidrive-cli#394 ----

    @Test
    fun `every upload leaves its trail with the real byte counts and the pass ends with a summary`() =
        runTest {
            provider.reportSentSha256 = { "marker-${it.size}" }
            val sizes = uploadThree(engine())
            val lines = events()

            for ((name, size) in sizes) {
                val path = "\"/$name\""
                for (phase in listOf("upload_start", "upload_done", "upload_confirmed")) {
                    assertTrue(
                        lines.any { it.startsWith("file_event phase=$phase path=$path bytes=$size") },
                        "$phase of $name with bytes=$size in:\n${lines.joinToString("\n")}",
                    )
                }
                assertTrue(lines.any { it.startsWith("file_event phase=hash_done path=$path bytes=$size") && "via=provider" in it })
                assertTrue(lines.single { it.startsWith("file_event phase=upload_done path=$path") }.contains(" ms="))
            }
            val summary = lines.single { it.startsWith("file_summary ") && "uploads=3" in it }
            assertTrue("upload_bytes=${sizes.values.sum()}" in summary, summary)
        }

    @Test
    fun `a failed upload names the exception class but never its text, and the retry is counted`() =
        runTest {
            val eng = engine()
            provider.deltaItems = emptyList()
            eng.syncOnce()
            Files.write(syncRoot.resolve("flaky.bin"), ByteArray(64))
            provider.uploadThrowByPath["/flaky.bin"] = ProviderException("PUT https://store.invalid/up?tempauth=SECRETTOKEN timed out")
            eng.syncOnce()
            provider.uploadThrowByPath.clear()
            eng.syncOnce()

            val lines = events()
            assertFalse(lines.any { "SECRETTOKEN" in it || "tempauth" in it }, lines.joinToString("\n"))
            assertTrue(lines.any { it.startsWith("file_event phase=upload_failed path=\"/flaky.bin\"") && "error=ProviderException" in it && "retries=1" in it })
            assertTrue(lines.any { it.startsWith("file_event phase=upload_done path=\"/flaky.bin\"") && "retries=1" in it }, lines.joinToString("\n"))
        }

    @Test
    fun `a file the cloud reports as new is a remote_change event`() =
        runTest {
            provider.files["/down.bin"] = ByteArray(321)
            provider.deltaItems =
                listOf(
                    org.krost.unidrive.CloudItem(
                        id = "id-down",
                        name = "down.bin",
                        path = "/down.bin",
                        size = 321,
                        isFolder = false,
                        modified = java.time.Instant.parse("2026-03-28T12:00:00Z"),
                        created = null,
                        hash = null,
                        mimeType = null,
                    ),
                )
            engine().syncOnce()

            assertTrue(
                events().any { it == "file_event phase=remote_change path=\"/down.bin\" bytes=321 state=new" },
                events().joinToString("\n"),
            )
        }
}
