package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * archive#951 S5: the two indexes no query needs are gone (the one on parent_uuid, the one on COALESCE(remote_path, path)),
 * the lookup by effective remote path is two seeks, and the queries over a subtree seek a path range instead of reading
 * every alive row (`substr(path, 1, length(?)) = ?`, or an OR around a range). Each rewritten query is checked twice:
 * its plan (a SEARCH, no SCAN of the table) and its answer against the same question asked in Kotlin over a tree with
 * names outside the BMP, NFD input, names that differ only in case and the characters LIKE would have read as patterns.
 */
class StateDatabaseSchemaTrimTest {
    private lateinit var dir: Path
    private lateinit var dbFile: Path
    private val prepared = mutableListOf<String>()
    private lateinit var db: StateDatabase

    // The desktop connector, recording the SQL of every statement the writable connection prepares.
    private val recording =
        object : StateDatabaseConnector {
            override fun open(path: Path?): Connection {
                val real = JdbcStateDatabaseConnector.open(path)
                return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                    if (method.name == "prepareStatement") prepared += args[0] as String
                    try {
                        method.invoke(real, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                } as Connection
            }

            override fun openReadOnly(path: Path): Connection = JdbcStateDatabaseConnector.openReadOnly(path)
        }

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-schema-trim")
        dbFile = dir.resolve("state.db")
        db = StateDatabase(dbFile, connector = recording)
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    private val stamp = Instant.parse("2026-03-28T12:00:00Z")

    private fun row(
        path: String,
        remotePath: String? = null,
        hydrated: Boolean = false,
        folder: Boolean = false,
    ) = SyncEntry(
        path = path,
        remotePath = remotePath,
        remoteId = "id-$path",
        remoteHash = null,
        remoteSize = 1,
        remoteModified = stamp,
        localMtime = null,
        localSize = null,
        isFolder = folder,
        isPinned = false,
        isHydrated = hydrated,
        lastSynced = stamp,
    )

