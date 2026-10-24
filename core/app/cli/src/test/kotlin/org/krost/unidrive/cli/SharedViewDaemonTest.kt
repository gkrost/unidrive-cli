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
import org.krost.unidrive.engine.ViewSpec
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Shared view as a profile's config asks for it: `shared_view` and `shared_folder_name` decide which views the daemon is
 * given ([ConfiguredViews]), and a daemon given none shows no folder and makes no request for one. A view the provider does not
 * declare is not shown either. The provider is a stub that counts the questions it is asked about a view; nothing here reaches
 * a real server.
 */
class SharedViewDaemonTest {
    private lateinit var tempDir: Path
    private lateinit var socketPath: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("shared-view-daemon-test")
        socketPath = tempDir.resolve("daemon.sock")
    }

    @AfterTest
    fun tearDown() {
        runCatching { tempDir.toFile().deleteRecursively() }
    }

    private fun config(keys: String): SyncConfig = SyncConfig.parse("[providers.p]\ntype = \"internxt\"\nmode = \"mount\"\n$keys", "p")

    // ---- which views the config asks for ---------------------------------------------------------------------------

    @Test
    fun `a profile that does not say asks for the Shared view while the default is on`() {
        assertTrue(SyncConfig.DEFAULT_SHARED_VIEW, "the default is the one decision these two tests stand on")

        assertEquals(listOf(ViewSpec(ViewKind.SHARED_BY_ME, null, ConfiguredViews.SHARED_LIMIT)), ConfiguredViews.of(config("recents_view = false\nfavorites_view = false\n"), "p"))
        assertEquals(
            listOf(ViewSpec(ViewKind.SHARED_BY_ME, "Geteilt", ConfiguredViews.SHARED_LIMIT)),
            ConfiguredViews.of(config("recents_view = false\nfavorites_view = false\nshared_folder_name = \"Geteilt\"\n"), "p"),
            "the name is the one asked for",
        )
    }

    @Test
    fun `shared_view false asks for no view, whatever else is set`() {
        assertEquals(emptyList(), ConfiguredViews.of(config("shared_view = false\nrecents_view = false\nfavorites_view = false\nshared_folder_name = \"Geteilt\"\n"), "p"))
    }

    @Test
    fun `shared_view true asks for the Shared view under the configured name, or the default one`() {
        assertEquals(
            listOf(ViewSpec(ViewKind.SHARED_BY_ME, "Geteilt", ConfiguredViews.SHARED_LIMIT)),
            ConfiguredViews.of(config("shared_view = true\nrecents_view = false\nfavorites_view = false\nshared_folder_name = \"Geteilt\"\n"), "p"),
        )
        assertEquals(
            listOf(ViewSpec(ViewKind.SHARED_BY_ME, null, ConfiguredViews.SHARED_LIMIT)),
            ConfiguredViews.of(config("shared_view = true\nrecents_view = false\nfavorites_view = false\n"), "p"),
            "no name: the engine's own",
        )
    }

    @Test
    fun `a profile that does not say asks for the Recents view too, fifty files long and paging to the most the server answers, and the keys change it`() {
        assertEquals(
            listOf(
                ViewSpec(ViewKind.RECENTS, null, 50, pageCap = SyncConfig.MAX_RECENTS_LIMIT),
                ViewSpec(ViewKind.FAVORITES, null, ConfiguredViews.FAVORITES_LIMIT),
                ViewSpec(ViewKind.SHARED_BY_ME, null, ConfiguredViews.SHARED_LIMIT),
            ),
            ConfiguredViews.of(config(""), "p"),
        )
        assertEquals(
            listOf(ViewSpec(ViewKind.RECENTS, "Zuletzt", 20, pageCap = SyncConfig.MAX_RECENTS_LIMIT)),
            ConfiguredViews.of(config("shared_view = false\nfavorites_view = false\nrecents_folder_name = \"Zuletzt\"\nrecents_limit = 20\n"), "p"),
        )
        assertEquals(emptyList(), ConfiguredViews.of(config("shared_view = false\nrecents_view = false\nfavorites_view = false\n"), "p"))
    }

    @Test
    fun `the Favorites view is asked for while the default is on, under the configured name, and favorites_view false drops it`() {
        val only = "shared_view = false\nrecents_view = false\n"
        assertEquals(
            listOf(ViewSpec(ViewKind.FAVORITES, null, ConfiguredViews.FAVORITES_LIMIT)),
            ConfiguredViews.of(config(only), "p"),
        )
        assertEquals(
            listOf(ViewSpec(ViewKind.FAVORITES, "Favoriten", ConfiguredViews.FAVORITES_LIMIT)),
            ConfiguredViews.of(config(only + "favorites_folder_name = \"Favoriten\"\n"), "p"),
        )
        assertEquals(emptyList(), ConfiguredViews.of(config(only + "favorites_view = false\n"), "p"))
    }

    @Test
    fun `the views of one profile are not the views of another`() {
        val both = SyncConfig.parse("[providers.a]\ntype = \"internxt\"\n[providers.b]\ntype = \"internxt\"\nshared_view = false\n", "a")

        assertEquals(3, ConfiguredViews.of(both, "a").size)
        assertEquals(listOf(ViewKind.RECENTS, ViewKind.FAVORITES), ConfiguredViews.of(both, "b").map { it.kind })
    }

    // ---- a daemon given what the config asks for -------------------------------------------------------------------

    private class SharedStub(
        private val shared: List<ViewRef>,
        private val declaresShared: Boolean = true,
    ) : CloudProvider {
        override val id: String = "stub"
        override val displayName: String = "Stub"
        override var isAuthenticated: Boolean = true

        // Every question about a view of this provider, whatever the kind.
        val viewRequests = AtomicInteger(0)

        override fun capabilities(): Set<Capability> =
            if (declaresShared) setOf(Capability.View(ViewKind.SHARED_BY_ME)) else emptySet()

        override suspend fun listView(
            kind: ViewKind,
            limit: Int,
        ): CapabilityResult<List<ViewRef>> {
            viewRequests.incrementAndGet()
            return if (kind == ViewKind.SHARED_BY_ME) {
                CapabilityResult.Success(shared.take(limit))
            } else {
                CapabilityResult.Unsupported(Capability.View(kind), "the stub has no $kind view")
            }
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

    // One folder and one file below it, the file being what the stub says the account shares.
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
            db.upsertEntry(row("/Work/report.pdf", "11111111-report", folder = false))
        } finally {
            db.close()
        }
    }

    private suspend fun ask(request: String): JsonObject {
        val channel = IpcAuthClient.connect(IpcEndpoint(socketPath, tempDir, "shared_view_profile"), IpcAuth.Scope.FULL)
        try {
            channel.configureBlocking(false)
            channel.write(ByteBuffer.wrap((request + "\n").toByteArray()))
            return Json.parseToJsonElement(awaitIpcLine(channel, request) { true }).jsonObject
        } finally {
            channel.close()
        }
    }

    private fun paths(reply: JsonObject): List<String> = reply.getValue("entries").jsonArray.map { it.jsonObject.getValue("path").jsonPrimitive.content }

    private fun runDaemonWith(
        config: SyncConfig,
        provider: SharedStub,
        body: suspend () -> Unit,
    ) = runBlocking {
        seedRows()
        val runtime =
            DaemonRuntime(
                profileMode = ProfileMode.MOUNT,
                profileName = "shared_view_profile",
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
    fun `a daemon whose profile turns shared_view off shows no folder and makes no request`() {
        val provider = SharedStub(listOf(ViewRef("11111111-report", false)))

        runDaemonWith(config("shared_view = false\n"), provider) {
            assertEquals(listOf("/Work"), paths(ask("""{"verb":"hydration.list","prefix":"/"}""")), "only the real folder")
            assertEquals(emptyList(), paths(ask("""{"verb":"hydration.list","prefix":"/Shared"}""")), "and /Shared is no view")
            ask("""{"verb":"hydration.list","prefix":"/Work"}""")

            val status = ask("""{"verb":"daemon.status"}""")
            assertFalse("views" in status, "no views object")
            assertFalse(status.getValue("capabilities").jsonArray.any { it.jsonPrimitive.content == "views" }, "and no views capability")
            assertEquals(0, provider.viewRequests.get(), "the provider was asked nothing about a view")
        }
    }

    @Test
    fun `a daemon whose profile turns shared_view on shows the folder under its name and asks once per time to live`() {
        val provider = SharedStub(listOf(ViewRef("11111111-report", false), ViewRef("99999999-gone", false)))

        runDaemonWith(config("shared_view = true\nshared_folder_name = \"Geteilt\"\n"), provider) {
            val top = ask("""{"verb":"hydration.list","prefix":"/"}""")
            assertEquals(listOf("/Work", "/Geteilt"), paths(top))
            val root = top.getValue("entries").jsonArray.map { it.jsonObject }.single { it.getValue("path").jsonPrimitive.content == "/Geteilt" }
            assertEquals("shared", root.getValue("view").jsonPrimitive.content)
            assertEquals("unidrive:view:shared", root.getValue("remote_id").jsonPrimitive.content)
            assertTrue(root.getValue("read_only").jsonPrimitive.content.toBoolean())
            assertEquals(0, provider.viewRequests.get(), "listing the top level asks the provider for nothing")

            val listed = ask("""{"verb":"hydration.list","prefix":"/Geteilt"}""")
            assertEquals(listOf("/Geteilt/report.pdf"), paths(listed), "the file that has a row; the reference without one is skipped")
            assertEquals("/Work/report.pdf", listed.getValue("entries").jsonArray.single().jsonObject.getValue("view_target").jsonPrimitive.content)
            ask("""{"verb":"hydration.list","prefix":"/Geteilt"}""")
            assertEquals(1, provider.viewRequests.get(), "the second listing is answered from the list the first one fetched")

            val view = ask("""{"verb":"daemon.status"}""").getValue("views").jsonArray.single().jsonObject
            assertEquals("shared", view.getValue("kind").jsonPrimitive.content)
            assertEquals("/Geteilt", view.getValue("root_path").jsonPrimitive.content)
            assertEquals("Geteilt", view.getValue("configured_name").jsonPrimitive.content)
            assertEquals(1, view.getValue("entries").jsonPrimitive.content.toInt())
            assertEquals(1, view.getValue("skipped").jsonPrimitive.content.toInt())
            assertEquals("ok", view.getValue("state").jsonPrimitive.content)
        }
    }

    // With shared_view unset, a provider that does not declare the Shared view shows no Shared root and no views capability.
    @Test
    fun `a daemon whose provider does not declare the Shared view shows no folder when the profile does not say`() {
        val provider = SharedStub(listOf(ViewRef("11111111-report", false)), declaresShared = false)

        runDaemonWith(config(""), provider) {
            assertEquals(listOf("/Work"), paths(ask("""{"verb":"hydration.list","prefix":"/"}""")), "only the real folder")
            assertEquals(emptyList(), paths(ask("""{"verb":"hydration.list","prefix":"/Shared"}""")), "and /Shared is no view")
            val status = ask("""{"verb":"daemon.status"}""")
            assertFalse("views" in status, "no views object")
            assertFalse(status.getValue("capabilities").jsonArray.any { it.jsonPrimitive.content == "views" }, "and no views capability")
            assertEquals(0, provider.viewRequests.get(), "the provider was asked nothing about a view")
        }
    }

    @Test
    fun `a daemon whose profile does not say shows the Shared folder under the default name`() {
        val provider = SharedStub(listOf(ViewRef("11111111-report", false)))

        runDaemonWith(config(""), provider) {
            assertEquals(listOf("/Work", "/Shared"), paths(ask("""{"verb":"hydration.list","prefix":"/"}""")))
            assertEquals(listOf("/Shared/report.pdf"), paths(ask("""{"verb":"hydration.list","prefix":"/Shared"}""")))
            assertTrue(ask("""{"verb":"daemon.status"}""").getValue("capabilities").jsonArray.any { it.jsonPrimitive.content == "views" })
        }
    }
}
