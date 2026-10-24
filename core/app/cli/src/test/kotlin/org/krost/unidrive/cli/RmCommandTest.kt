package org.krost.unidrive.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcServer
import org.krost.unidrive.sync.ProcessLock
import org.krost.unidrive.sync.StateDatabase
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `unidrive rm` deletes one remote file, or with `-r` one remote folder, through the provider's own delete. The
 * end-to-end tests run the real command through Main's picocli CommandLine against a localfs profile whose "cloud" is
 * a temp directory, so what was deleted (or kept) is read straight off the disk.
 */
class RmCommandTest {
    private lateinit var configDir: Path
    private lateinit var remoteRoot: Path
    private lateinit var syncRoot: Path
    private lateinit var socketDir: Path
    private val originalOut = System.out
    private lateinit var captured: ByteArrayOutputStream

    @BeforeTest
    fun setUp() {
        configDir = Files.createTempDirectory("ud-rm-cfg")
        remoteRoot = Files.createTempDirectory("ud-rm-remote")
        syncRoot = Files.createTempDirectory("ud-rm-local")
        socketDir = Files.createTempDirectory("ud-rm-sock")
        writeProfile("mount")
        // The real socket lives in the user's runtime folder and ignores -c; the tests point it at their own folder.
        RmHooks.socketPath = { socketDir.resolve("$it.sock") }
        captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true, Charsets.UTF_8))
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        RmHooks.socketPath = { IpcServer.defaultSocketPath(it) }
        for (dir in listOf(configDir, remoteRoot, syncRoot, socketDir)) runCatching { dir.toFile().deleteRecursively() }
    }

    private fun toml(p: Path) = p.toString().replace('\\', '/')

    private fun writeProfile(mode: String) {
        Files.writeString(
            configDir.resolve("config.toml"),
            """
            |[providers.localtest]
            |type = "localfs"
            |mode = "$mode"
            |sync_root = "${toml(syncRoot)}"
            |root_path = "${toml(remoteRoot)}"
            """.trimMargin(),
        )
    }

    private fun profileDir(): Path = configDir.resolve("localtest").also { Files.createDirectories(it) }

    private fun rm(vararg args: String): Int = CommandLine(Main()).execute("-c", configDir.toString(), "-p", "localtest", "rm", *args)

    private fun output(): String = captured.toString(Charsets.UTF_8)

    private fun jsonReply(): JsonObject = Json.parseToJsonElement(output().trim().lines().last()).jsonObject

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun seedTree() {
        Files.writeString(remoteRoot.resolve("a.txt"), "alpha")
        Files.createDirectories(remoteRoot.resolve("dir/sub"))
        Files.writeString(remoteRoot.resolve("dir/b.txt"), "bravo")
        Files.writeString(remoteRoot.resolve("dir/sub/c.txt"), "charlie")
    }

    // ── the provider route (mount profile, no daemon, no state yet) ─────────────

    @Test
    fun `rm of a file removes it from the remote`() {
        seedTree()
        assertEquals(0, rm("/a.txt"))
        assertFalse(Files.exists(remoteRoot.resolve("a.txt")), "the file is gone from the remote")
        assertTrue(Files.exists(remoteRoot.resolve("dir/b.txt")), "nothing else was touched")
    }

    @Test
    fun `rm of a folder without -r refuses and deletes nothing`() {
        seedTree()
        assertEquals(1, rm("--json", "/dir"))
        val reply = jsonReply()
        assertEquals("false", reply.str("ok"))
        assertEquals("path_is_folder", reply.str("error"))
        assertTrue(Files.exists(remoteRoot.resolve("dir/sub/c.txt")), "the folder is untouched")
    }

    @Test
    fun `rm -r --yes removes the folder with everything in it`() {
        seedTree()
        assertEquals(0, rm("-r", "--yes", "--json", "/dir"))
        val reply = jsonReply()
        assertEquals("true", reply.str("ok"))
        assertEquals("/dir", reply.str("path"))
        assertEquals("folder", reply.str("kind"))
        assertEquals("provider", reply.str("route"))
        assertEquals("permanent", reply.str("deleted_as"), "localfs deletes for good; the reply says so")
        assertFalse(Files.exists(remoteRoot.resolve("dir")), "the tree is gone")
        assertTrue(Files.exists(remoteRoot.resolve("a.txt")), "the sibling stays")
    }

    @Test
    fun `rm -r of a folder without a console and without --yes asks for confirmation and deletes nothing`() {
        seedTree()
        assertEquals(1, rm("-r", "--json", "/dir"))
        assertEquals("confirmation_required", jsonReply().str("error"))
        assertTrue(Files.exists(remoteRoot.resolve("dir/sub/c.txt")))
    }

    @Test
    fun `rm of a missing path is not_found`() {
        seedTree()
        assertEquals(1, rm("--json", "/nope.txt"))
        assertEquals("not_found", jsonReply().str("error"))
    }

    @Test
    fun `rm of the root is refused`() {
        seedTree()
        assertEquals(1, rm("-r", "--yes", "--json", "/"))
        assertEquals("root_refused", jsonReply().str("error"))
        assertTrue(Files.exists(remoteRoot.resolve("a.txt")))
    }

    @Test
    fun `a mirror profile is refused and the remote is unchanged`() {
        writeProfile("mirror")
        seedTree()
        assertEquals(78, rm("--json", "/a.txt"))
        val reply = jsonReply()
        assertEquals("false", reply.str("ok"))
        assertEquals("mirror_profile", reply.str("error"))
        assertTrue(Files.exists(remoteRoot.resolve("a.txt")), "a mirror profile's remote is never touched by rm")
    }

    @Test
    fun `a mount profile with state but no daemon is refused so the state stays true`() {
        seedTree()
        StateDatabase(profileDir().resolve("state.db")).also { it.initialize() }.close()
        assertEquals(1, rm("--json", "/a.txt"))
        assertEquals("daemon_not_running", jsonReply().str("error"))
        assertTrue(Files.exists(remoteRoot.resolve("a.txt")))
    }

    // ── the daemon route ─────────────────────────────────────────────────────────

    private class Recorded(val verb: String, val scope: String?, val json: String)

    /** A daemon stand-in: holds the profile lock in daemon mode and answers the hydration verbs over the real IPC server. */
    private fun withFakeDaemon(
        unlinkReply: String,
        rmdirReply: String,
        block: (MutableList<Recorded>) -> Unit,
    ) = runBlocking(Dispatchers.IO) {
        val dir = profileDir()
        val lock = ProcessLock(dir.resolve(".lock"))
        assertTrue(lock.tryLock(ProcessLock.Mode.DAEMON))
        val auth = IpcAuth.issue(dir, "localtest", "test")
        val server = IpcServer(socketPath = RmHooks.socketPath("localtest"), auth = auth)
        val recorded = Collections.synchronizedList(mutableListOf<Recorded>())
        server.registerHandler("hydration.unlink") { conn, json ->
            recorded += Recorded("hydration.unlink", server.scopeOf(conn), json)
            unlinkReply
        }
        server.registerHandler("hydration.rmdir") { conn, json ->
            recorded += Recorded("hydration.rmdir", server.scopeOf(conn), json)
            rmdirReply
        }
        val job = Job()
        try {
            server.start(CoroutineScope(coroutineContext + job))
            block(recorded)
        } finally {
            server.close()
            job.cancel()
            lock.unlock()
        }
    }

    @Test
    fun `rm -r with a running daemon sends hydration rmdir recursive under the full scope`() {
        seedTree()
        withFakeDaemon(
            unlinkReply = """{"ok":false,"error":"path_is_folder"}""",
            rmdirReply = """{"ok":true}""",
        ) { recorded ->
            assertEquals(0, rm("-r", "--yes", "--json", "/dir"))
            val rmdir = recorded.single { it.verb == "hydration.rmdir" }
            assertEquals("full", rmdir.scope, "a write verb needs the full-scope connection")
            val request = Json.parseToJsonElement(rmdir.json).jsonObject
            assertEquals("/dir", request.str("path"))
            assertEquals("true", request.str("recursive"))
            val reply = jsonReply()
            assertEquals("daemon", reply.str("route"))
            assertEquals("folder", reply.str("kind"))
        }
        assertTrue(Files.exists(remoteRoot.resolve("dir")), "the daemon deletes, not the command behind its back")
    }

    // ── --permanent (archive#1178) ──────────────────────────────────────────────────────

    @Test
    fun `rm --permanent --yes deletes a file for good on the provider route`() {
        seedTree()

        assertEquals(0, rm("--permanent", "--yes", "--json", "/a.txt"))

        val reply = jsonReply()
        assertEquals("true", reply.str("ok"))
        assertEquals("permanent", reply.str("deleted_as"), "the reply says for good, not the provider default")
        assertFalse(Files.exists(remoteRoot.resolve("a.txt")))
    }

    @Test
    fun `rm --permanent without a console and without --yes refuses and deletes nothing`() {
        seedTree()

        assertEquals(1, rm("--permanent", "--json", "/a.txt"))
        assertEquals("confirmation_required", jsonReply().str("error"))
        assertTrue(Files.exists(remoteRoot.resolve("a.txt")), "nothing was deleted")
    }

    @Test
    fun `rm -r --permanent asks exactly one question and sends permanent on both daemon requests`() {
        seedTree()
        val questions = Collections.synchronizedList(mutableListOf<String>())
        val defaultConsole = RmHooks.console
        RmHooks.console = {
            { ask ->
                questions += ask
                true
            }
        }
        try {
            withFakeDaemon(
                unlinkReply = """{"ok":false,"error":"path_is_folder"}""",
                rmdirReply = """{"ok":true}""",
            ) { recorded ->
                assertEquals(0, rm("-r", "--permanent", "/dir"))
                assertEquals(listOf("Delete '/dir' (a folder with everything in it, if it is one) FOR GOOD, bypassing the provider's trash?"), questions)
                assertEquals(
                    "true",
                    Json.parseToJsonElement(recorded.single { it.verb == "hydration.unlink" }.json).jsonObject.str("permanent"),
                )
                val rmdirRequest = Json.parseToJsonElement(recorded.single { it.verb == "hydration.rmdir" }.json).jsonObject
                assertEquals("true", rmdirRequest.str("permanent"))
                assertEquals("true", rmdirRequest.str("recursive"))
            }
        } finally {
            RmHooks.console = defaultConsole
        }
    }

    @Test
    fun `a daemon that answers permanent_unsupported is refused with the mapped token`() {
        seedTree()
        withFakeDaemon(
            unlinkReply = """{"ok":false,"error":"permanent_unsupported"}""",
            rmdirReply = """{"ok":true}""",
        ) { _ ->
            assertEquals(1, rm("--permanent", "--yes", "--json", "/a.txt"))
            assertEquals("permanent_delete_unsupported", jsonReply().str("error"))
        }
        assertTrue(Files.exists(remoteRoot.resolve("a.txt")), "nothing was deleted")
    }

    @Test
    fun `rm --permanent of a folder without -r is refused by the daemon's path_is_folder`() {
        seedTree()
        withFakeDaemon(
            unlinkReply = """{"ok":false,"error":"path_is_folder"}""",
            rmdirReply = """{"ok":true}""",
        ) { recorded ->
            assertEquals(1, rm("--permanent", "--yes", "--json", "/dir"))
            assertEquals("path_is_folder", jsonReply().str("error"))
            assertEquals(listOf("hydration.unlink"), recorded.map { it.verb }, "no rmdir without -r")
        }
    }

    @Test
    fun `rm of a file with a running daemon sends hydration unlink`() {
        seedTree()
        withFakeDaemon(unlinkReply = """{"ok":true}""", rmdirReply = """{"ok":true}""") { recorded ->
            assertEquals(0, rm("--json", "/a.txt"))
            assertEquals(listOf("hydration.unlink"), recorded.map { it.verb })
            assertEquals("file", jsonReply().str("kind"))
        }
    }

    @Test
    fun `a folder without -r is refused by the daemon route before rmdir is asked`() {
        seedTree()
        withFakeDaemon(unlinkReply = """{"ok":false,"error":"path_is_folder"}""", rmdirReply = """{"ok":true}""") { recorded ->
            assertEquals(1, rm("--json", "/dir"))
            assertEquals("path_is_folder", jsonReply().str("error"))
            assertTrue(recorded.none { it.verb == "hydration.rmdir" })
        }
    }

    @Test
    fun `daemon tokens map to rm refusals`() {
        seedTree()
        withFakeDaemon(unlinkReply = """{"ok":false,"error":"unknown_path"}""", rmdirReply = """{"ok":true}""") {
            assertEquals(1, rm("--json", "/nope"))
            assertEquals("not_found", jsonReply().str("error"))
        }
    }

    @Test
    fun `a recursive delete the daemon refuses for unsynced bytes says so`() {
        seedTree()
        withFakeDaemon(
            unlinkReply = """{"ok":false,"error":"path_is_folder"}""",
            rmdirReply = """{"ok":false,"error":"not_empty"}""",
        ) {
            assertEquals(1, rm("-r", "--yes", "--json", "/dir"))
            assertEquals("unsynced_below", jsonReply().str("error"))
        }
    }

    @Test
    fun `a daemon that holds the profile but does not answer is refused and nothing is deleted`() {
        seedTree()
        val lock = ProcessLock(profileDir().resolve(".lock"))
        assertTrue(lock.tryLock(ProcessLock.Mode.DAEMON))
        try {
            assertEquals(1, rm("--json", "/a.txt"))
            assertEquals("daemon_not_answering", jsonReply().str("error"))
        } finally {
            lock.unlock()
        }
        assertTrue(Files.exists(remoteRoot.resolve("a.txt")), "no delete behind a silent daemon's back")
    }
}
