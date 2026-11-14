package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.TransientNetworkException
import org.krost.unidrive.TrashEmptyOutcome
import org.krost.unidrive.TrashItem
import org.krost.unidrive.io.formatSize
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `trash list --remote --json` and `trash purge --remote --all --json`: one JSON object on stdout for a front-end, in
 * the lifecycle refusal shape when anything is refused, while the console output without `--json` stays as it was.
 * The provider is a fake behind `Main.createProvider`; the unsupported case runs the real localfs provider.
 */
class TrashRemoteJsonTest {
    private lateinit var configDir: Path
    private lateinit var remoteRoot: Path
    private lateinit var syncRoot: Path
    private val originalOut = System.out
    private val originalErr = System.err
    private lateinit var out: ByteArrayOutputStream
    private lateinit var err: ByteArrayOutputStream

    @BeforeTest
    fun setUp() {
        configDir = Files.createTempDirectory("ud-trash-json-cfg")
        remoteRoot = Files.createTempDirectory("ud-trash-json-remote")
        syncRoot = Files.createTempDirectory("ud-trash-json-local")
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
        out = ByteArrayOutputStream()
        err = ByteArrayOutputStream()
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        for (dir in listOf(configDir, remoteRoot, syncRoot)) runCatching { dir.toFile().deleteRecursively() }
    }

    private fun toml(p: Path) = p.toString().replace('\\', '/')

