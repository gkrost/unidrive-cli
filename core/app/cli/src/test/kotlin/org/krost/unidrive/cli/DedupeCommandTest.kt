package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `unidrive dedupe` lists the duplicate remote items a profile keeps under decorated twin names (`name (dup-xxxxxxxx).ext`)
 * and what a keep rule would do with each set. It is a dry run: it reads state.db read-only and never calls a provider.
 * The tests drive the real command line against a temp config directory with a real state.db seeded with twin rows.
 */
class DedupeCommandTest {
    private lateinit var configDir: Path
    private lateinit var remoteRoot: Path
    private lateinit var syncRoot: Path
    private val originalOut = System.out
    private val originalErr = System.err
    private lateinit var captured: ByteArrayOutputStream
    private lateinit var capturedErr: ByteArrayOutputStream

    private val older = Instant.parse("2026-01-01T10:00:00Z")
    private val newer = Instant.parse("2026-02-01T10:00:00Z")

    @BeforeTest
    fun setUp() {
        configDir = Files.createTempDirectory("ud-dedupe-cfg")
        remoteRoot = Files.createTempDirectory("ud-dedupe-remote")
        syncRoot = Files.createTempDirectory("ud-dedupe-local")
        Files.writeString(
            configDir.resolve("config.toml"),
            """
            |[providers.localtest]
            |type = "localfs"
            |mode = "mount"
            |sync_root = "${toml(syncRoot)}"
            |root_path = "${toml(remoteRoot)}"
            """.trimMargin(),
        )
        Files.createDirectories(configDir.resolve("localtest"))
        captured = ByteArrayOutputStream()
        capturedErr = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true, Charsets.UTF_8))
        System.setErr(PrintStream(capturedErr, true, Charsets.UTF_8))
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        for (dir in listOf(configDir, remoteRoot, syncRoot)) runCatching { dir.toFile().deleteRecursively() }
    }

    private fun toml(p: Path) = p.toString().replace('\\', '/')

    private fun stateDb(): Path = configDir.resolve("localtest").resolve("state.db")

    private fun dedupe(vararg args: String): Int = CommandLine(Main()).execute("-c", configDir.toString(), "-p", "localtest", "dedupe", *args)

    private fun output(): String = captured.toString(Charsets.UTF_8)

    private fun jsonReply(): JsonObject = Json.parseToJsonElement(output().trim().lines().last()).jsonObject

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.groups(): List<JsonObject> = this["groups"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.ids(key: String): List<String> = (this[key] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()

    private fun row(
        path: String,
        id: String,
        remotePath: String? = null,
        size: Long = 5,
        modified: Instant? = older,
        isFolder: Boolean = false,
        contentId: String? = null,
    ) = SyncEntry(
        path = path,
        remotePath = remotePath,
        remoteId = id,
        remoteHash = "token-$id",
        remoteSize = size,
        remoteModified = modified,
        localMtime = null,
        localSize = null,
        isFolder = isFolder,
        isPinned = false,
        isHydrated = false,
        lastSynced = older,
        remoteContentId = contentId,
    )

    private fun seed(vararg rows: SyncEntry) {
        val db = StateDatabase(stateDb())
        try {
            db.initialize()
            rows.forEach { db.upsertEntry(it) }
        } finally {
            db.close()
        }
    }

    private fun aliveRows(): List<SyncEntry> {
        val db = StateDatabase(stateDb(), readOnly = true)
        try {
            db.initialize()
            return db.getAllEntries().sortedBy { it.path }
        } finally {
            db.close()
        }
    }

    // Two twins of /docs/a.txt: the canonical holder and the decorated loser, plus an unrelated file.
    private fun seedTwins(
        canonicalModified: Instant? = older,
        twinModified: Instant? = newer,
        canonicalSize: Long = 5,
        twinSize: Long = 5,
    ) = seed(
        row("/docs", "f0000000-0000", isFolder = true),
        row("/docs/a.txt", "aaaaaaaa-0001", size = canonicalSize, modified = canonicalModified),
        row("/docs/a (dup-bbbbbbbb).txt", "bbbbbbbb-0002", remotePath = "/docs/a.txt", size = twinSize, modified = twinModified),
        row("/docs/other.txt", "cccccccc-0003"),
    )

    @Test
    fun `dedupe --json lists one group with both ids and changes nothing`() {
        seedTwins()
        Files.createDirectories(remoteRoot.resolve("docs"))
        Files.writeString(remoteRoot.resolve("docs/a.txt"), "alpha")
        val rowsBefore = aliveRows()

        assertEquals(0, dedupe("--json"))

        val reply = jsonReply()
        assertEquals("true", reply.str("ok"))
        assertEquals("true", reply.str("dry_run"))
        assertEquals("1", reply.str("count"))
        val group = reply.groups().single()
        assertEquals("/docs/a.txt", group.str("path"))
        assertEquals("file", group.str("kind"))
        assertEquals("none", group.str("action"), "without --keep the plan is to do nothing")
        val members = group["members"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("aaaaaaaa-0001", "bbbbbbbb-0002"), members.map { it.str("id") }, "the canonical holder first")
        assertEquals(listOf("/docs/a.txt", "/docs/a (dup-bbbbbbbb).txt"), members.map { it.str("local_path") })
        assertEquals(listOf("true", "false"), members.map { it.str("canonical") })

        assertEquals(rowsBefore, aliveRows(), "state.db is untouched")
        assertEquals("alpha", Files.readString(remoteRoot.resolve("docs/a.txt")), "the remote is untouched")
    }

    @Test
    fun `--keep newest plans to trash the older twin by id and still changes nothing`() {
        seedTwins(canonicalModified = older, twinModified = newer)
        val rowsBefore = aliveRows()

        assertEquals(0, dedupe("--keep", "newest", "--json"))

        val reply = jsonReply()
        assertEquals("newest", reply.str("keep"))
        val group = reply.groups().single()
        assertEquals("trash", group.str("action"))
        assertEquals("bbbbbbbb-0002", group.str("keep_id"))
        assertEquals(listOf("aaaaaaaa-0001"), group.ids("trash_ids"))
        assertEquals(rowsBefore, aliveRows(), "a dry run with a keep rule changes nothing either")
    }

    @Test
    fun `--keep largest keeps the larger twin`() {
        seedTwins(canonicalSize = 9, twinSize = 5)

        assertEquals(0, dedupe("--keep", "largest", "--json"))

        val group = jsonReply().groups().single()
        assertEquals("aaaaaaaa-0001", group.str("keep_id"))
        assertEquals(listOf("bbbbbbbb-0002"), group.ids("trash_ids"))
        assertEquals("different", group.str("content"), "different sizes are different content")
    }

    @Test
    fun `--keep id keeps that member of its group`() {
        seedTwins()

        assertEquals(0, dedupe("--keep", "id:aaaaaaaa-0001", "--json"))

        val group = jsonReply().groups().single()
        assertEquals("aaaaaaaa-0001", group.str("keep_id"))
        assertEquals(listOf("bbbbbbbb-0002"), group.ids("trash_ids"))
    }

    @Test
    fun `--keep id of no duplicate refuses`() {
        seedTwins()

        assertEquals(1, dedupe("--keep", "id:cccccccc-0003", "--json"))

        val reply = jsonReply()
        assertEquals("false", reply.str("ok"))
        assertEquals("unknown_id", reply.str("error"))
    }

    @Test
    fun `an unknown keep rule refuses`() {
        seedTwins()

        assertEquals(1, dedupe("--keep", "oldest", "--json"))

        assertEquals("invalid_keep", jsonReply().str("error"))
    }

    @Test
    fun `a tie under the keep rule is skipped, never guessed`() {
        seedTwins(canonicalModified = newer, twinModified = newer)

        assertEquals(0, dedupe("--keep", "newest", "--json"))

        val group = jsonReply().groups().single()
        assertEquals("skip", group.str("action"))
        assertEquals("tie", group.str("reason"))
        assertNull(group.str("keep_id"))
        assertTrue(group.ids("trash_ids").isEmpty())
    }

    @Test
    fun `a member without a modification time is skipped by --keep newest`() {
        seedTwins(canonicalModified = null, twinModified = newer)

        assertEquals(0, dedupe("--keep", "newest", "--json"))

        val group = jsonReply().groups().single()
        assertEquals("skip", group.str("action"))
        assertEquals("modified_unknown", group.str("reason"))
    }

    @Test
    fun `the same stored content is reported as same, unproven content as unknown`() {
        seed(
            row("/a.txt", "aaaaaaaa-0001", contentId = "obj-1"),
            row("/a (dup-bbbbbbbb).txt", "bbbbbbbb-0002", remotePath = "/a.txt", contentId = "obj-1"),
            row("/b.txt", "cccccccc-0003", contentId = "obj-2"),
            row("/b (dup-dddddddd).txt", "dddddddd-0004", remotePath = "/b.txt", contentId = "obj-3"),
        )

        assertEquals(0, dedupe("--json"))

        val byPath = jsonReply().groups().associateBy { it.str("path") }
        assertEquals("same", byPath["/a.txt"]!!.str("content"))
        assertEquals("unknown", byPath["/b.txt"]!!.str("content"), "two stored objects of one size may still hold the same bytes")
    }

    @Test
    fun `a folder twin is listed but skipped, and the rows below it are no group of their own`() {
        seed(
            row("/photos", "aaaaaaaa-0001", isFolder = true),
            row("/photos/x.jpg", "aaaaaaaa-0011"),
            row("/photos (dup-bbbbbbbb)", "bbbbbbbb-0002", remotePath = "/photos", isFolder = true),
            row("/photos (dup-bbbbbbbb)/x.jpg", "bbbbbbbb-0012", remotePath = "/photos/x.jpg"),
        )

        assertEquals(0, dedupe("--keep", "newest", "--json"))

        val group = jsonReply().groups().single()
        assertEquals("/photos", group.str("path"))
        assertEquals("folder", group.str("kind"))
        assertEquals("skip", group.str("action"))
        assertEquals("folder", group.str("reason"))
    }

    @Test
    fun `--path keeps the groups at or below it`() {
        seed(
            row("/docs/a.txt", "aaaaaaaa-0001"),
            row("/docs/a (dup-bbbbbbbb).txt", "bbbbbbbb-0002", remotePath = "/docs/a.txt"),
            row("/docsx/c.txt", "cccccccc-0003"),
            row("/docsx/c (dup-dddddddd).txt", "dddddddd-0004", remotePath = "/docsx/c.txt"),
        )

        assertEquals(0, dedupe("--path", "/docs", "--json"))

        assertEquals(listOf("/docs/a.txt"), jsonReply().groups().map { it.str("path") }, "whole path segments only")
    }

    @Test
    fun `a profile without state has no duplicates`() {
        assertEquals(0, dedupe("--json"))

        val reply = jsonReply()
        assertEquals("true", reply.str("ok"))
        assertEquals("0", reply.str("count"))
        assertFalse(Files.exists(stateDb()), "a dry run creates no state.db")
    }

    @Test
    fun `there is no apply yet - an --apply flag is refused before anything is read`() {
        seedTwins()
        val rowsBefore = aliveRows()

        assertEquals(CommandLine.ExitCode.USAGE, dedupe("--keep", "newest", "--apply"))

        assertEquals(rowsBefore, aliveRows())
    }

    @Test
    fun `the console form says it is a dry run and what the keep rule would do`() {
        seedTwins()

        assertEquals(0, dedupe("--keep", "newest"))

        val text = output()
        assertTrue(text.contains("/docs/a.txt"), text)
        assertTrue(text.contains("keep") && text.contains("bbbbbbbb-0002"), text)
        assertTrue(text.contains("trash") && text.contains("aaaaaaaa-0001"), text)
        assertTrue(text.contains("Dry run"), text)
    }
}