    private fun indexes(): Set<String> =
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='sync_entries'").use { rs ->
                    buildSet { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    /** The SQL [call] prepared on the writer, each with the plan SQLite reports for it. */
    private fun plansOf(call: () -> Unit): Map<String, String> {
        prepared.clear()
        db.batch { call() } // inside a batch every statement runs on the writer connection, whose SQL is recorded
        return DriverManager.getConnection("jdbc:sqlite:$dbFile").use { c ->
            prepared.distinct().associateWith { sql ->
                c.prepareStatement("EXPLAIN QUERY PLAN $sql").use { st ->
                    st.executeQuery().use { rs -> buildString { while (rs.next()) append(rs.getString("detail")).append('\n') } }
                }
            }
        }
    }

    private fun assertSeeks(
        name: String,
        call: () -> Unit,
    ) {
        val plans = plansOf(call)
        assertTrue(plans.isNotEmpty(), "$name prepared no statement")
        for ((sql, plan) in plans) {
            assertTrue("SEARCH sync_entries" in plan && "SCAN sync_entries" !in plan, "$name: expected seeks only for\n$sql\ngot plan:\n$plan")
        }
    }

    @Test
    fun `an older file loses the two indexes at its next open, rows intact, and opens again`() {
        db.upsertEntry(row("/a.txt"))
        db.upsertEntry(row("/Bilder/x.jpg", remotePath = "/Pictures/x.jpg"))
        db.close()
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { c ->
            c.createStatement().use { st ->
                st.executeUpdate("CREATE INDEX idx_sync_entries_parent_alive ON sync_entries(parent_uuid, status)")
                st.executeUpdate(
                    "CREATE INDEX idx_sync_entries_remote_path_alive ON sync_entries(COALESCE(remote_path, path)) WHERE status='EXISTS'",
                )
            }
        }
        assertTrue(indexes().containsAll(listOf("idx_sync_entries_parent_alive", "idx_sync_entries_remote_path_alive")))

        repeat(2) {
            db.initialize()
            val names = indexes()
            assertTrue("idx_sync_entries_parent_alive" !in names && "idx_sync_entries_remote_path_alive" !in names, "got $names")
            assertEquals(2, db.getEntryCount())
            assertEquals("/Bilder/x.jpg", db.getEntryByRemotePath("/Pictures/x.jpg")?.path)
            assertEquals("/a.txt", db.getEntryByRemotePath("/a.txt")?.path)
            db.close()
        }
        db.initialize()
    }

    @Test
    fun `each rewritten query seeks an index instead of reading the table`() {
        db.upsertEntry(row("/p", folder = true))
        for (i in 0 until 20) db.upsertEntry(row("/p/f$i", hydrated = i % 2 == 0))
        db.upsertEntry(row("/Bilder", remotePath = "/Pictures", folder = true))
        db.upsertEntry(row("/Bilder/x.jpg", remotePath = "/Pictures/x.jpg"))

        assertSeeks("getEntriesByPrefix") { db.getEntriesByPrefix("/p/") }
        assertSeeks("hasEntriesBelow") { db.hasEntriesBelow("/p/") }
        assertSeeks("hasHydratedDescendant") { db.hasHydratedDescendant("/p") }
        assertSeeks("countEntriesUnderTopLevel") { db.countEntriesUnderTopLevel("/p") }
        assertSeeks("getEntryByRemotePath") { db.getEntryByRemotePath("/p/f3") }
        assertSeeks("assignRemotePaths") { db.assignRemotePaths("/p", "/q") }
        assertSeeks("rebaseRemotePaths") { db.rebaseRemotePaths("/Pictures", "/Photos") }
    }

    // A tree with what the old substr/LIKE matching got wrong once: a name outside the BMP, an NFD name, folders that
    // differ only in case, a sibling whose name extends the folder's, and LIKE's pattern characters.
    private val tree =
        listOf(
            "/p" to false, "/p/a.txt" to true, "/p/sub" to false, "/p/sub/b.txt" to false, "/p/😀" to false,
            "/p/😀/c.txt" to true, "/p0" to false, "/p0/x" to true, "/pa/x" to false, "/P/x" to true,
            "/😀/d" to true, "/😀x/e" to false, "/földer/a" to true, "/földer2/b" to false,
            "/Docs/a" to true, "/docs/b" to false, "/a_b/c" to false, "/axb/c" to true, "/a%b/d" to false,
        )

    private fun seedTree() {
        for ((path, hydrated) in tree) db.upsertEntry(row(path, hydrated = hydrated))
    }

    @Test
    fun `the subtree queries answer as a prefix match in Kotlin does`() {
        seedTree()
        val alive = db.getAllEntries()
        assertEquals(tree.size, alive.size)

        val prefixes =
            listOf("/", "/p/", "/p", "/P/", "/😀/", "/😀", "/föl", "/földer2/", "/docs/", "/Docs/", "/a_b/", "/a%", "/zz/")
        for (raw in prefixes) {
            val prefix = PathNormalizer.nfc(raw)
            val expected = alive.map { it.path }.filter { it.startsWith(prefix) }.sorted()
            assertEquals(expected, db.getEntriesByPrefix(raw).map { it.path }.sorted(), "getEntriesByPrefix($raw)")
            assertEquals(expected.isNotEmpty(), db.hasEntriesBelow(raw), "hasEntriesBelow($raw)")
        }

        for (raw in listOf("/p", "/P", "/😀", "/docs", "/Docs", "/földer", "/földer", "/a_b", "/a%b", "/zz")) {
            val top = PathNormalizer.nfc(raw)
            val under = alive.filter { it.path == top || it.path.startsWith("$top/") }
            assertEquals(under.size, db.countEntriesUnderTopLevel(raw), "countEntriesUnderTopLevel($raw)")
            assertEquals(under.any { it.isHydrated || it.localMtime != null }, db.hasHydratedDescendant(raw), "hasHydratedDescendant($raw)")
        }
    }

    @Test
    fun `assignRemotePaths and rebaseRemotePaths change exactly the rows at and beneath the path`() {
        seedTree()
        db.assignRemotePaths("/p", "/server-p")
        db.assignRemotePaths("/😀", "/smile")

        fun remotes() = db.getAllEntries().associate { it.path to it.remotePath }
        val assigned = remotes()
        for ((path, _) in tree.map { PathNormalizer.nfc(it.first) to it.second }) {
            val expected =
                when {
                    path == "/p" || path.startsWith("/p/") -> "/server-p" + path.removePrefix("/p")
                    path == "/😀" || path.startsWith("/😀/") -> "/smile" + path.removePrefix("/😀")
                    else -> null
                }
            assertEquals(expected, assigned[path], "remote path of $path after assign")
        }

        db.rebaseRemotePaths("/server-p", "/moved")
        val rebased = remotes()
        for ((path, remote) in assigned) {
            val expected =
                remote?.let {
                    if (it == "/server-p" || it.startsWith("/server-p/")) "/moved" + it.removePrefix("/server-p") else it
                }
            assertEquals(expected, rebased[path], "remote path of $path after rebase")
        }
    }

    @Test
    fun `the lookup by effective remote path answers as before, aliased rows and twins included`() {
        db.upsertEntry(row("/a"))
        db.upsertEntry(row("/Bilder/x.jpg", remotePath = "/Pictures/x.jpg"))
        db.upsertEntry(row("/dup"))
        db.upsertEntry(row("/dup (1)", remotePath = "/dup"))
        db.upsertEntry(row("/same", remotePath = "/same"))
        db.upsertEntry(row("/other", remotePath = "/elsewhere"))
        val alive = db.getAllEntries()
        assertNotNull(alive.singleOrNull { it.path == "/dup (1)" && it.remotePath == "/dup" })

        for (q in listOf("/a", "/Pictures/x.jpg", "/Bilder/x.jpg", "/dup", "/same", "/elsewhere", "/other", "/missing")) {
            // COALESCE(remote_path, path) = q, the shortest path first: what the single expression index answered.
            val expected = alive.filter { (it.remotePath ?: it.path) == q }.minByOrNull { it.path.length }?.path
            assertEquals(expected, db.getEntryByRemotePath(q)?.path, "getEntryByRemotePath($q)")
        }
    }
}
