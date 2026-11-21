package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.krost.unidrive.sync.ProcessLock
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.RenameIntent
import org.krost.unidrive.sync.model.ReplaceIntent
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `profile reset-state` (gkrost/unidrive-cli#863, item 2): drops the tombstones of an emptied profile, the staged scan and the
 * transfer history, and gives the space back. Every test drives the real command line against a temp config directory; the
 * daemon is a fake (a process lock held by the test, a listening socket in a temp folder). No cloud, no real account.
 */
class ProfileResetStateTest {
    private lateinit var dir: Path
    private lateinit var socketDir: Path
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()
    private val originalOut = System.out
    private val originalErr = System.err

    private var holder: ProcessLock? = null
    private var listener: ServerSocketChannel? = null

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("profile-reset-state")
        socketDir = Files.createDirectories(dir.resolve("sock"))
        ProfileRemoveHooks.socketPath = { socketDir.resolve("$it.sock") }
        ProfileRemoveHooks.cacheRoot = { dir.resolve("cache") }
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        Files.writeString(
            dir.resolve("config.toml"),
            """
            [general]
            default_profile = "emptied"

            [providers.emptied]
            type = "localfs"
            mode = "mirror"
            sync_root = "${toml(root)}"
            root_path = "${toml(dir.resolve("remote"))}"
            """.trimIndent() + "\n",
        )
        Files.createDirectories(root)
        Files.writeString(root.resolve("my-document.txt"), "precious")
        Files.createDirectories(profileDir)
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        holder?.unlock()
        listener?.close()
        ProfileRemoveHooks.socketPath = { org.krost.unidrive.sync.IpcServer.defaultSocketPath(it) }
        ProfileRemoveHooks.cacheRoot = { org.krost.unidrive.sync.SyncEngine.defaultHydrationCacheRoot() }
        dir.toFile().deleteRecursively()
    }

    private val profileDir: Path get() = dir.resolve("emptied")
    private val root: Path get() = dir.resolve("roots").resolve("emptied")
    private val stateDb: Path get() = profileDir.resolve("state.db")
    private val staging: Path get() = profileDir.resolve("scan-staging.db")
    private val telemetry: Path get() = profileDir.resolve("telemetry.db")

    private fun toml(p: Path) = p.toString().replace('\\', '/')

    private fun entry(
        path: String,
        remoteId: String,
    ) = SyncEntry(
        path = path,
        remoteId = remoteId,
        remoteHash = "h".repeat(64),
        remoteSize = 10,
        remoteModified = null,
        localMtime = 1L,
        localSize = 10L,
        isFolder = false,
        isPinned = false,
        isHydrated = false,
        lastSynced = Instant.parse("2026-01-01T00:00:00Z"),
    )

    /** [tombstones] DELETED rows (long paths, so the file is big), [alive] EXISTS rows, and one fresh mount-delete tombstone. */
    private fun seedState(
        tombstones: Int = 3_000,
        alive: Int = 3,
        configure: (StateDatabase) -> Unit = {},
    ) {
        val db = StateDatabase(stateDb)
        db.initialize()
        try {
            db.batch {
                for (i in 0 until tombstones) {
                    val path = "/old/" + "d".repeat(150) + "/file-$i.bin"
                    db.upsertEntry(entry(path, "cloud-old-$i"))
                    db.markDeleted(path)
                }
            }
            for (i in 0 until alive) db.upsertEntry(entry("/alive/file-$i.txt", "cloud-alive-$i"))
            db.upsertEntry(entry("/fresh/just-deleted.txt", "cloud-fresh"))
            db.markDeleted("/fresh/just-deleted.txt", mountDelete = true)
            configure(db)
        } finally {
            db.close()
        }
    }

    /** A real SQLite file with [rows] rows of filler, standing in for the staged scan or the transfer history. */
    private fun fatSqlite(
        file: Path,
        rows: Int = 2_000,
    ) {
        DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}").use { c ->
            c.createStatement().use { it.execute("CREATE TABLE filler (id INTEGER PRIMARY KEY, blob BLOB)") }
            c.autoCommit = false
            c.prepareStatement("INSERT INTO filler (blob) VALUES (zeroblob(1000))").use { st ->
                repeat(rows) { st.executeUpdate() }
            }
            c.commit()
        }
    }

    private fun rowCount(
        sql: String,
        db: Path = stateDb,
    ): Int =
        DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { c ->
            c.createStatement().use { st -> st.executeQuery(sql).use { it.next(); it.getInt(1) } }
        }

    private fun run(vararg args: String): Int {
        out.reset()
        err.reset()
        val code = CommandLine(Main()).execute("-c", dir.toString(), *args)
        System.out.flush()
        System.err.flush()
        return code
    }

    private fun json(): JsonObject {
        val text = out.toString(Charsets.UTF_8).trim()
        assertEquals(1, text.lines().size, "stdout must be a single line of JSON, was: $text")
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun refusalToken(): String {
        val r = json()
        assertFalse(r.getValue("ok").jsonPrimitive.boolean, "expected a refusal, got $r")
        return r.getValue("error").jsonPrimitive.content
    }

    private fun resetJson(vararg extra: String): Int = run("profile", "reset-state", "emptied", "--yes", "--json", *extra)

    private fun fakeDaemonLock() {
        holder = ProcessLock(profileDir.resolve(".lock")).also { assertTrue(it.tryLock(ProcessLock.Mode.DAEMON)) }
    }

    private fun assertUntouched(
        stateBytes: Long,
        stagingBytes: Long,
        telemetryBytes: Long,
    ) {
        assertEquals(stateBytes, Files.size(stateDb), "state.db must be as it was")
        assertEquals(stagingBytes, Files.size(staging), "scan-staging.db must be as it was")
        assertEquals(telemetryBytes, Files.size(telemetry), "telemetry.db must be as it was")
        assertEquals(3_001, rowCount("SELECT COUNT(*) FROM sync_entries WHERE status='DELETED'"))
        assertTrue(Files.exists(root.resolve("my-document.txt")))
    }

    private fun seedAll(configure: (StateDatabase) -> Unit = {}): Triple<Long, Long, Long> {
        seedState(configure = configure)
        fatSqlite(staging)
        fatSqlite(telemetry)
        return Triple(Files.size(stateDb), Files.size(staging), Files.size(telemetry))
    }

    // ── refusals ─────────────────────────────────────────────────────────────

    @Test
    fun `a daemon that holds the profile lock refuses the reset and nothing changes`() {
        val (s, g, t) = seedAll()
        fakeDaemonLock()
        assertEquals(1, resetJson())
        assertEquals("daemon_running", refusalToken())
        assertUntouched(s, g, t)
    }

    @Test
    fun `a daemon whose socket answers refuses the reset and nothing changes`() {
        val (s, g, t) = seedAll()
        listener =
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).also {
                it.bind(UnixDomainSocketAddress.of(socketDir.resolve("emptied.sock")))
            }
        assertEquals(1, resetJson())
        assertEquals("daemon_running", refusalToken())
        assertUntouched(s, g, t)
    }

    @Test
    fun `another unidrive process that holds the profile refuses the reset`() {
        val (s, g, t) = seedAll()
        holder = ProcessLock(profileDir.resolve(".lock")).also { assertTrue(it.tryLock(ProcessLock.Mode.SYNC)) }
        assertEquals(1, resetJson())
        assertEquals("profile_in_use", refusalToken())
        assertUntouched(s, g, t)
    }

    @Test
    fun `pending uploads refuse the reset and nothing changes`() {
        val (s, g, t) =
            seedAll { db ->
                db.upsertEntry(entry("/pending.txt", "local:/pending.txt").copy(isHydrated = true))
            }
        assertEquals(1, resetJson())
        assertEquals("uploads_pending", refusalToken())
        assertUntouched(s, g, t)
    }

    @Test
    fun `a pending rename or replace intent refuses the reset`() {
        for (intent in listOf<(StateDatabase) -> Unit>(
            { it.recordRenameIntent(RenameIntent("/a", "/b", "cloud-alive-0", ghost = false)) },
            { it.recordReplaceIntent(ReplaceIntent("/src", "/dest", "/.parked", "cloud-src", "cloud-dest")) },
        )) {
            for (f in listOf(stateDb, staging, telemetry)) Files.deleteIfExists(f)
            val (s, g, t) = seedAll(intent)
            assertEquals(1, resetJson())
            assertEquals("operations_pending", refusalToken())
            assertUntouched(s, g, t)
        }
    }

    @Test
    fun `without --yes a script is refused and nothing changes`() {
        val (s, g, t) = seedAll()
        assertEquals(1, run("profile", "reset-state", "emptied", "--json"))
        assertEquals("confirmation_required", refusalToken())
        assertUntouched(s, g, t)
    }

    @Test
    fun `an unknown profile is refused`() {
        assertEquals(1, run("profile", "reset-state", "nobody", "--yes", "--json"))
        assertEquals("profile_not_found", refusalToken())
    }

    // ── what is removed ──────────────────────────────────────────────────────

    @Test
    fun `the reset drops the tombstones and gives the space of all three files back`() {
        val (s, g, t) = seedAll()
        assertEquals(0, resetJson())

        // state.db: tombstones gone, alive rows and the fresh mount delete (a lagging listing may still show it) stay.
        assertEquals(3, rowCount("SELECT COUNT(*) FROM sync_entries WHERE status='EXISTS'"))
        assertEquals(1, rowCount("SELECT COUNT(*) FROM sync_entries WHERE status='DELETED'"))
        assertEquals(1, rowCount("SELECT COUNT(*) FROM sync_entries WHERE remote_id='cloud-fresh'"))
        assertTrue(Files.size(stateDb) < s / 4, "state.db must shrink: $s -> ${Files.size(stateDb)}")
        // the staged scan and the history are rebuilt by the next run: the files are gone
        assertFalse(Files.exists(staging), "scan-staging.db must be removed")
        assertFalse(Files.exists(telemetry), "telemetry.db must be removed")
        assertFalse(Files.exists(profileDir.resolve("scan-staging.db-wal")))
        // the user's tree is never touched
        assertEquals("precious", Files.readString(root.resolve("my-document.txt")))

        val r = json()
        assertTrue(r.getValue("ok").jsonPrimitive.boolean)
        assertEquals(3_000L, r.getValue("removed").jsonObject.getValue("tombstones").jsonPrimitive.long)
        val files = r.getValue("files").jsonObject
        val state = files.getValue("state.db").jsonObject
        assertEquals(s, state.getValue("bytes_before").jsonPrimitive.long)
        assertEquals(Files.size(stateDb), state.getValue("bytes_after").jsonPrimitive.long)
        val stagingReport = files.getValue("scan-staging.db").jsonObject
        assertEquals(g, stagingReport.getValue("bytes_before").jsonPrimitive.long)
        assertEquals(0L, stagingReport.getValue("bytes_after").jsonPrimitive.long)
        assertEquals(2_000L, stagingReport.getValue("rows_removed").jsonPrimitive.long)
        val telemetryReport = files.getValue("telemetry.db").jsonObject
        assertEquals(t, telemetryReport.getValue("bytes_before").jsonPrimitive.long)
        assertEquals(2_000L, telemetryReport.getValue("rows_removed").jsonPrimitive.long)
    }

    @Test
    fun `a profile without staging or telemetry files resets cleanly`() {
        seedState()
        assertEquals(0, resetJson())
        assertEquals(0, rowCount("SELECT COUNT(*) FROM sync_entries WHERE status='DELETED' AND remote_id<>'cloud-fresh'"))
        assertEquals(0L, json().getValue("files").jsonObject.getValue("scan-staging.db").jsonObject.getValue("bytes_before").jsonPrimitive.long)
    }

    @Test
    fun `all also drops the alive rows after a copy of the state file is put aside`() {
        seedAll()
        assertEquals(0, resetJson("--all"))
        assertEquals(0, rowCount("SELECT COUNT(*) FROM sync_entries"))
        val backups = Files.list(profileDir).use { s -> s.filter { it.fileName.toString().startsWith(StateReset.BACKUP_PREFIX) }.toList() }
        assertEquals(1, backups.size, "state.db must be copied aside before everything is cleared")
        assertNotNull(json().getValue("backup").jsonPrimitive.content)
        assertEquals("precious", Files.readString(root.resolve("my-document.txt")))
    }

    // review of #871: an alive row with edit_base is a file edited locally (hydration cache) that the cloud has not seen; --all would
    // drop its row, and nothing would ever upload the edit.
    @Test
    fun `all refuses while an alive row holds an unsynced edit and nothing changes`() {
        val (s, g, t) =
            seedAll { db ->
                db.upsertEntry(entry("/edited.txt", "cloud-edited"))
                assertTrue(db.markEdited("/edited.txt", "base-token"))
            }
        assertEquals(1, resetJson("--all"))
        assertEquals("unsynced_edits", refusalToken())
        assertUntouched(s, g, t)
        assertEquals(1, rowCount("SELECT COUNT(*) FROM sync_entries WHERE remote_id='cloud-edited' AND edit_base IS NOT NULL"))
    }

    // review of #871: the default mode only drops tombstones, and the row that is unsynced stays alive with it.
    @Test
    fun `the default mode keeps an alive row that holds an unsynced edit`() {
        seedAll { db ->
            db.upsertEntry(entry("/edited.txt", "cloud-edited"))
            assertTrue(db.markEdited("/edited.txt", "base-token"))
        }
        assertEquals(0, resetJson())
        assertEquals(1, rowCount("SELECT COUNT(*) FROM sync_entries WHERE remote_id='cloud-edited' AND edit_base IS NOT NULL"))
    }

    // review of #871: the Reconciler's un-trash reads TRASHED rows (Reconciler: db.recovery.trashedEntries()); an item restored from the
    // provider's trash must meet its row, not arrive as a new remote file beside a local file that has none.
    @Test
    fun `the default mode keeps a TRASHED row and --all drops it`() {
        val trashed = { db: StateDatabase -> db.upsertEntry(entry("/trashed.txt", "cloud-trashed").copy(status = org.krost.unidrive.sync.model.EntryStatus.TRASHED)) }
        seedAll(trashed)
        assertEquals(0, resetJson())
        assertEquals(1, rowCount("SELECT COUNT(*) FROM sync_entries WHERE status='TRASHED'"), "un-trash still finds its row")
        assertEquals(3_000L, json().getValue("removed").jsonObject.getValue("tombstones").jsonPrimitive.long, "only the DELETED rows went")

        assertEquals(0, resetJson("--all"))
        assertEquals(0, rowCount("SELECT COUNT(*) FROM sync_entries"))
    }

    @Test
    fun `the reset never starts a daemon or asks the cloud`() {
        seedAll()
        // No socket, no provider: it runs on the files alone. A second run finds nothing left to remove and still succeeds.
        assertEquals(0, resetJson())
        assertEquals(0, resetJson())
        assertEquals(0L, json().getValue("removed").jsonObject.getValue("tombstones").jsonPrimitive.long)
    }
}
