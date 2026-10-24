package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.localfs.LocalFsProvider
import org.krost.unidrive.sync.ProcessLock
import org.krost.unidrive.sync.ProgressReporter
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.SyncEngine
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `scope show | set | clear` (archive#395): the command edits `sync_path`, applies the scope transition to state.db and
 * replaces the guard's persisted scope with the current one. Every test starts from a profile that has really run
 * scoped to `/_INBOX` (the mirror engine on the `localfs` provider, in-process), so the state the command meets is the
 * one a sync leaves behind. The daemon is a fake: a process lock held by the test.
 */
class ScopeCommandTest {
    private lateinit var dir: Path
    private lateinit var local: Path
    private lateinit var remote: Path
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()
    private val originalOut = System.out
    private val originalErr = System.err
    private val originalHome = System.getProperty("user.home")
    private var holder: ProcessLock? = null

    private val profileName = "p1"
    private val profileDir: Path get() = dir.resolve(profileName)
    private val configPath: Path get() = dir.resolve("config.toml")

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("scope-command")
        System.setProperty("user.home", dir.toString())
        local = Files.createDirectories(dir.resolve("roots").resolve("mirror"))
        remote = Files.createDirectories(dir.resolve("remote"))
        Files.createDirectories(profileDir)
        Files.writeString(
            configPath,
            """
            [general]
            default_profile = "$profileName"

            [providers.$profileName]
            type = "localfs"
            mode = "mirror"
            sync_root = "${local.toString().replace('\\', '/')}"
            root_path = "${remote.toString().replace('\\', '/')}"
            sync_path = ["/_INBOX"]
            """.trimIndent() + "\n",
        )
        put(remote, "_INBOX/a.txt", "inbox file")
        put(remote, "other/b.txt", "other file")
        sync(listOf("/_INBOX"))
        assertTrue(Files.exists(local.resolve("_INBOX/a.txt")), "the scoped first run downloaded the inbox")
        assertEquals(listOf("/_INBOX"), persistedScope(profileDir), "the scoped run persisted its scope")
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
    }

    @AfterTest
    fun tearDown() {
        holder?.unlock()
        System.setOut(originalOut)
        System.setErr(originalErr)
        System.setProperty("user.home", originalHome)
        dir.toFile().deleteRecursively()
    }

    private fun put(
        root: Path,
        path: String,
        content: String,
    ) {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    private fun run(vararg args: String): Int {
        out.reset()
        err.reset()
        return CommandLine(Main()).execute("-c", dir.toString(), "scope", *args)
    }

    private fun configuredScope(): List<String>? = SyncConfig.parseRaw(Files.readString(configPath), configPath.toString()).providers[profileName]?.sync_path

    // The mirror engine as `unidrive sync` builds it for this profile: the standing scope is the run's scope.
    private fun sync(scope: List<String>) {
        val db = StateDatabase(profileDir.resolve("state.db")).also { it.initialize() }
        try {
            val provider = LocalFsProvider(remote)
            runBlocking { provider.authenticate() }
            val engine =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = local,
                    reporter = ProgressReporter.Silent,
                    syncPaths = scope,
                    standingScope = scope,
                    cacheRoot = Files.createDirectories(dir.resolve("cache")),
                )
            runBlocking { engine.syncOnce() }
        } finally {
            db.close()
        }
    }

    private fun state(key: String): String? {
        val db = StateDatabase(profileDir.resolve("state.db"), readOnly = true).also { it.initialize() }
        try {
            return db.getSyncState(key)
        } finally {
            db.close()
        }
    }

    private fun hasRow(path: String): Boolean {
        val db = StateDatabase(profileDir.resolve("state.db"), readOnly = true).also { it.initialize() }
        try {
            return db.getEntry(path) != null
        } finally {
            db.close()
        }
    }

    @Test
    fun `set replaces the scope and its history, untracks rows outside it and keeps every file`() {
        assertEquals(0, run("set", "/other"), err.toString(Charsets.UTF_8))
        val text = out.toString(Charsets.UTF_8)

        assertEquals(listOf("/other"), configuredScope())
        assertEquals(listOf("/other"), persistedScope(profileDir), "the history is replaced, not grown: /_INBOX is gone")
        assertEquals("/other", state("tracked_scope"))
        assertEquals("", state("delta_cursor"), "/other is new to the tracked set: the next gather re-enumerates")
        assertTrue(!hasRow("/_INBOX/a.txt"), "the inbox row is no longer tracked")
        assertTrue(Files.exists(local.resolve("_INBOX/a.txt")), "the local file stays")
        assertTrue(Files.exists(remote.resolve("_INBOX/a.txt")), "the remote file stays")
        assertTrue("/other" in text && Regex("(?i)stopped tracking \\d+ row").containsMatchIn(text), text)
        assertTrue("re-enumerates" in text, text)

        sync(listOf("/other"))
        assertTrue(Files.exists(local.resolve("other/b.txt")), "the next run syncs the new scope")
        assertTrue(Files.exists(local.resolve("_INBOX/a.txt")), "and leaves the old scope's local file alone")
        assertTrue(Files.exists(remote.resolve("_INBOX/a.txt")), "and its remote file")
    }

    @Test
    fun `clear removes sync_path and the next bidirectional run is a whole-drive run without --full-tree`() {
        assertEquals(0, run("clear"), err.toString(Charsets.UTF_8))
        val text = out.toString(Charsets.UTF_8)

        assertNull(configuredScope())
        assertEquals(emptyList(), persistedScope(profileDir))
        assertEquals("", state("tracked_scope"))
        assertTrue("whole drive" in text && "--full-tree" in text, text)

        // Without the command this run is refused by the scoped-history guard.
        sync(emptyList())
        assertTrue(Files.exists(local.resolve("other/b.txt")), "the whole drive is synced")
        assertTrue(Files.exists(local.resolve("_INBOX/a.txt")))
        assertTrue(Files.exists(remote.resolve("_INBOX/a.txt")))
        assertTrue(Files.exists(remote.resolve("other/b.txt")))
    }

    @Test
    fun `set --json reports the change and what the next run does`() {
        assertEquals(0, run("set", "/other", "/_INBOX/sub", "--json"), err.toString(Charsets.UTF_8))
        val json = Json.parseToJsonElement(out.toString(Charsets.UTF_8)).jsonObject
        assertTrue(json.getValue("ok").jsonPrimitive.boolean)
        assertEquals(profileName, json.getValue("profile").jsonPrimitive.content)
        assertEquals(listOf("/_INBOX/sub", "/other"), json.getValue("scope").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("/_INBOX"), json.getValue("previous").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1, json.getValue("untracked").jsonPrimitive.int)
        assertTrue(json.getValue("re_enumerate").jsonPrimitive.boolean)
        assertTrue(json.getValue("next_run").jsonPrimitive.content.isNotEmpty())
        assertEquals(listOf("/_INBOX/sub", "/other"), configuredScope())
    }

    @Test
    fun `show prints the configured scope and the persisted state`() {
        assertEquals(0, run("show"), err.toString(Charsets.UTF_8))
        assertTrue("/_INBOX" in out.toString(Charsets.UTF_8), out.toString(Charsets.UTF_8))

        assertEquals(0, run("show", "--json"))
        val json = Json.parseToJsonElement(out.toString(Charsets.UTF_8)).jsonObject
        assertEquals(listOf("/_INBOX"), json.getValue("scope").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("/_INBOX"), json.getValue("effective_scope").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("/_INBOX"), json.getValue("tracked_scope").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `a daemon holding the profile refuses with profile_in_use and changes nothing`() {
        val before = Files.readString(configPath)
        holder = ProcessLock(profileDir.resolve(".lock")).also { it.tryLock(ProcessLock.Mode.DAEMON) }

        assertEquals(1, run("set", "/other", "--json"))
        val json = Json.parseToJsonElement(out.toString(Charsets.UTF_8)).jsonObject
        assertEquals("profile_in_use", json.getValue("error").jsonPrimitive.content)
        assertEquals(1, run("clear"))
        assertTrue("in use" in err.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))

        assertEquals(before, Files.readString(configPath))
        holder?.unlock()
        holder = null
        assertEquals(listOf("/_INBOX"), persistedScope(profileDir))
        assertTrue(hasRow("/_INBOX/a.txt"))
    }

    @Test
    fun `a path that is not an absolute remote path is refused and nothing changes`() {
        val before = Files.readString(configPath)
        assertEquals(1, run("set", "other", "--json"))
        val json = Json.parseToJsonElement(out.toString(Charsets.UTF_8)).jsonObject
        assertEquals("invalid_value", json.getValue("error").jsonPrimitive.content)
        assertEquals(1, run("set", "/", "--json"))
        assertEquals("invalid_value", Json.parseToJsonElement(out.toString(Charsets.UTF_8)).jsonObject.getValue("error").jsonPrimitive.content)
        assertEquals(before, Files.readString(configPath))
        assertEquals(listOf("/_INBOX"), persistedScope(profileDir))
    }
}
