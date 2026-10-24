package org.krost.unidrive.sync.ignore

import org.krost.unidrive.engine.RuleFileTransfers
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** #660: rule files only the cloud holds are downloaded on purpose, once per cloud copy, capped and fail closed. */
class CloudRuleFilesTest {
    private val dir: Path = Files.createTempDirectory("ud-cloud-rules")
    private val cloud = mutableMapOf<String, Pair<CloudRuleFile, String>>()
    private val downloads = mutableListOf<String>()
    private var failNext = 0
    private var now = 1_000_000L
    private var paused = false
    private var pauseDuringNextDownload = false
    private val ran = mutableListOf<String>()
    private var bodyOf: (String) -> String = { it }

    // The daemon's pause and the transfer budget (RemoteOperationGuard.ruleFileTransfers), faked.
    private val transfers =
        object : RuleFileTransfers {
            override val isPaused: Boolean get() = paused

            override fun runner(path: String): suspend (block: suspend () -> Unit) -> Unit =
                { block ->
                    ran += path
                    block()
                }
        }

    private val files =
        CloudRuleFiles(
            dir = dir.resolve("rules"),
            lookup = { path -> cloud[path]?.first },
            download = { id, _, destination ->
                downloads += id
                if (pauseDuringNextDownload) {
                    pauseDuringNextDownload = false
                    paused = true
                    throw IOException("aborted: paused")
                }
                if (failNext > 0) {
                    failNext--
                    throw IOException("offline")
                }
                Files.writeString(destination, bodyOf(cloud.values.single { it.first.remoteId == id }.second))
            },
            maxBytes = 16,
            retryAfterMs = 60_000,
            clock = { now },
            transfers = { transfers },
        )

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun put(
        path: String,
        text: String,
        stamp: String = "v1",
        size: Long = text.length.toLong(),
        contentHash: String? = null,
    ) {
        cloud[path] = CloudRuleFile("id-$path", path, size, stamp, contentHash) to text
    }

    /** Kept rule copies: a rejected download must not leave a `.rules` file, a stamp, or a partial. */
    private fun keptCopies(): List<String> {
        val rules = dir.resolve("rules")
        if (!Files.isDirectory(rules)) return emptyList()
        return Files.list(rules).use { list ->
            list.map { it.fileName.toString() }.filter { name ->
                name.endsWith(".rules") || name.endsWith(".stamp") || name.endsWith(".part")
            }.toList()
        }
    }

    @Test
    fun aRuleFileIsDownloadedOnceAndAgainOnlyWhenTheCloudCopyChanges() {
        put("/a/.unidrive.ignore", "*.o\n")

        assertIs<RuleFileProbe.Present>(files.probe("/a/.unidrive.ignore"))
        assertIs<RuleFileProbe.Present>(files.probe("/a/.unidrive.ignore"))
        assertEquals("*.o\n", String(files.read("/a/.unidrive.ignore", 16)!!))
        assertEquals(1, downloads.size, "the second probe reads the copy already downloaded")

        put("/a/.unidrive.ignore", "*.tmp\n", stamp = "v2")
        assertIs<RuleFileProbe.Present>(files.probe("/a/.unidrive.ignore"))
        assertEquals("*.tmp\n", String(files.read("/a/.unidrive.ignore", 16)!!))
        assertEquals(2, downloads.size)
    }

    @Test
    fun aDownloadLargerThanTheCapIsNotKeptEvenWhenTheRecordedSizeIsSmall() {
        // A stale small remote size is not permission to keep a body over the cap.
        put("/big/.unidrive.ignore", "tiny")
        bodyOf = { "x".repeat(17) }

        val probe = assertIs<RuleFileProbe.Unreadable>(files.probe("/big/.unidrive.ignore"))
        assertTrue(probe.reason.startsWith("larger than 16 bytes"), probe.reason)
        assertEquals(1, downloads.size, "the body is fetched, then dropped")
        assertEquals(emptyList(), keptCopies())
    }

    @Test
    fun aDownloadWhoseLengthIsNotTheCloudSizeIsNotKept() {
        // A body that is not the cloud's known length is not that copy, so it is not stamped.
        put("/short/.unidrive.ignore", "12345678")
        bodyOf = { it.dropLast(3) }
        assertIs<RuleFileProbe.Unreadable>(files.probe("/short/.unidrive.ignore"))

        put("/under/.unidrive.ignore", "abcd")
        bodyOf = { it + "extra" }
        assertIs<RuleFileProbe.Unreadable>(files.probe("/under/.unidrive.ignore"))

        assertEquals(2, downloads.size)
        assertEquals(emptyList(), keptCopies())
    }

    @Test
    fun aRuleFileWithANegativeSizeIsNotDownloaded() {
        // The provider writes the whole object and cannot be stopped, so a size it does not know is not a bound.
        put("/neg/.unidrive.ignore", "x".repeat(100), size = -1L)
        val probe = assertIs<RuleFileProbe.Unreadable>(files.probe("/neg/.unidrive.ignore"))
        assertTrue(probe.reason.contains("not a positive size"), probe.reason)
        assertTrue(downloads.isEmpty())
        assertEquals(emptyList(), keptCopies())
    }

    @Test
    fun anEmptyRuleFileReportedWithSizeZeroIsDownloadedAndReleasesItsFolder() {
        // Zero is an empty file, not a missing size: it is fetched once, kept, and read as no rules.
        put("/empty/.unidrive.ignore", "", size = 0L)

        assertIs<RuleFileProbe.Present>(files.probe("/empty/.unidrive.ignore"))
        assertEquals("", String(files.read("/empty/.unidrive.ignore", 16)!!))
        assertIs<RuleFileProbe.Present>(files.probe("/empty/.unidrive.ignore"))
        assertEquals(1, downloads.size, "the second probe reads the copy already downloaded")
        assertTrue(keptCopies().any { it.endsWith(".rules") })
    }

