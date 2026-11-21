package org.krost.unidrive.engine

import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.testing.TestScratch
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the remote merge (`RemoteGather.updateRemoteEntries`) records about WHO last changed a row: a delta or enumeration
 * delivered the item (gkrost/unidrive-cli#849), as opposed to an upload the engine itself sent. A row that carries no stamp
 * (written before the columns, or by a writer that changed nothing) stays unstamped — absence, never a wrong value.
 */
class RemoteMergeChangedByTest {
    private lateinit var tmp: Path
    private lateinit var db: StateDatabase
    private lateinit var dbPath: Path

    @BeforeTest
    fun setUp() {
        tmp = TestScratch.directory("unidrive-remote-merge-changed-by")
        dbPath = tmp.resolve("state.db")
        db = StateDatabase(dbPath)
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tmp.toFile().deleteRecursively()
    }

    private fun gather() =
        RemoteGather(
            provider = FakeViewProvider(),
            db = db,
            guard =
                RemoteOperationGuard(
                    standingScope = emptyList(),
                    syncPaths = emptyList(),
                    excludePatterns = emptyList(),
                    maxConcurrentTransfers = 2,
                    matchesGlob = { _, _ -> false },
                ),
            options = RemoteGather.Options(providerId = "test"),
            listener = object : RemoteGather.Listener {},
            log = LoggerFactory.getLogger(RemoteMergeChangedByTest::class.java),
        )

    private fun item(
        id: String,
        path: String,
        size: Long = 5,
        hash: String? = "h1",
    ) = CloudItem(
        id = id,
        name = path.substringAfterLast('/'),
        path = path,
        size = size,
        isFolder = false,
        modified = null,
        created = null,
        hash = hash,
        mimeType = null,
    )

    /** The stamp of the alive row at [path], read outside StateDatabase so the assertion sees what is stored. */
    private fun stamp(path: String): Pair<String?, Long?> =
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT changed_by, changed_at_ms FROM sync_entries WHERE path='$path' AND status='EXISTS'").use { rs ->
                    check(rs.next()) { "no alive row at $path" }
                    rs.getLong(2).let { if (rs.wasNull()) null else it }.let { ms -> rs.getString(1) to ms }
                }
            }
        }

    /** A stamp written from outside, as an uploader other than the merge would have left it. */
    private fun writeStamp(
        path: String,
        by: String,
        atMs: Long,
    ) {
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { s ->
                check(
                    s.executeUpdate("UPDATE sync_entries SET changed_by='$by', changed_at_ms=$atMs WHERE path='$path' AND status='EXISTS'") == 1,
                ) { "no alive row at $path" }
            }
        }
    }

    @Test
    fun `a delta delivery stamps the row remote`() {
        val before = System.currentTimeMillis()
        gather().updateRemoteEntries(mapOf("/a.txt" to item("id-a", "/a.txt")))
        val after = System.currentTimeMillis()
        val (by, atMs) = stamp("/a.txt")
        assertEquals("remote", by, "the engine saw the change through a delivery")
        assertNotNull(atMs)
        assertTrue(atMs in before..after, "changed_at_ms is when the delivery was merged, got $atMs")
    }

    @Test
    fun `an enumeration stamps the row remote like a delta`() {
        val before = System.currentTimeMillis()
        // The first gather's cursor is empty: it lists the whole drive — the enumeration provenance, not a delta's.
        gather().updateRemoteEntries(
            mapOf("/a.txt" to item("id-a", "/a.txt"), "/b.txt" to item("id-b", "/b.txt")),
        )
        val after = System.currentTimeMillis()
        for (path in listOf("/a.txt", "/b.txt")) {
            val (by, atMs) = stamp(path)
            assertEquals("remote", by)
            assertNotNull(atMs)
            assertTrue(atMs in before..after)
        }
    }

    @Test
    fun `a re-delivered unchanged item keeps its stamp`() {
        gather().updateRemoteEntries(mapOf("/a.txt" to item("id-a", "/a.txt")))
        val (_, firstMs) = stamp("/a.txt")
        Thread.sleep(5)
        gather().updateRemoteEntries(mapOf("/a.txt" to item("id-a", "/a.txt")))
        val (by, secondMs) = stamp("/a.txt")
        assertEquals("remote", by)
        assertEquals(firstMs, secondMs, "nothing was delivered that the row did not already hold")
    }

    @Test
    fun `a new version from the cloud re-stamps a locally-uploaded row remote`() {
        // The row as an upload left it: the engine's own change, seen before the cloud delivered a newer version.
        gather().updateRemoteEntries(mapOf("/a.txt" to item("id-a", "/a.txt", hash = "h1")))
        writeStamp("/a.txt", by = "local", atMs = 1000)
        Thread.sleep(5)
        gather().updateRemoteEntries(mapOf("/a.txt" to item("id-a", "/a.txt", size = 9, hash = "h2")))
        val (by, atMs) = stamp("/a.txt")
        assertEquals("remote", by)
        assertNotNull(atMs)
        assertTrue(atMs!! > 1000, "the delivery's stamp replaces the upload's")
    }

    @Test
    fun `a delivery that only renews the token re-stamps too`() {
        // gkrost/unidrive-cli#164 class: same bytes, the provider moved its write token — the row took something new.
        gather().updateRemoteEntries(mapOf("/a.txt" to item("id-a", "/a.txt").copy(version = "\"{A},1\"")))
        val (_, firstMs) = stamp("/a.txt")
        Thread.sleep(5)
        gather().updateRemoteEntries(mapOf("/a.txt" to item("id-a", "/a.txt").copy(version = "\"{A},2\"")))
        val (by, secondMs) = stamp("/a.txt")
        assertEquals("remote", by)
        assertTrue(secondMs!! > firstMs!!, "the token the row records changed")
    }
}
