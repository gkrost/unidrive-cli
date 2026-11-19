package org.krost.unidrive.cli

import org.krost.unidrive.CloudItem
import org.krost.unidrive.engine.TrashNamespace
import org.krost.unidrive.engine.ViewNamespaces
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #825: `verify` on a mount profile. There is no sync_root, so the audit holds the state.db rows against the live remote
 * listing (and hydrated rows against their cache copies). A localfs mount profile stands in for the cloud: the remote is
 * a temp directory, the rows are seeded behind its back.
 */
class VerifyMountModeTest {
    private lateinit var configDir: Path
    private lateinit var remoteRoot: Path
    private lateinit var cacheRoot: Path
    private lateinit var dbPath: Path
    private val originalOut = System.out
    private lateinit var captured: ByteArrayOutputStream

    @BeforeTest
    fun setUp() {
        configDir = TestScratch.directory("ud-verify-mount-cfg")
        remoteRoot = TestScratch.directory("ud-verify-mount-remote")
        cacheRoot = TestScratch.directory("ud-verify-mount-cache")
        Files.writeString(
            configDir.resolve("config.toml"),
            """
            |[providers.mounttest]
            |type = "localfs"
            |mode = "mount"
            |sync_root = "${toml(configDir.resolve("mounttest/sync-root"))}"
            |root_path = "${toml(remoteRoot)}"
            """.trimMargin(),
        )
        dbPath = configDir.resolve("mounttest").also { Files.createDirectories(it) }.resolve("state.db")
        VerifyCommand.cacheRoot = { cacheRoot }
        captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true, Charsets.UTF_8))
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        VerifyCommand.cacheRoot = { SyncEngine.defaultHydrationCacheRoot() }
    }

    private fun toml(p: Path) = p.toString().replace('\\', '/')

    private fun runVerify(vararg extra: String): Int =
        CommandLine(Main()).execute("-c", configDir.toString(), "-p", "mounttest", "verify", *extra)

    private fun output(): String = captured.toString(Charsets.UTF_8)

    private fun row(
        path: String,
        size: Long,
        remoteId: String? = path,
        isFolder: Boolean = false,
        isHydrated: Boolean = false,
        remoteHash: String? = null,
    ) = SyncEntry(
        path = path,
        remoteId = remoteId,
        remoteHash = remoteHash,
        remoteSize = size,
        remoteModified = Instant.now(),
        localMtime = null,
        localSize = if (isHydrated) size else null,
        isFolder = isFolder,
        isPinned = false,
        isHydrated = isHydrated,
        lastSynced = Instant.now(),
    )

    private fun item(
        path: String,
        size: Long,
        hash: String? = null,
        trashed: Boolean = false,
    ) = CloudItem(
        id = path,
        name = path.substringAfterLast('/'),
        path = path,
        size = size,
        isFolder = false,
        modified = null,
        created = null,
        hash = hash,
        mimeType = null,
        trashed = trashed,
    )

    private fun withDb(block: (StateDatabase) -> Unit) {
        val db = StateDatabase(dbPath)
        db.initialize()
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    /** A file the cloud and the view agree on. */
    private fun seedConverged(
        rel: String,
        content: String,
    ) {
        val remote = remoteRoot.resolve(rel)
        Files.createDirectories(remote.parent)
        Files.writeString(remote, content)
        withDb { it.upsertEntry(row("/$rel", content.length.toLong())) }
    }

    // Every file below [roots]: its content digest and its modification time.
    private fun snapshot(vararg roots: Path): Map<String, String> =
        roots.flatMap { root ->
            Files.walk(root).use { s -> s.filter { it.isRegularFile() }.toList() }.map { f ->
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes())
                f.toString() to "${digest.joinToString("") { "%02x".format(it) }}@${Files.getLastModifiedTime(f).toMillis()}"
            }
        }.toMap()

    @Test
    fun `mount-mode verify audits without a sync root and exits 0 when converged`() {
        seedConverged("a.txt", "alpha")
        seedConverged("dir/b.txt", "bravo")
        assertFalse(Files.exists(configDir.resolve("mounttest/sync-root")))
        assertEquals(VerifyCommand.EXIT_CONVERGED, runVerify(), output())
        assertTrue(output().contains("converged") && output().contains("no local walk"), output())
    }

    @Test
    fun `mount-mode verify reports a stale row as row-only`() {
        seedConverged("a.txt", "alpha")
        withDb { it.upsertEntry(row("/dir/gone.txt", 3)) }
        assertEquals(VerifyCommand.EXIT_DIVERGED, runVerify(), output())
        val out = output()
        assertTrue(Regex("""row-only\s+/dir/gone.txt""").containsMatchIn(out), out)
        assertTrue(out.contains("Refresh settles:  /dir"), "names the subtree a refresh settles: $out")
    }

    @Test
    fun `mount-mode verify reports an unobserved remote item as remote-only`() {
        seedConverged("a.txt", "alpha")
        Files.createDirectories(remoteRoot.resolve("new"))
        Files.writeString(remoteRoot.resolve("new/c.txt"), "charlie")
        assertEquals(VerifyCommand.EXIT_DIVERGED, runVerify(), output())
        assertTrue(Regex("""remote-only\s+/new/c.txt""").containsMatchIn(output()), output())
    }

    @Test
    fun `mount-mode verify reports a size mismatch`() {
        seedConverged("a.txt", "alpha")
        Files.writeString(remoteRoot.resolve("a.txt"), "alpha, edited in the cloud")
        assertEquals(VerifyCommand.EXIT_DIVERGED, runVerify(), output())
        assertTrue(Regex("""size-mismatch\s+/a.txt""").containsMatchIn(output()), output())
    }

    @Test
    fun `mount-mode verify ignores virtual view folders`() {
        seedConverged("a.txt", "alpha")
        withDb { db ->
            // The trash root is found by its stable key whatever it is called; the rows below it are the trash's.
            db.upsertEntry(row("/Mülleimer", 0, remoteId = TrashNamespace.ROOT_ID, isFolder = true))
            db.upsertEntry(row("/Mülleimer/old.txt", 9, remoteId = "real-id-of-old"))
            db.upsertEntry(row("/Recents/x.txt", 9, remoteId = ViewNamespaces.ROOT_KEY_PREFIX + "recents:abc"))
        }
        assertEquals(VerifyCommand.EXIT_CONVERGED, runVerify(), output())
        assertTrue(output().contains("2 rows in virtual folders"), output())
    }

    @Test
    fun `mount-mode verify honours sync-path`() {
        seedConverged("inbox/a.txt", "alpha")
        withDb { it.upsertEntry(row("/other/gone.txt", 3)) }
        assertEquals(VerifyCommand.EXIT_CONVERGED, runVerify("--sync-path", "/inbox"), output())
        assertEquals(VerifyCommand.EXIT_DIVERGED, runVerify("--sync-path", "/other"), output())
    }

    @Test
    fun `mount-mode verify flags a hydrated row whose cache copy is missing`() {
        Files.writeString(remoteRoot.resolve("h.txt"), "hydrated")
        Files.writeString(remoteRoot.resolve("ok.txt"), "cached")
        withDb {
            it.upsertEntry(row("/h.txt", 8, isHydrated = true))
            it.upsertEntry(row("/ok.txt", 6, isHydrated = true))
        }
        val cacheDir = SyncEngine.hydrationCacheRoot(cacheRoot, "mounttest").also { Files.createDirectories(it) }
        Files.writeString(cacheDir.resolve("ok.txt"), "cached")
        assertEquals(VerifyCommand.EXIT_DIVERGED, runVerify(), output())
        val out = output()
        assertTrue(Regex("""hydration-claim\s+/h.txt""").containsMatchIn(out), out)
        assertFalse(out.contains("/ok.txt"), "a matching cache copy is no divergence: $out")
    }

    @Test
    fun `mount-mode verify is read-only`() {
        seedConverged("a.txt", "alpha")
        withDb {
            it.upsertEntry(row("/gone.txt", 3, isHydrated = true))
        }
        Files.writeString(remoteRoot.resolve("new.txt"), "new")
        val before = snapshot(remoteRoot, cacheRoot) + snapshot(dbPath)
        assertEquals(VerifyCommand.EXIT_DIVERGED, runVerify(), output())
        assertEquals(before, snapshot(remoteRoot, cacheRoot) + snapshot(dbPath), "state.db, the cloud and the cache are unchanged")
        // A read-only SQLite open of a WAL database may leave the empty -wal/-shm pair; no page is ever written to it.
        val wal = dbPath.resolveSibling("state.db-wal")
        assertTrue(!Files.exists(wal) || Files.size(wal) == 0L, "nothing was written through the WAL")
    }

    @Test
    fun `mount-mode verify on a missing state db cannot audit and exits 2`() {
        assertEquals(VerifyCommand.EXIT_UNAUDITABLE, runVerify())
    }

    // ── the pure classification ──

    @Test
    fun `an etag that differs from the row's is etag-mismatch only where both carry one`() {
        val report =
            VerifyAudit.auditMount(
                dbEntries = listOf(row("/a", 1, remoteHash = "v1"), row("/b", 1, remoteHash = null), row("/c", 1, remoteHash = "v1")),
                remoteFiles = mapOf("/a" to item("/a", 1, "v2"), "/b" to item("/b", 1, "v9"), "/c" to item("/c", 1, "v1")),
            )
        assertEquals(listOf("/a"), report.etagMismatch)
    }

    @Test
    fun `a pending upload row is not a stale row`() {
        val report = VerifyAudit.auditMount(dbEntries = listOf(row("/up.txt", 0, remoteId = null)), remoteFiles = emptyMap())
        assertTrue(report.converged, report.toString())
        assertEquals(1, report.pendingUploadCount)
    }

    @Test
    fun `a trashed remote item is not an unobserved change`() {
        val report = VerifyAudit.auditMount(dbEntries = emptyList(), remoteFiles = mapOf("/t" to item("/t", 1, trashed = true)))
        assertTrue(report.converged, report.toString())
    }

    @Test
    fun `a cache copy holding an unsynced edit may differ in size but not be missing`() {
        val edited = row("/e", 5, isHydrated = true).copy(editBase = "v1")
        val remote = mapOf("/e" to item("/e", 5))
        assertTrue(VerifyAudit.auditMount(listOf(edited), remote, cacheCopySize = { 99L }).converged)
        assertEquals(listOf("/e"), VerifyAudit.auditMount(listOf(edited), remote, cacheCopySize = { null }).hydrationMismatch)
    }

    @Test
    fun `settling subtrees collapse to the topmost folders`() {
        val report =
            VerifyAudit.auditMount(
                dbEntries = listOf(row("/a/b/x", 1), row("/a/y", 1), row("/c/z", 1)),
                remoteFiles = emptyMap(),
            )
        assertEquals(listOf("/a", "/c"), VerifyAudit.settlingSubtrees(report))
    }
}