    @Test
    fun aRuleFileReportedWithSizeZeroIsAcceptedWhateverComesBackUnderTheCap() {
        // A provider that reports 0 for a size it does not know costs one capped download: what fits the cap is kept.
        put("/lie/.unidrive.ignore", "*.o\n", size = 0L)

        assertIs<RuleFileProbe.Present>(files.probe("/lie/.unidrive.ignore"))
        assertEquals("*.o\n", String(files.read("/lie/.unidrive.ignore", 16)!!))

        put("/toolong/.unidrive.ignore", "x".repeat(17), size = 0L)
        val probe = assertIs<RuleFileProbe.Unreadable>(files.probe("/toolong/.unidrive.ignore"))
        assertTrue(probe.reason.startsWith("larger than 16 bytes"), probe.reason)
    }

    @Test
    fun aCloudHashIsNotCheckedWhenNoCheckerIsGiven() {
        // The hash algorithm is the provider's. Without a checker, a body of the cloud's length is still kept.
        put("/a/.unidrive.ignore", "abcd", contentHash = "not-a-real-hash")

        assertIs<RuleFileProbe.Present>(files.probe("/a/.unidrive.ignore"))
        assertEquals("abcd", String(files.read("/a/.unidrive.ignore", 16)!!))
    }

    @Test
    fun aDownloadThatDoesNotMatchTheCloudHashIsNotKept() {
        // When the provider's checker is wired, a body that is not the cloud's hash is not stamped.
        val checked =
            CloudRuleFiles(
                dir = dir.resolve("rules"),
                lookup = { path -> cloud[path]?.first },
                download = { id, _, destination ->
                    downloads += id
                    Files.writeString(destination, cloud.values.single { it.first.remoteId == id }.second)
                },
                maxBytes = 16,
                retryAfterMs = 60_000,
                clock = { now },
                contentMatches = { _, hash -> hash == "good" },
            )
        put("/a/.unidrive.ignore", "abcd", contentHash = "bad")

        assertIs<RuleFileProbe.Unreadable>(checked.probe("/a/.unidrive.ignore"))
        assertEquals(emptyList(), keptCopies())
    }

    @Test
    fun aRuleFileLargerThanTheCapIsNeverDownloaded() {
        put("/big/.unidrive.ignore", "x".repeat(17))

        val probe = assertIs<RuleFileProbe.Unreadable>(files.probe("/big/.unidrive.ignore"))
        assertTrue(probe.reason.startsWith("larger than 16 bytes in the cloud"), probe.reason)
        assertTrue(downloads.isEmpty())
    }

    @Test
    fun aFailedDownloadHoldsBackAndIsTriedAgainAfterTheDelay() {
        put("/a/.unidrive.ignore", "*.o\n")
        failNext = 1

        assertIs<RuleFileProbe.Unreadable>(files.probe("/a/.unidrive.ignore"))
        assertIs<RuleFileProbe.Unreadable>(files.probe("/a/.unidrive.ignore"), "not tried again at once")
        assertEquals(1, downloads.size)

        now += 60_000
        assertIs<RuleFileProbe.Present>(files.probe("/a/.unidrive.ignore"))
        assertEquals(2, downloads.size)
    }

    @Test
    fun aRuleFileTheCloudNoLongerKnowsIsUnreadable() {
        assertIs<RuleFileProbe.Unreadable>(files.probe("/gone/.unidrive.ignore"))
        assertTrue(downloads.isEmpty())
    }

    @Test
    fun aPausedDaemonDownloadsNoRuleFileAndTheFirstProbeAfterThePauseDoes() {
        put("/a/.unidrive.ignore", "*.o\n")
        paused = true

        val probe = assertIs<RuleFileProbe.Unreadable>(files.probe("/a/.unidrive.ignore"))
        assertEquals(CloudRuleFiles.PAUSED, probe.reason)
        assertTrue(downloads.isEmpty(), "nothing is downloaded while paused")

        paused = false
        assertIs<RuleFileProbe.Present>(files.probe("/a/.unidrive.ignore"))
        assertEquals(1, downloads.size)
        assertEquals(listOf("/a/.unidrive.ignore"), ran, "the download ran as a transfer")
    }

    @Test
    fun aDownloadAPauseAbortsIsNotAFailureAndRunsAgainRightAfterThePause() {
        put("/a/.unidrive.ignore", "*.o\n")
        pauseDuringNextDownload = true

        val probe = assertIs<RuleFileProbe.Unreadable>(files.probe("/a/.unidrive.ignore"))
        assertEquals(CloudRuleFiles.PAUSED, probe.reason)

        paused = false
        assertIs<RuleFileProbe.Present>(files.probe("/a/.unidrive.ignore"), "no minute to wait: it did not fail")
        assertEquals(2, downloads.size)
    }

    @Test
    fun aCopyAlreadyDownloadedIsReadWhilePaused() {
        put("/a/.unidrive.ignore", "*.o\n")
        assertIs<RuleFileProbe.Present>(files.probe("/a/.unidrive.ignore"))
        paused = true

        assertIs<RuleFileProbe.Present>(files.probe("/a/.unidrive.ignore"))
        assertEquals(1, downloads.size)
    }
}
