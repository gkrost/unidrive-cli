package org.krost.unidrive.cli

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.krost.unidrive.io.LinkedEntries
import org.krost.unidrive.localfs.LocalFsProvider
import org.krost.unidrive.sync.LocalScanner
import org.krost.unidrive.sync.ProgressReporter
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Local entries the mirror cannot carry (gkrost/unidrive-cli#582, #583), put in a `localfs` profile and synced twice
 * with the real mirror engine. The owner's rule for both: the entry is skipped with a warning, never uploaded or
 * mirrored, left untouched on disk, and the second sync is clean (no failed action, no `del-local`), so `sync` exits 0.
 *
 * - #582: a name that holds a lone UTF-16 surrogate has no UTF-8 form. It used to go up in the first sync and then be
 *   planned as gone locally (`del-local`, `adopt`), which failed with InvalidPathException on every sync.
 * - #583: a directory junction or symlinked directory in the sync root was mirrored as an empty folder and then
 *   attempted as a file. (A symlink to a *file* is uploaded as a file; that is unchanged and pinned below.)
 */
class LocalfsMirrorSkipsTest {
    private lateinit var base: Path
    private lateinit var local: Path
    private lateinit var remote: Path
    private lateinit var outside: Path
    private lateinit var db: StateDatabase
    private lateinit var engine: SyncEngine
    private val reporter = RunReporter()

    // The scanner's and the engine's own warnings go to their loggers, not to the reporter.
    private val scannerLog = ListAppender<ILoggingEvent>()
    private val scannerLogger = LoggerFactory.getLogger(LocalScanner::class.java) as Logger
    private val engineLogger = LoggerFactory.getLogger(SyncEngine::class.java) as Logger

    @BeforeTest
    fun setUp() {
        scannerLog.start()
        scannerLogger.addAppender(scannerLog)
        engineLogger.addAppender(scannerLog)
        base = Files.createTempDirectory("ud-mirror-skips")
        local = Files.createDirectories(base.resolve("local"))
        remote = Files.createDirectories(base.resolve("remote"))
        outside = Files.createDirectories(base.resolve("outside"))
        Files.writeString(outside.resolve("must-survive.txt"), "x")
        Files.createDirectories(outside.resolve("sub"))
        Files.writeString(outside.resolve("sub").resolve("deep.txt"), "y")
        db = StateDatabase(Files.createDirectories(base.resolve("profile")).resolve("state.db")).also { it.initialize() }
        val provider = LocalFsProvider(remote)
        engine = SyncEngine(provider = provider, db = db, syncRoot = local, reporter = reporter, cacheRoot = base.resolve("cache"))
        runBlocking { provider.authenticate() }
    }

    @AfterTest
    fun tearDown() {
        scannerLogger.detachAppender(scannerLog)
        engineLogger.detachAppender(scannerLog)
        scannerLog.stop()
        runCatching { db.close() }
        runCatching { LinkedEntries.deleteTree(base) }
    }

    // ── #582 ─────────────────────────────────────────────────────────────────

    @Test
    fun `a local name with a lone surrogate is skipped with one warning, never uploaded, and the second sync is clean`() {
        val name = "lone-\uD800-surrogate.txt"
        val file = seedLoneSurrogate(local, name, "mine")
        Files.writeString(local.resolve("ordinary.txt"), "an ordinary sibling")

        val first = sync()
        val second = sync()
        val third = sync()

        assertEquals("an ordinary sibling", Files.readString(remote.resolve("ordinary.txt")), "the sibling goes up")
        assertEquals("mine", Files.readString(file), "the local file is untouched")
        assertEquals(listOf("ordinary.txt"), remoteNames(), "nothing under the surrogate name reaches the cloud")
        for ((i, run) in listOf(first, second, third).withIndex()) {
            assertEquals(0, run.failed, "sync ${i + 1} has no failed action: ${run.warnings}")
            assertFalse(run.actions.containsKey("DeleteLocal"), "sync ${i + 1} plans no del-local: ${run.actions}")
            assertFalse(run.actions.containsKey("DeleteRemote"), "sync ${i + 1} plans no del-remote: ${run.actions}")
        }
        assertEquals(
            1,
            first.warnings.count { it.contains("surrogate") },
            "the first sync warns once: ${first.warnings}",
        )
    }

    @Test
    fun `a cloud name with a lone surrogate is skipped the same way and its cloud copy is left alone`() {
        val name = "cloud-\uDC00-lone.txt"
        seedLoneSurrogate(remote, name, "theirs")
        Files.writeString(remote.resolve("ordinary.txt"), "an ordinary sibling")

        val first = sync()
        val second = sync()

        assertEquals("an ordinary sibling", Files.readString(local.resolve("ordinary.txt")), "the sibling comes down")
        assertEquals(listOf("ordinary.txt"), localNames(), "nothing under the surrogate name is written locally")
        assertEquals("theirs", Files.readString(remote.resolve(name)), "the cloud copy is left alone")
        assertEquals(null, db.getEntry("/$name"), "no row is kept under the name")
        assertEquals(1, first.warnings.count { it.contains("surrogate") }, "the first sync warns once: ${first.warnings}")
        for ((i, run) in listOf(first, second).withIndex()) {
            assertEquals(0, run.failed, "sync ${i + 1} has no failed action: ${run.warnings}")
            assertFalse(run.actions.containsKey("DeleteLocal"), "sync ${i + 1} plans no del-local: ${run.actions}")
            assertFalse(run.actions.containsKey("DeleteRemote"), "sync ${i + 1} plans no del-remote: ${run.actions}")
        }
    }

    // ── #583 ─────────────────────────────────────────────────────────────────

    @Test
    fun `a junction in the sync root is skipped, never mirrored, and the second sync is clean`() {
        val link = junction(local.resolve("deps"), outside)
        Files.writeString(local.resolve("ordinary.txt"), "an ordinary sibling")

        assertSkippedLink(link)
    }

    @Test
    fun `a symlinked directory in the sync root is skipped, never mirrored, and the second sync is clean`() {
        val link = local.resolve("deps")
        assumeTrue(
            "this host cannot create a directory symlink",
            runCatching { Files.createSymbolicLink(link, outside) }.isSuccess,
        )
        Files.writeString(local.resolve("ordinary.txt"), "an ordinary sibling")

        assertSkippedLink(link)
    }

    @Test
    fun `a symlink to a file is still uploaded as a file`() {
        val target = outside.resolve("must-survive.txt")
        assumeTrue(
            "this host cannot create a file symlink",
            runCatching { Files.createSymbolicLink(local.resolve("linked.txt"), target) }.isSuccess,
        )

        val first = sync()
        val second = sync()

        assertEquals("x", Files.readString(remote.resolve("linked.txt")), "a file symlink goes up as the file it points at")
        assertEquals(0, first.failed + second.failed, "${first.warnings} ${second.warnings}")
    }

    private fun assertSkippedLink(link: Path) {
        val first = sync()
        val second = sync()
        val third = sync()

        assertEquals("an ordinary sibling", Files.readString(remote.resolve("ordinary.txt")), "the sibling goes up")
        assertEquals(listOf("ordinary.txt"), remoteNames(), "nothing of the link is mirrored, not even as an empty folder")
        assertTrue(Files.exists(link, LinkOption.NOFOLLOW_LINKS), "the link itself is untouched")
        assertEquals("x", Files.readString(outside.resolve("must-survive.txt")), "what the link leads to is untouched")
        assertTrue(Files.exists(outside.resolve("sub").resolve("deep.txt")), "and everything below it")
        assertEquals(null, db.getEntry("/deps"), "no row for the link")
        for ((i, run) in listOf(first, second, third).withIndex()) {
            assertEquals(0, run.failed, "sync ${i + 1} has no failed action: ${run.warnings}")
            assertFalse(run.actions.containsKey("DeleteLocal"), "sync ${i + 1} plans no del-local: ${run.actions}")
            assertFalse(run.actions.containsKey("DeleteRemote"), "sync ${i + 1} plans no del-remote: ${run.actions}")
        }
        assertEquals(
            1,
            first.warnings.count { it.contains("deps") },
            "the first sync warns once about the link: ${first.warnings}",
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    // Linux refuses the name already in Path.resolve (it has no UTF-8 form); NTFS takes it. Skips the test where it is refused.
    private fun seedLoneSurrogate(
        root: Path,
        name: String,
        content: String,
    ): Path {
        val file = runCatching { root.resolve(name).also { Files.writeString(it, content) } }.getOrNull()
        assumeTrue("this host cannot create a name with a lone surrogate", file != null)
        return file!!
    }

    private fun junction(
        link: Path,
        target: Path,
    ): Path {
        assumeTrue("junctions (mklink /J) exist only on Windows", System.getProperty("os.name").lowercase().contains("win"))
        val made = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start()
        val output = made.inputStream.readBytes().toString(Charsets.UTF_8)
        assumeTrue("mklink /J is not available here: $output", made.waitFor() == 0 && Files.exists(link, LinkOption.NOFOLLOW_LINKS))
        return link
    }

    private fun remoteNames(): List<String> = names(remote)

    private fun localNames(): List<String> = names(local)

    private fun names(root: Path): List<String> =
        Files.list(root).use { s -> s.map { it.fileName.toString() }.filter { !it.startsWith(".unidrive") }.toList() }.sorted()

    private fun sync(): Run {
        reporter.newRun()
        scannerLog.list.clear()
        runBlocking { engine.syncOnce() }
        return reporter.run(scannerLog.list.filter { it.level == Level.WARN }.map { it.formattedMessage })
    }

    private class Run(
        val failed: Int,
        val actions: Map<String, Int>,
        val warnings: List<String>,
    )

    private class RunReporter : ProgressReporter {
        private var failed = 0
        private var actions: Map<String, Int> = emptyMap()
        private val warnings = java.util.concurrent.CopyOnWriteArrayList<String>()

        fun newRun() {
            failed = 0
            actions = emptyMap()
            warnings.clear()
        }

        fun run(logged: List<String>) = Run(failed, actions, warnings.toList() + logged)

        override fun onScanProgress(
            phase: String,
            count: Int,
        ) {}

        override fun onActionCount(
            total: Int,
            preFilterTotal: Int,
            filterReason: String?,
        ) {}

        override fun onActionProgress(
            index: Int,
            total: Int,
            action: String,
            path: String,
        ) {}

        override fun onTransferProgress(
            path: String,
            bytesTransferred: Long,
            totalBytes: Long,
        ) {}

        override fun onSyncComplete(
            downloaded: Int,
            uploaded: Int,
            conflicts: Int,
            durationMs: Long,
            actionCounts: Map<String, Int>,
            failed: Int,
        ) {
            this.failed = failed
            this.actions = actionCounts
        }

        override fun onWarning(message: String) {
            warnings += message
        }
    }
}
