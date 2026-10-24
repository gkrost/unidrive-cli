package org.krost.unidrive.cli

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.Capability
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ViewKind
import org.krost.unidrive.ViewRef
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.ProfileMode
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `view.page` on a live mount daemon whose profile shows the Recents view as the config asks for it when it does not say
 * (the newest 50 in the folder) over a provider with 120 recent files, all of them with a row. A read-scope connection pages
 * through them in steps of 50; the provider is asked once per distinct size within the time to live, and the folder keeps
 * its 50. Nothing here reaches a real server.
 */
class RecentsPagingDaemonTest {
    private lateinit var tempDir: Path
    private lateinit var socketPath: Path

    private val ids = (1..120).map { "%08d-file".format(it) }

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("recents-paging-daemon-test")
        socketPath = tempDir.resolve("daemon.sock")
    }

    @AfterTest
    fun tearDown() {
        runCatching { tempDir.toFile().deleteRecursively() }
    }

    private class RecentsStub(
        private val recents: List<ViewRef>,
    ) : CloudProvider {
        override val id: String = "stub"
        override val displayName: String = "Stub"
        override var isAuthenticated: Boolean = true

        // The limit of every question about the Recents view, in order.
        val sizes = CopyOnWriteArrayList<Int>()

        override fun capabilities(): Set<Capability> = setOf(Capability.View(ViewKind.RECENTS))

        override suspend fun listView(
            kind: ViewKind,
            limit: Int,
        ): CapabilityResult<List<ViewRef>> {
            if (kind != ViewKind.RECENTS) return CapabilityResult.Unsupported(Capability.View(kind), "the stub has no $kind view")
            sizes += limit
            return CapabilityResult.Success(recents.take(limit))
        }

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem = error("not used")

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = error("not used")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used")

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) = error("not used")

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem = error("not used")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage = DeltaPage(items = emptyList(), cursor = "x", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    // One folder with the 120 files the stub calls recent.
    private fun seedRows() {
        fun row(
            path: String,
            id: String,
            folder: Boolean,
        ) = SyncEntry(
            path = path,
            remoteId = id,
            remoteHash = null,
            remoteSize = if (folder) 0 else 5,
            remoteModified = Instant.ofEpochMilli(1_234_567_890_000L),
            localMtime = null,
            localSize = null,
            isFolder = folder,
            isPinned = false,
            isHydrated = false,
            lastSynced = Instant.ofEpochMilli(1_234_567_890_123L),
        )
        val db = StateDatabase(tempDir.resolve("state.db"))
        db.initialize()
        try {
            db.upsertEntry(row("/Work", "rid-work", folder = true))
            ids.forEach { db.upsertEntry(row("/Work/$it.txt", it, folder = false)) }
        } finally {
            db.close()
        }
    }

    private suspend fun ask(
        request: String,
        scope: IpcAuth.Scope = IpcAuth.Scope.READ,
    ): JsonObject {
        val channel = IpcAuthClient.connect(IpcEndpoint(socketPath, tempDir, "recents_paging_profile"), scope)
        try {
            channel.configureBlocking(false)
            channel.write(ByteBuffer.wrap((request + "\n").toByteArray()))
            return Json.parseToJsonElement(awaitIpcLine(channel, request) { true }).jsonObject
        } finally {
            channel.close()
        }
    }

    private fun paths(reply: JsonObject): List<String> = reply.getValue("entries").jsonArray.map { it.jsonObject.getValue("path").jsonPrimitive.content }

    private fun runDaemon(
        provider: RecentsStub,
        body: suspend () -> Unit,
    ) = runBlocking {
        seedRows()
        val config = SyncConfig.parse("[providers.p]\ntype = \"internxt\"\nmode = \"mount\"\nshared_view = false\nfavorites_view = false\n", "p")
        val runtime =
            DaemonRuntime(
                profileMode = ProfileMode.MOUNT,
                profileName = "recents_paging_profile",
                lockFile = tempDir.resolve(".lock"),
                dbPath = tempDir.resolve("state.db"),
                syncRoot = tempDir,
                socketPath = socketPath,
                providerFactory = { provider },
                providerHasQuota = false,
                views = ConfiguredViews.of(config, "p"),
            )
        val daemonJob = launch { runtime.start() }
        try {
            awaitDaemonSocket(socketPath, daemonJob)
            body()
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `a read connection pages through 120 recent files fifty at a time, one request per distinct size`() {
        val provider = RecentsStub(ids.map { ViewRef(it, false) })

        runDaemon(provider) {
            val first = ask("""{"verb":"view.page","kind":"recents"}""")
            assertEquals(ids.take(50).map { "/Work/$it.txt" }, paths(first), "no offset and no limit: the newest fifty, the real rows")
            assertEquals(50, first.getValue("next_offset").jsonPrimitive.content.toInt())
            assertFalse("end" in first)

            val second = ask("""{"verb":"view.page","kind":"recents","offset":50,"limit":50}""")
            assertEquals(ids.subList(50, 100).map { "/Work/$it.txt" }, paths(second))
            assertEquals(100, second.getValue("next_offset").jsonPrimitive.content.toInt())

            val third = ask("""{"verb":"view.page","kind":"recents","offset":100,"limit":50}""")
            assertEquals(ids.subList(100, 120).map { "/Work/$it.txt" }, paths(third))
            assertTrue(third.getValue("end").jsonPrimitive.content.toBoolean(), "fewer than asked for: the list ends")
            assertFalse("next_offset" in third)
            assertEquals("ok", third.getValue("state").jsonPrimitive.content)

            assertEquals(listOf(50, 100, 150), provider.sizes.toList(), "one request per distinct size")
            ask("""{"verb":"view.page","kind":"recents"}""")
            ask("""{"verb":"view.page","kind":"recents","offset":50,"limit":50}""")
            ask("""{"verb":"view.page","kind":"recents","offset":100,"limit":50}""")
            assertEquals(3, provider.sizes.size, "the same pages within the time to live ask nothing")

            assertEquals(50, paths(ask("""{"verb":"hydration.list","prefix":"/Recents"}""")).size, "the folder keeps its fifty")
            assertEquals(3, provider.sizes.size)

            val caps = ask("""{"verb":"daemon.status"}""").getValue("capabilities").jsonArray.map { it.jsonPrimitive.content }
            assertTrue("view_page" in caps, "the daemon says it pages: $caps")
        }
    }

    @Test
    fun `a read connection may page but may not write, and a bad request is refused before the provider is asked`() {
        val provider = RecentsStub(ids.map { ViewRef(it, false) })

        runDaemon(provider) {
            val refused = ask("""{"verb":"hydration.unlink","path":"/Work/${ids[0]}.txt"}""")
            assertEquals("forbidden", refused.getValue("error").jsonPrimitive.content, "a write verb stays out of the read scope")

            assertEquals("invalid_limit", ask("""{"verb":"view.page","kind":"recents","limit":0}""").getValue("error").jsonPrimitive.content)
            assertEquals("invalid_offset", ask("""{"verb":"view.page","kind":"recents","offset":-5}""").getValue("error").jsonPrimitive.content)
            assertEquals("unknown_view", ask("""{"verb":"view.page","kind":"shared"}""").getValue("error").jsonPrimitive.content)
            assertEquals(emptyList(), provider.sizes.toList(), "none of these asked the provider")

            assertEquals(50, paths(ask("""{"verb":"view.page","kind":"recents"}""")).size)
        }
    }
}
