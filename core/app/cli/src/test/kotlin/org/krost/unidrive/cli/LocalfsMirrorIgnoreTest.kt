package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ScanContext
import org.krost.unidrive.localfs.LocalFsProvider
import org.krost.unidrive.sync.ProgressReporter
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.ignore.IgnoreSettings
import org.krost.unidrive.sync.ignore.UploadIgnore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The mirror applies the ignore rules (`.unidrive.ignore`, the account's rules, the content defaults) the way it
 * applies `exclude_patterns`: an ignored path is not uploaded, not downloaded and not deleted on either side. A rule
 * file that cannot be read, or that the cloud holds and this computer does not yet, holds its folder back (fail
 * closed). The "cloud" is a plain directory (`localfs`), and the real mirror engine runs against it in-process.
 */
class LocalfsMirrorIgnoreTest {
    private lateinit var base: Path
    private lateinit var local: Path
    private lateinit var remote: Path
    private lateinit var db: StateDatabase
    private lateinit var engine: SyncEngine
    private val reporter = Reporter()

    // Folder paths whose remote delete this pass reports as a file tombstone (no folder facet).
    private var folderTombstones: Set<String> = emptySet()

    // File times only move forward, ten seconds per write, so an edit differs from its predecessor in time and size.
    private var stamp = System.currentTimeMillis() - 86_400_000L

    @BeforeTest
    fun setUp() {
        base = Files.createTempDirectory("ud-mirror-ignore")
        local = Files.createDirectories(base.resolve("local"))
        remote = Files.createDirectories(base.resolve("remote"))
        db = StateDatabase(Files.createDirectories(base.resolve("profile")).resolve("state.db")).also { it.initialize() }
    }

    @AfterTest
    fun tearDown() {
        runCatching { db.close() }
        runCatching { base.toFile().deleteRecursively() }
    }

    @Test
    fun `an ignored local file and an ignored folder are not uploaded, the rule file is`() {
        start()
        put(local, ".unidrive.ignore", "secret.txt\nbuild/\n")
        put(local, "secret.txt", "stays here")
        put(local, "build/out.o", "object code")
        put(local, "docs/secret.txt", "stays here too")
        put(local, "docs/new.txt", "goes up")

        sync()

        assertFalse(Files.exists(remote.resolve("secret.txt")), "an ignored file is not uploaded")
        assertFalse(Files.exists(remote.resolve("build")), "an ignored folder is not uploaded")
        assertFalse(Files.exists(remote.resolve("docs/secret.txt")), "the rule applies below its folder")
        assertEquals("goes up", Files.readString(remote.resolve("docs/new.txt")))
        assertEquals("secret.txt\nbuild/\n", Files.readString(remote.resolve(".unidrive.ignore")), "the rule file syncs")
        assertQuiet()
    }

    @Test
    fun `a synced path that becomes ignored is neither deleted nor overwritten on either side`() {
        start()
        put(local, ".unidrive.ignore", "photos/\n")
        sync()
        Files.delete(local.resolve("photos/p1.bin"))
        put(remote, "photos/p2.bin", "p2 bytes, edited in the cloud")
        put(local, "photos/p3.bin", "new and ignored")

        sync()

        assertEquals("p1 bytes", Files.readString(remote.resolve("photos/p1.bin")), "a local delete of an ignored file deletes nothing in the cloud")
        assertEquals("p2 bytes", Files.readString(local.resolve("photos/p2.bin")), "a cloud edit of an ignored file is not downloaded over it")
        assertFalse(Files.exists(remote.resolve("photos/p3.bin")))
        assertQuiet()
    }

    @Test
    fun `removing the rule file makes its paths eligible again and deletes nothing`() {
        start()
        put(local, ".unidrive.ignore", "secret.txt\n")
        put(local, "secret.txt", "held back for now")
        sync()
        assertFalse(Files.exists(remote.resolve("secret.txt")))

        Files.delete(local.resolve(".unidrive.ignore"))
        sync()

        assertEquals("held back for now", Files.readString(remote.resolve("secret.txt")), "no longer ignored: uploaded")
        assertTrue(Files.exists(local.resolve("secret.txt")))
        assertFalse(Files.exists(remote.resolve(".unidrive.ignore")), "the rule file's delete reaches the cloud")
        assertQuiet()
    }

    @Test
    fun `an unreadable rule file holds back its folder's uploads, not the rest`() {
        start(UploadIgnore.settingsFor(honorGitignore = false, accountRules = emptyList()).copy(maxRuleFileBytes = 16))
        put(local, "docs/.unidrive.ignore", "# longer than sixteen bytes\n*.bak\n")
        put(local, "docs/report.txt", "held back")
        put(local, "keep.txt", "keep v2, longer than v1")

        sync()

        assertFalse(Files.exists(remote.resolve("docs/report.txt")), "fail closed: nothing below the folder is uploaded")
        assertEquals("keep v2, longer than v1", Files.readString(remote.resolve("keep.txt")), "outside the folder it syncs")
        assertQuiet()
    }

    @Test
    fun `a rule file only the cloud holds holds its folder back until it is downloaded`() {
        start()
        // Another computer added the rule; this one has a file the rule names, not yet synced.
        put(remote, "notes/.unidrive.ignore", "draft.txt\n")
        put(local, "notes/draft.txt", "a private draft")

        sync()

        assertFalse(Files.exists(remote.resolve("notes/draft.txt")), "the folder waits for its rule file")
        assertEquals("draft.txt\n", Files.readString(local.resolve("notes/.unidrive.ignore")), "the rule file is downloaded")

        sync()

        assertFalse(Files.exists(remote.resolve("notes/draft.txt")), "once read, the rule keeps it local")
        assertQuiet()
    }

    @Test
    fun `without rule files the built-in defaults still keep version-control folders local`() {
        start()
        put(local, "proj/.git/config", "[core]")
        put(local, "proj/main.kt", "fun main() {}")

        sync()

        assertFalse(Files.exists(remote.resolve("proj/.git")))
        assertEquals("fun main() {}", Files.readString(remote.resolve("proj/main.kt")))
        assertQuiet()
    }

    @Test
    fun `a rule file re-includes a version-control folder`() {
        start()
        put(local, "proj/.unidrive.ignore", "!.git\n")
        put(local, "proj/.git/config", "[core]")

        sync()

        assertEquals("[core]", Files.readString(remote.resolve("proj/.git/config")))
        assertQuiet()
    }

    @Test
    fun `a remote delete of an ignored folder keeps the local tree when the tombstone has no folder bit`() {
        // A directory-only rule still applies when the delete tombstone does not say folder: the local tree stays.
        start(useTrash = false)
        put(local, ".unidrive.ignore", "photos/\n")
        sync()
        remote.resolve("photos").toFile().deleteRecursively()
        folderTombstones = setOf("/photos")

        sync()

        assertEquals("p1 bytes", Files.readString(local.resolve("photos/p1.bin")))
        assertEquals("p2 bytes", Files.readString(local.resolve("photos/p2.bin")))
        assertQuiet()
    }

    @Test
    fun `a directory-only negation is created remotely and its re-included file is uploaded`() {
        // `!keep/` re-includes the directory itself, not only a file that happens to live under it.
        start()
        put(local, ".unidrive.ignore", "*\n!keep/\n!keep/file.txt\n")
        put(local, "keep/file.txt", "kept")

        sync()

        assertTrue(Files.isDirectory(remote.resolve("keep")))
        assertEquals("kept", Files.readString(remote.resolve("keep/file.txt")))
        assertQuiet()
    }

    @Test
    fun `a cloud-only rule file holds its folder back on the streaming pass that first lists it`() {
        // The ignored file is not uploaded on the same streaming pass that first sees the rule file.
        start(streaming = true)
        put(remote, "notes/.unidrive.ignore", "draft.txt\n")
        put(local, "notes/draft.txt", "a private draft")

        sync()

        assertFalse(Files.exists(remote.resolve("notes/draft.txt")))
        assertEquals("draft.txt\n", Files.readString(local.resolve("notes/.unidrive.ignore")))
        assertQuiet()
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Builds the engine with the ignore rules on and converges on the START tree. */
    private fun start(
        settings: IgnoreSettings = UploadIgnore.settingsFor(honorGitignore = false, accountRules = emptyList()),
        streaming: Boolean = false,
        useTrash: Boolean = true,
    ) {
        val provider = TombstoneListing(LocalFsProvider(remote))
        engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = local,
                reporter = reporter,
                useTrash = useTrash,
                streamingReconciliation = streaming,
                cacheRoot = Files.createDirectories(base.resolve("cache")),
                mirrorIgnore = settings,
            )
        runBlocking { provider.authenticate() }
        for ((path, content) in START) put(remote, path, content)
        sync()
        for ((path, content) in START) assertEquals(content, Files.readString(local.resolve(path)), "first sync: $path")
    }

    private fun sync() {
        reporter.reset()
        runBlocking { engine.syncOnce() }
        assertEquals(0, reporter.failed, "no action failed; warnings: ${reporter.warnings}")
    }

    /** One more sync with nothing changed plans nothing and changes neither tree. */
    private fun assertQuiet() {
        val localBefore = tree(local)
        val remoteBefore = tree(remote)
        sync()
        assertEquals(0, reporter.planned, "a second sync plans nothing")
        assertEquals(localBefore, tree(local))
        assertEquals(remoteBefore, tree(remote))
    }

    private fun put(
        root: Path,
        rel: String,
        content: String,
    ) {
        val file = root.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
        stamp += 10_000
        Files.setLastModifiedTime(file, FileTime.fromMillis(stamp))
    }

    private fun tree(root: Path): Map<String, String> =
        Files.walk(root).use { stream ->
            stream
                .filter { it != root }
                .map { path ->
                    val rel = root.relativize(path).joinToString("/")
                    if (Files.isDirectory(path)) "$rel/" to "" else rel to Files.readString(path)
                }.toList()
        }.toMap().toSortedMap()

    /** localfs lists what is on disk and never emits a delete. This adds folder tombstones that claim to be files. */
    private inner class TombstoneListing(
        private val inner: LocalFsProvider,
    ) : CloudProvider by inner {
        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage {
            val page = inner.delta(cursor, onPageProgress, scanContext)
            if (folderTombstones.isEmpty()) return page
            val tombstones =
                folderTombstones.map { path ->
                    CloudItem(
                        id = path,
                        name = path.substringAfterLast('/'),
                        path = path,
                        size = 0,
                        isFolder = false,
                        modified = null,
                        created = null,
                        hash = null,
                        mimeType = null,
                        deleted = true,
                    )
                }
            return page.copy(items = page.items + tombstones)
        }
    }

    private class Reporter : ProgressReporter {
        @Volatile var planned = 0

        @Volatile var failed = 0
        val warnings = java.util.concurrent.CopyOnWriteArrayList<String>()

        fun reset() {
            planned = 0
            failed = 0
            warnings.clear()
        }

        override fun onScanProgress(
            phase: String,
            count: Int,
        ) {}

        override fun onActionCount(
            total: Int,
            preFilterTotal: Int,
            filterReason: String?,
        ) {
            planned = total
        }

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
        }

        override fun onWarning(message: String) {
            warnings += message
        }
    }

    companion object {
        private val START =
            mapOf(
                "keep.txt" to "keep v1",
                "docs/a.txt" to "alpha",
                "photos/p1.bin" to "p1 bytes",
                "photos/p2.bin" to "p2 bytes",
            )
    }
}