    /** A provider with a trash; each failure knob throws from the step it names. */
    private class TrashProvider(
        val items: List<TrashItem> = emptyList(),
        val outcome: TrashEmptyOutcome = TrashEmptyOutcome.Completed,
        val remoteTrash: Boolean = true,
    ) : CloudProvider {
        override val id = "fake-trash"
        override val displayName = "Fake (trash json test)"
        override var isAuthenticated = true
        var authFailure: Exception? = null
        var listFailure: Exception? = null
        var emptied = 0
        var closed = false

        override fun close() {
            closed = true
        }

        override fun capabilities(): Set<Capability> = if (remoteTrash) setOf(Capability.RemoteTrash) else emptySet()

        override suspend fun authenticate() {
            authFailure?.let { throw it }
        }

        override suspend fun listTrash(): List<TrashItem> {
            listFailure?.let { throw it }
            return items
        }

        override suspend fun emptyTrash(): TrashEmptyOutcome {
            emptied++
            return outcome
        }

        override suspend fun quota(): QuotaInfo = error("not used")
        override suspend fun listChildren(path: String): List<CloudItem> = error("not used")
        override suspend fun getMetadata(path: String): CloudItem = error("not used")
        override suspend fun download(remotePath: String, destination: Path): Long = error("not used")
        override suspend fun downloadById(remoteId: String, remotePath: String, destination: Path): Long = error("not used")
        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used")
        override suspend fun delete(remotePath: String, ifMatchETag: String?) = error("not used")
        override suspend fun createFolder(path: String): CloudItem = error("not used")
        override suspend fun move(fromPath: String, toPath: String): CloudItem = error("not used")
        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: ScanContext?,
        ): DeltaPage = error("not used")
    }

    private val sample =
        listOf(
            TrashItem(id = "uuid-a", isFolder = false, name = "a.txt", sizeBytes = 100, deletedAtMs = 1_700_000_000_000),
            TrashItem(id = "uuid-b", isFolder = true, name = "dir", sizeBytes = null, deletedAtMs = null),
            TrashItem(id = "uuid-c", isFolder = false, name = "c.bin", sizeBytes = 2048, deletedAtMs = null),
            TrashItem(id = "uuid-d", isFolder = false, name = "d.dat", sizeBytes = null, deletedAtMs = 1_700_000_100_000),
        )

    private fun run(
        provider: CloudProvider?,
        vararg args: String,
    ): Int {
        val main =
            if (provider == null) {
                Main()
            } else {
                object : Main() {
                    override fun createProvider(): CloudProvider = provider
                }
            }
        return CommandLine(main)
            .also { it.executionExceptionHandler = cliExecutionExceptionHandler }
            .execute("-c", configDir.toString(), "-p", "localtest", "trash", *args)
    }

    private fun stdout(): String = out.toString(Charsets.UTF_8)

    private fun stderr(): String = err.toString(Charsets.UTF_8)

    /** The whole of stdout is exactly one JSON object. */
    private fun reply(): JsonObject {
        val lines = stdout().trim().lines()
        assertEquals(1, lines.size, "stdout carries one line: ${stdout()}")
        return Json.parseToJsonElement(lines.single()).jsonObject
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    // ── trash list --remote --json ─────────────────────────────────────────────

    @Test
    fun `list --remote --json answers the items, the count and the bytes of the known file sizes`() {
        val provider = TrashProvider(sample)
        assertEquals(0, run(provider, "list", "--remote", "--json"))
        val reply = reply()
        assertEquals("4", reply.str("count"))
        assertEquals("2148", reply.str("file_bytes"), "the folder and the file of unknown size add nothing")
        val items = reply["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("uuid-a", "uuid-b", "uuid-c", "uuid-d"), items.map { it.str("id") })
        assertEquals(listOf("file", "folder", "file", "file"), items.map { it.str("kind") })
        assertEquals(listOf("a.txt", "dir", "c.bin", "d.dat"), items.map { it.str("name") })
        assertEquals("100", items[0].str("size_bytes"))
        assertEquals("1700000000000", items[0].str("deleted_at_ms"))
        assertEquals(JsonNull, items[1]["size_bytes"], "unknown size is null")
        assertEquals(JsonNull, items[1]["deleted_at_ms"], "unknown deletion time is null")
        assertEquals(JsonNull, items[3]["size_bytes"])
        assertEquals(setOf("id", "kind", "name", "size_bytes", "deleted_at_ms"), items[0].keys)
        assertTrue(provider.closed, "the provider is closed")
    }

    @Test
    fun `list --remote --json of an empty trash is an empty list`() {
        assertEquals(0, run(TrashProvider(emptyList()), "list", "--remote", "--json"))
        val reply = reply()
        assertEquals(JsonArray(emptyList()), reply["items"])
        assertEquals("0", reply.str("count"))
        assertEquals("0", reply.str("file_bytes"))
    }

    @Test
    fun `list --remote --json on a provider without a remote trash is remote_trash_unsupported`() {
        // The real localfs provider: it declares no RemoteTrash.
        assertEquals(1, run(null, "list", "--remote", "--json"))
        val reply = reply()
        assertEquals("false", reply.str("ok"))
        assertEquals("remote_trash_unsupported", reply.str("error"))
        assertTrue(reply.str("message")!!.isNotBlank())
    }

    @Test
    fun `list --remote --json when not signed in is auth_required`() {
        val provider = TrashProvider(sample).apply { authFailure = AuthenticationException("expired") }
        assertEquals(1, run(provider, "list", "--remote", "--json"))
        assertEquals("auth_required", reply().str("error"))
    }

    @Test
    fun `list --remote --json without a network is network_error`() {
        val provider = TrashProvider(sample).apply { listFailure = TransientNetworkException("connection reset") }
        assertEquals(1, run(provider, "list", "--remote", "--json"))
        assertEquals("network_error", reply().str("error"))
    }

    @Test
    fun `list --remote --json with a raw I-O failure is network_error too`() {
        val provider = TrashProvider(sample).apply { listFailure = IOException("unreachable") }
        assertEquals(1, run(provider, "list", "--remote", "--json"))
        assertEquals("network_error", reply().str("error"))
    }

    @Test
    fun `list --remote --json with a provider answer is remote_trash_failed`() {
        val provider = TrashProvider(sample).apply { listFailure = ProviderException("server error", httpStatus = 500) }
        assertEquals(1, run(provider, "list", "--remote", "--json"))
        assertEquals("remote_trash_failed", reply().str("error"))
    }

    @Test
    fun `list --remote --json with an unexpected failure is internal_error worded by type`() {
        val provider = TrashProvider(sample).apply { listFailure = IllegalStateException("secret-ish detail") }
        assertEquals(1, run(provider, "list", "--remote", "--json"))
        val reply = reply()
        assertEquals("internal_error", reply.str("error"))
        assertFalse(reply.str("message")!!.contains("secret-ish"), "the exception's message is not quoted")
    }

    @Test
    fun `list --remote --json for a profile that is not configured is profile_not_found`() {
        val exit =
            CommandLine(Main()).execute("-c", configDir.toString(), "-p", "nosuch", "trash", "list", "--remote", "--json")
        assertEquals(1, exit)
        assertEquals("profile_not_found", reply().str("error"))
    }

    @Test
    fun `list --json without --remote is a usage error`() {
        assertEquals(2, run(TrashProvider(sample), "list", "--json"))
        assertTrue(stderr().contains("pass --remote"), stderr())
    }

    // ── trash purge --remote --all --json ──────────────────────────────────────

    @Test
    fun `purge --remote --all --yes --json answers emptied`() {
        val provider = TrashProvider(sample, TrashEmptyOutcome.Completed)
        assertEquals(0, run(provider, "purge", "--remote", "--all", "--yes", "--json"))
        val reply = reply()
        assertEquals("true", reply.str("ok"))
        assertEquals("emptied", reply.str("result"))
        assertEquals("4", reply.str("count"))
        assertEquals(1, provider.emptied)
    }

    @Test
    fun `purge --remote --all --yes --json answers partial with the remaining items when the provider only queued it`() {
        val provider = TrashProvider(sample, TrashEmptyOutcome.Queued)
        assertEquals(0, run(provider, "purge", "--remote", "--all", "--yes", "--json"))
        val reply = reply()
        assertEquals("true", reply.str("ok"))
        assertEquals("partial", reply.str("result"))
        assertEquals("4", reply.str("remaining"))
        assertEquals("true", reply.str("queued"))
    }

    @Test
    fun `purge --remote --all --json without --yes is confirmation_required and empties nothing`() {
        val provider = TrashProvider(sample)
        assertEquals(1, run(provider, "purge", "--remote", "--all", "--json"))
        val reply = reply()
        assertEquals("false", reply.str("ok"))
        assertEquals("confirmation_required", reply.str("error"))
        assertEquals(0, provider.emptied)
    }

    @Test
    fun `purge --remote --all --yes --json on a provider without a remote trash is remote_trash_unsupported`() {
        assertEquals(1, run(null, "purge", "--remote", "--all", "--yes", "--json"))
        assertEquals("remote_trash_unsupported", reply().str("error"))
    }

    @Test
    fun `purge --remote --all --yes --json on a fake without the capability is remote_trash_unsupported and empties nothing`() {
        val provider = TrashProvider(sample, remoteTrash = false)
        assertEquals(1, run(provider, "purge", "--remote", "--all", "--yes", "--json"))
        assertEquals("remote_trash_unsupported", reply().str("error"))
        assertEquals(0, provider.emptied)
    }

    @Test
    fun `purge --remote --all --yes --json when not signed in is auth_required`() {
        val provider = TrashProvider(sample).apply { authFailure = AuthenticationException("expired") }
        assertEquals(1, run(provider, "purge", "--remote", "--all", "--yes", "--json"))
        assertEquals("auth_required", reply().str("error"))
        assertEquals(0, provider.emptied)
    }

    @Test
    fun `purge --remote --all --yes --json without a network is network_error`() {
        val provider = TrashProvider(sample).apply { listFailure = TransientNetworkException("timeout") }
        assertEquals(1, run(provider, "purge", "--remote", "--all", "--yes", "--json"))
        assertEquals("network_error", reply().str("error"))
    }

    @Test
    fun `purge --remote --id --yes --json of an unknown id is not_in_trash`() {
        val provider = TrashProvider(sample)
        assertEquals(1, run(provider, "purge", "--remote", "--id", "uuid-x", "--yes", "--json"))
        assertEquals("not_in_trash", reply().str("error"))
    }

    @Test
    fun `purge --json without --remote is a usage error`() {
        assertEquals(2, run(TrashProvider(sample), "purge", "--all", "--json"))
        assertTrue(stderr().contains("pass --remote"), stderr())
    }

    // ── the console output without --json is unchanged ─────────────────────────

    @Test
    fun `list --remote without --json prints the table as before`() {
        assertEquals(0, run(TrashProvider(sample), "list", "--remote"))
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        fun at(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(fmt)
        val row = "%-6s  %-10s  %-25s  %-38s  %s"
        val expected =
            listOf(
                row.format("Kind", "Size", "Deleted", "Id", "Name"),
                "-".repeat(100),
                row.format("file", formatSize(100), at(1_700_000_000_000), "uuid-a", "a.txt"),
                row.format("folder", "-", "-", "uuid-b", "dir"),
                row.format("file", formatSize(2048), "-", "uuid-c", "c.bin"),
                row.format("file", "-", at(1_700_000_100_000), "uuid-d", "d.dat"),
                // The command starts the summary line with a bare line feed, not the platform separator.
                "\n4 item(s), ${formatSize(2148)} in the files",
            ).joinToString(System.lineSeparator()) + System.lineSeparator()
        assertEquals(expected, stdout())
    }

    @Test
    fun `list --remote without --json of an empty trash says so`() {
        assertEquals(0, run(TrashProvider(emptyList()), "list", "--remote"))
        assertEquals("Remote trash is empty." + System.lineSeparator(), stdout())
    }

    @Test
    fun `purge --remote --all --yes without --json prints the line as before`() {
        assertEquals(0, run(TrashProvider(sample, TrashEmptyOutcome.Completed), "purge", "--remote", "--all", "--yes"))
        assertEquals("Remote trash emptied (4 item(s))." + System.lineSeparator(), stdout())
    }

    @Test
    fun `purge --remote --all --yes without --json reports a queued deletion as before`() {
        assertEquals(0, run(TrashProvider(sample, TrashEmptyOutcome.Queued), "purge", "--remote", "--all", "--yes"))
        assertEquals(
            "Remote trash deletion queued (4 item(s) at listing time); " +
                "the provider deletes in the background and the quota follows when it finishes." + System.lineSeparator(),
            stdout(),
        )
    }

    @Test
    fun `purge --remote --all without --yes and without a console is refused on stderr as before`() {
        val provider = TrashProvider(sample)
        assertEquals(1, run(provider, "purge", "--remote", "--all"))
        assertEquals("", stdout())
        assertTrue(stderr().contains("Error: purging the remote trash needs a confirmation"), stderr())
        assertEquals(0, provider.emptied)
    }

    @Test
    fun `list --remote without --json on a provider without a remote trash is refused on stderr as before`() {
        assertEquals(1, run(null, "list", "--remote"))
        assertEquals("", stdout())
        assertTrue(stderr().contains("Error: the provider of this profile does not expose its trash"), stderr())
    }
}
