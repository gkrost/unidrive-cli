package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.sync.ProcessLock
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `unidrive dedupe --apply` moves the members a keep rule does not keep to the provider's trash, by their ids. The tests
 * drive the real command line against a temp config with a real state.db seeded with twin rows, and a fake provider that
 * stands for a drive whose delete trashes (the profile's type is `internxt`; nothing reaches a network).
 */
class DedupeApplyTest {
    private lateinit var configDir: Path
    private lateinit var scratch: Path
    private val originalOut = System.out
    private val originalErr = System.err
    private lateinit var captured: ByteArrayOutputStream
    private lateinit var capturedErr: ByteArrayOutputStream
    private val savedSocketPath = DedupeHooks.socketPath

    private val older = Instant.parse("2026-01-01T10:00:00Z")
    private val newer = Instant.parse("2026-02-01T10:00:00Z")

    private lateinit var drive: FakeDrive
    private var providersCreated = 0

    @BeforeTest
    fun setUp() {
        configDir = Files.createTempDirectory("ud-dedupe-apply-cfg")
        scratch = Files.createTempDirectory("ud-dedupe-apply-roots")
        val roots = listOf("ix", "lf", "lf-remote", "mx").associateWith { Files.createDirectories(scratch.resolve(it)) }
        Files.writeString(
            configDir.resolve("config.toml"),
            """
            |[providers.ix]
            |type = "internxt"
            |mode = "mount"
            |sync_root = "${toml(roots.getValue("ix"))}"
            |
            |[providers.lf]
            |type = "localfs"
            |mode = "mount"
            |sync_root = "${toml(roots.getValue("lf"))}"
            |root_path = "${toml(roots.getValue("lf-remote"))}"
            |
            |[providers.mx]
            |type = "internxt"
            |mode = "mirror"
            |sync_root = "${toml(roots.getValue("mx"))}"
            """.trimMargin(),
        )
        for (p in listOf("ix", "lf", "mx")) Files.createDirectories(configDir.resolve(p))
        // No real daemon socket of a profile of the same name can answer for these tests.
        DedupeHooks.socketPath = { scratch.resolve("no-daemon-$it.sock") }
        drive = FakeDrive()
        providersCreated = 0
        captured = ByteArrayOutputStream()
        capturedErr = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true, Charsets.UTF_8))
        System.setErr(PrintStream(capturedErr, true, Charsets.UTF_8))
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        DedupeHooks.socketPath = savedSocketPath
        for (dir in listOf(configDir, scratch)) runCatching { dir.toFile().deleteRecursively() }
    }

    private fun toml(p: Path) = p.toString().replace('\\', '/')

    private fun stateDb(profile: String = "ix"): Path = configDir.resolve(profile).resolve("state.db")

    private fun dedupe(
        profile: String,
        vararg args: String,
    ): Int {
        val main =
            object : Main() {
                override fun createProvider(): CloudProvider {
                    providersCreated++
                    return drive
                }
            }
        return CommandLine(main).execute("-c", configDir.toString(), "-p", profile, "dedupe", *args)
    }

    private fun jsonReply(): JsonObject = Json.parseToJsonElement(captured.toString(Charsets.UTF_8).trim().lines().last()).jsonObject

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.groups(): List<JsonObject> = this["groups"]!!.jsonArray.map { it.jsonObject }

    /** The per-member outcomes of a group: id to (outcome, reason). */
    private fun JsonObject.results(): Map<String, Pair<String?, String?>> =
        this["results"]!!.jsonArray.map { it.jsonObject }.associate { it.str("id")!! to (it.str("outcome") to it.str("reason")) }

    private fun row(
        path: String,
        id: String,
        remotePath: String? = null,
        size: Long = 5,
        modified: Instant? = older,
        isFolder: Boolean = false,
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
    )

    /** The cloud item [row] stands for, as a fresh listing of its folder gives it. */
    private fun itemOf(row: SyncEntry): CloudItem {
        val cloudPath = row.remotePath ?: row.path
        return CloudItem(
            id = row.remoteId!!,
            name = cloudPath.substringAfterLast('/'),
            path = cloudPath,
            size = row.remoteSize,
            isFolder = row.isFolder,
            modified = row.remoteModified,
            created = null,
            hash = row.remoteHash,
            mimeType = null,
        )
    }

    /** Seeds [rows] into the profile's state.db and puts the items they stand for into the drive, unchanged. */
    private fun seed(
        vararg rows: SyncEntry,
        profile: String = "ix",
    ) {
        val db = StateDatabase(stateDb(profile))
        try {
            db.initialize()
            rows.forEach { db.upsertEntry(it) }
        } finally {
            db.close()
        }
        rows.forEach { drive.put(itemOf(it)) }
    }

    private fun aliveRows(profile: String = "ix"): List<SyncEntry> {
        val db = StateDatabase(stateDb(profile), readOnly = true)
        try {
            db.initialize()
            return db.getAllEntries().sortedBy { it.path }
        } finally {
            db.close()
        }
    }

    // /docs/a.txt: the canonical holder (older) and one decorated twin (newer), plus an unrelated file.
    private fun seedTwins() =
        seed(
            row("/docs", "f0000000-0000", isFolder = true),
            row("/docs/a.txt", "aaaaaaaa-0001", modified = older),
            row("/docs/a (dup-bbbbbbbb).txt", "bbbbbbbb-0002", remotePath = "/docs/a.txt", modified = newer),
            row("/docs/other.txt", "cccccccc-0003"),
        )

    @Test
    fun `--apply --keep newest trashes exactly the older twin by id and leaves the rows to the next sync`() {
        seedTwins()
        val rowsBefore = aliveRows()

        assertEquals(0, dedupe("ix", "--keep", "newest", "--apply", "--json"))

        assertEquals(listOf("aaaaaaaa-0001"), drive.trashedIds, "the older twin, by its id")
        assertTrue(drive.pathDeletes.isEmpty(), "never a delete by name: it could reach the other twin")
        val reply = jsonReply()
        assertEquals("true", reply.str("ok"))
        assertEquals("false", reply.str("dry_run"))
        assertEquals("1", reply.str("trashed"))
        assertEquals("0", reply.str("skipped"))
        assertEquals("0", reply.str("failed"))
        val group = reply.groups().single()
        assertEquals("bbbbbbbb-0002", group.str("keep_id"))
        assertEquals(mapOf("aaaaaaaa-0001" to ("trashed" to null)), group.results())
        assertEquals(rowsBefore, aliveRows(), "the rows are the next sync's to settle")
    }

    @Test
    fun `--apply without --keep is a usage error and calls no provider`() {
        seedTwins()

        assertEquals(CommandLine.ExitCode.USAGE, dedupe("ix", "--apply", "--json"))

        assertEquals("keep_required", jsonReply().str("error"))
        assertEquals(0, providersCreated)
        assertTrue(drive.trashedIds.isEmpty())
    }

    @Test
    fun `a provider whose delete does not trash is refused`() {
        seed(
            row("/a.txt", "aaaaaaaa-0001", modified = older),
            row("/a (dup-bbbbbbbb).txt", "bbbbbbbb-0002", remotePath = "/a.txt", modified = newer),
            profile = "lf",
        )

        assertEquals(1, dedupe("lf", "--keep", "newest", "--apply", "--json"))

        val reply = jsonReply()
        assertEquals("false", reply.str("ok"))
        assertEquals("trash_unsupported", reply.str("error"))
        assertEquals(0, providersCreated)
        assertTrue(drive.trashedIds.isEmpty())
    }

    @Test
    fun `a profile a daemon holds is refused`() {
        seedTwins()
        val daemon = ProcessLock(configDir.resolve("ix").resolve(".lock"))
        assertTrue(daemon.tryLock(ProcessLock.Mode.DAEMON))
        try {
            assertEquals(1, dedupe("ix", "--keep", "newest", "--apply", "--json"))
        } finally {
            daemon.unlock()
        }

        assertEquals("daemon_running", jsonReply().str("error"))
        assertEquals(0, providersCreated)
        assertTrue(drive.trashedIds.isEmpty())
    }

    @Test
    fun `a mirror profile is refused like rm refuses it`() {
        seed(
            row("/a.txt", "aaaaaaaa-0001", modified = older),
            row("/a (dup-bbbbbbbb).txt", "bbbbbbbb-0002", remotePath = "/a.txt", modified = newer),
            profile = "mx",
        )

        assertEquals(78, dedupe("mx", "--keep", "newest", "--apply", "--json"))

        assertEquals("mirror_profile", jsonReply().str("error"))
        assertEquals(0, providersCreated)
        assertTrue(drive.trashedIds.isEmpty())
    }

    @Test
    fun `a member that changed in the cloud since the plan is skipped, the others are trashed`() {
        seed(
            row("/a.txt", "aaaaaaaa-0001", modified = newer),
            row("/a (dup-bbbbbbbb).txt", "bbbbbbbb-0002", remotePath = "/a.txt", modified = older),
            row("/a (dup-cccccccc).txt", "cccccccc-0003", remotePath = "/a.txt", modified = older),
        )
        // Edited in the cloud after the last sync: another size, time and token than its row says.
        drive.put(drive.item("bbbbbbbb-0002").copy(size = 9, modified = newer, hash = "token-new"))

        assertEquals(3, dedupe("ix", "--keep", "id:aaaaaaaa-0001", "--apply", "--json"))

        assertEquals(listOf("cccccccc-0003"), drive.trashedIds)
        val reply = jsonReply()
        assertEquals("1", reply.str("trashed"))
        assertEquals("1", reply.str("skipped"))
        assertEquals(
            mapOf("bbbbbbbb-0002" to ("skipped" to "changed"), "cccccccc-0003" to ("trashed" to null)),
            reply.groups().single().results(),
        )
    }

    @Test
    fun `a member no longer in its folder's listing is skipped`() {
        seedTwins()
        drive.remove("aaaaaaaa-0001")

        assertEquals(3, dedupe("ix", "--keep", "newest", "--apply", "--json"))

        assertTrue(drive.trashedIds.isEmpty())
        assertEquals(mapOf("aaaaaaaa-0001" to ("skipped" to "not_listed")), jsonReply().groups().single().results())
    }

    @Test
    fun `the kept member is never touched, and when it changed nothing of its set is trashed`() {
        seedTwins()
        drive.put(drive.item("bbbbbbbb-0002").copy(size = 7))

        assertEquals(3, dedupe("ix", "--keep", "newest", "--apply", "--json"))

        assertTrue(drive.trashedIds.isEmpty(), "with the keeper changed, trashing the other could leave the wrong copy")
        val group = jsonReply().groups().single()
        assertEquals("bbbbbbbb-0002", group.str("keep_id"))
        assertEquals(mapOf("aaaaaaaa-0001" to ("skipped" to "keep_changed")), group.results())
    }

    @Test
    fun `a folder set is listed and skipped, and no provider is asked`() {
        seed(
            row("/photos", "aaaaaaaa-0001", isFolder = true, modified = older),
            row("/photos (dup-bbbbbbbb)", "bbbbbbbb-0002", remotePath = "/photos", isFolder = true, modified = newer),
        )

        assertEquals(0, dedupe("ix", "--keep", "newest", "--apply", "--json"))

        val group = jsonReply().groups().single()
        assertEquals("skip", group.str("action"))
        assertEquals("folder", group.str("reason"))
        assertEquals(0, providersCreated)
        assertTrue(drive.trashedIds.isEmpty())
    }

    @Test
    fun `a failed delete is reported and the other sets are still done`() {
        seed(
            row("/a.txt", "aaaaaaaa-0001", modified = newer),
            row("/a (dup-bbbbbbbb).txt", "bbbbbbbb-0002", remotePath = "/a.txt", modified = older),
            row("/b.txt", "cccccccc-0003", modified = newer),
            row("/b (dup-dddddddd).txt", "dddddddd-0004", remotePath = "/b.txt", modified = older),
        )
        drive.failingIds += "bbbbbbbb-0002"

        assertEquals(4, dedupe("ix", "--keep", "newest", "--apply", "--json"))

        assertEquals(listOf("dddddddd-0004"), drive.trashedIds)
        val reply = jsonReply()
        assertEquals("1", reply.str("trashed"))
        assertEquals("1", reply.str("failed"))
        val byPath = reply.groups().associateBy { it.str("path") }
        assertEquals("failed" to "delete_failed", byPath["/a.txt"]!!.results()["bbbbbbbb-0002"])
        assertEquals("trashed" to null, byPath["/b.txt"]!!.results()["dddddddd-0004"])
    }

    @Test
    fun `the console form says what was trashed and what was skipped`() {
        seed(
            row("/a.txt", "aaaaaaaa-0001", modified = newer),
            row("/a (dup-bbbbbbbb).txt", "bbbbbbbb-0002", remotePath = "/a.txt", modified = older),
            row("/a (dup-cccccccc).txt", "cccccccc-0003", remotePath = "/a.txt", modified = older),
        )
        drive.put(drive.item("cccccccc-0003").copy(size = 9))

        assertEquals(3, dedupe("ix", "--keep", "id:aaaaaaaa-0001", "--apply"))

        val text = captured.toString(Charsets.UTF_8)
        assertTrue(text.contains("trashed") && text.contains("bbbbbbbb-0002"), text)
        assertTrue(text.contains("skipped") && text.contains("cccccccc-0003"), text)
        assertFalse(text.contains("Dry run"), text)
    }

    /** A drive whose delete by id moves the item to its trash; a delete by name is never expected. */
    private class FakeDrive : CloudProvider {
        private val items = linkedMapOf<String, CloudItem>()
        val trashedIds = mutableListOf<String>()
        val pathDeletes = mutableListOf<String>()
        val failingIds = mutableSetOf<String>()

        fun put(item: CloudItem) {
            items[item.id] = item
        }

        fun item(id: String): CloudItem = items.getValue(id)

        fun remove(id: String) {
            items.remove(id)
        }

        override val id = "internxt"
        override val displayName = "Fake trash drive"
        override var isAuthenticated = true

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String): List<CloudItem> =
            items.values.filter { it.path.substringBeforeLast('/', "").ifEmpty { "/" } == path }

        override suspend fun getMetadata(path: String): CloudItem = error("dedupe re-lists the folder, it does not resolve a name")

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = error("dedupe never downloads")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("dedupe never uploads")

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) {
            pathDeletes += remotePath
        }

        override suspend fun deleteById(
            remoteId: String,
            remotePath: String,
        ) {
            if (remoteId in failingIds) throw IOException("the drive answered 500")
            items.remove(remoteId)
            trashedIds += remoteId
        }

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem = error("dedupe never moves")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage = error("not used")

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }
}
