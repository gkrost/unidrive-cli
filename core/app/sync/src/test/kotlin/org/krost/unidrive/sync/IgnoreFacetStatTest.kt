package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.*
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

// #770 (#743 finding 5): the ignore filter asks the disk whether a path is a directory only when the answer can change
// the verdict, so a profile without ignore rules (or with rules that do not depend on the folder facet) costs no stat
// per path; a directory-only rule still sees a directory that only the disk knows about.
class IgnoreFacetStatTest {
    private lateinit var db: StateDatabase
    private lateinit var syncRoot: Path
    private lateinit var reconciler: Reconciler
    private val stats = AtomicInteger()

    @BeforeTest
    fun setUp() {
        syncRoot = TestScratch.directory("unidrive-ignore-facet-stat")
        db = StateDatabase(TestScratch.directory("unidrive-ignore-facet-stat-db").resolve("state.db"))
        db.initialize()
        reconciler = Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH)
        reconciler.isDirectoryOnDisk = { stats.incrementAndGet(); Files.isDirectory(it) }
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun remoteFile(path: String) =
        CloudItem(
            id = "id-$path",
            name = path.substringAfterLast("/"),
            path = path,
            size = 3,
            isFolder = false,
            modified = Instant.parse("2026-03-28T12:00:00Z"),
            created = Instant.parse("2026-03-28T10:00:00Z"),
            hash = "h",
            mimeType = "text/plain",
        )

    private fun newLocalFile(path: String) {
        val p = syncRoot.resolve(path.removePrefix("/"))
        Files.createDirectories(p.parent)
        Files.writeString(p, "x")
    }

    @Test
    fun `reconcile_without_ignore_rules_stats_no_path_for_the_folder_facet`() {
        val remote = (1..20).associate { "/r$it.txt" to remoteFile("/r$it.txt") }
        (1..20).forEach { newLocalFile("/l$it.txt") }
        val local = (1..20).associate { "/l$it.txt" to ChangeState.NEW }
        reconciler.reconcile(remote, local)
        assertEquals(0, stats.get(), "no rules wired: the facet must not be asked")
    }

    @Test
    fun `reconcile_with_rules_blind_to_the_folder_facet_stats_no_path`() {
        reconciler.isIgnored = { path, _ -> path.endsWith(".tmp") }
        val remote = (1..20).associate { "/r$it.txt" to remoteFile("/r$it.txt") }
        newLocalFile("/junk.tmp")
        val actions = reconciler.reconcile(remote, mapOf("/junk.tmp" to ChangeState.NEW))
        assertEquals(0, stats.get(), "a verdict the facet cannot change needs no stat")
        assertTrue(actions.none { it.path == "/junk.tmp" }, "the rule still ignores: $actions")
    }

    @Test
    fun `directory_only_rule_still_sees_a_directory_only_the_disk_knows`() {
        reconciler.isIgnored = { path, isDir -> isDir && path == "/photos" }
        Files.createDirectories(syncRoot.resolve("photos"))
        newLocalFile("/photos.txt")
        val actions = reconciler.reconcile(emptyMap(), mapOf("/photos" to ChangeState.NEW, "/photos.txt" to ChangeState.NEW))
        assertTrue(actions.none { it.path == "/photos" }, "the directory-only rule ignores the directory: $actions")
        assertTrue(actions.any { it.path == "/photos.txt" }, "a file is not touched by the rule: $actions")
        assertTrue(stats.get() >= 1, "the disk was asked for the path the facet decides")
    }
}
