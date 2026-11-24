package org.krost.unidrive.cli

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.CredentialProof
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.ProfileMode
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.testing.TestScratch
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#860: `scope.get` and `scope.set` against a live daemon on a real socket: the verb classes, the
 * capabilities of `daemon.status`, the version check and the `scope.changed` event. The engine side of the swap (what leaves,
 * the refusal of local-only files, no delete in the cloud) is pinned by `ScopeSwapTest` in :app:hydration.
 */
class DaemonScopeVerbsTest {
    private lateinit var tempDir: Path
    private lateinit var socketPath: Path
    private val profile = "scope_test_" + UUID.randomUUID().toString().take(8)
    private val persisted = CopyOnWriteArrayList<List<String>>()

    @BeforeTest
    fun setUp() {
        tempDir = TestScratch.directory("daemon-scope-test")
        socketPath = tempDir.resolve("daemon.sock")
    }

    @AfterTest
    fun tearDown() {
        runCatching { tempDir.toFile().deleteRecursively() }
        runCatching { SyncEngine.hydrationCacheRoot(SyncEngine.defaultHydrationCacheRoot(), profile).toFile().deleteRecursively() }
    }

    private class Provider : CloudProvider {
        private val t0 = Instant.parse("2026-11-01T10:00:00Z")

        private fun item(
            path: String,
            folder: Boolean,
        ) = CloudItem(id = "id-$path", name = path.substringAfterLast('/'), path = path, size = if (folder) 0 else 5, isFolder = folder, modified = t0, created = t0, hash = null, mimeType = null)

        override val id: String = "stub"
        override val displayName: String = "Stub"
        override var isAuthenticated: Boolean = true

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {}

        override suspend fun checkCredentials(): CredentialProof = CredentialProof("Stub")

        override suspend fun listChildren(path: String): List<CloudItem> =
            if (path == "/") listOf(item("/A", true), item("/B", true), item("/C", true), item("/top.txt", false)) else emptyList()

        override suspend fun getMetadata(path: String): CloudItem = error("not used")

        override suspend fun download(remotePath: String, destination: Path): Long = error("not used")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used")

        override suspend fun delete(remotePath: String, ifMatchETag: String?) = error("a scope change never deletes in the cloud")

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(fromPath: String, toPath: String): CloudItem = error("not used")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage = DeltaPage(items = listOf(item("/A", true), item("/A/a.txt", false), item("/B", true), item("/B/b.txt", false)), cursor = "cursor-1", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 100L, used = 10L, remaining = 90L)
    }

    private fun runtime(mode: ProfileMode = ProfileMode.MOUNT) =
        DaemonRuntime(
            profileMode = mode,
            profileName = profile,
            lockFile = tempDir.resolve(".lock"),
            dbPath = tempDir.resolve("state.db"),
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { Provider() },
            providerHasQuota = false,
        ).also { it.scopeConfig = { scope -> persisted += scope } }

    private fun connect(scope: IpcAuth.Scope = IpcAuth.Scope.FULL): SocketChannel =
        IpcAuthClient.connect(IpcEndpoint(socketPath, tempDir, profile), scope).also { it.configureBlocking(false) }

    private fun SocketChannel.send(request: String) {
        write(ByteBuffer.wrap((request + "\n").toByteArray(Charsets.UTF_8)))
    }

    private suspend fun ask(
        request: String,
        scope: IpcAuth.Scope = IpcAuth.Scope.FULL,
    ): JsonObject {
        val channel = connect(scope)
        try {
            channel.send(request)
            val line = awaitIpcLine(channel, "reply to $request") { !it.contains("\"event\"") }
            return Json.parseToJsonElement(line).jsonObject
        } finally {
            channel.close()
        }
    }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.strings(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.ok() = getValue("ok").jsonPrimitive.boolean

    private suspend fun <T> daemon(
        mode: ProfileMode = ProfileMode.MOUNT,
        persistScope: (List<String>) -> Unit = { persisted += it },
        body: suspend () -> T,
    ): T =
        runBlocking {
            val runtime = runtime(mode).also { it.scopeConfig = persistScope }
            val job = launch { runtime.start() }
            awaitDaemonSocket(socketPath, job)
            try {
                body()
            } finally {
                runtime.close()
                job.join()
            }
        }

    @Test
    fun scope_reads_keep_the_committed_scope_while_persistence_is_pending_and_after_it_fails() =
        runBlocking {
            val persisting = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            daemon(persistScope = {
                persisting.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS))
                throw java.io.IOException("cannot write config")
            }) {
                val before = ask("""{"verb":"scope.get"}""")
                val setting = async {
                    ask("""{"verb":"scope.set","included":["/A"],"version":"${before.str("version")}"}""")
                }
                try {
                    kotlinx.coroutines.withTimeout(5_000) { persisting.await() }
                    assertEquals(before, ask("""{"verb":"scope.get"}"""))
                    assertEquals(before.strings("included"), ask("""{"verb":"daemon.status"}""").strings("sync_paths"))
                } finally {
                    release.countDown()
                }
                assertEquals("write_failed", setting.await().str("error"))
                assertEquals(before, ask("""{"verb":"scope.get"}"""))
                assertEquals(before.strings("included"), ask("""{"verb":"daemon.status"}""").strings("sync_paths"))
            }
        }

    @Test
    fun excluded_folders_do_not_include_ancestors_of_a_nested_scope() =
        runBlocking {
            daemon {
                val before = ask("""{"verb":"scope.get"}""")
                val set = ask("""{"verb":"scope.set","included":["/A/nested"],"version":"${before.str("version")}"}""")
                assertTrue(set.ok(), set.toString())
                assertEquals(listOf("/B", "/C"), ask("""{"verb":"scope.get","list_excluded":true}""").strings("excluded"))
            }
        }

    @Test
    fun read_scope_cannot_call_scope_set() =
        runBlocking {
            daemon {
                val got = ask("""{"verb":"scope.get"}""", IpcAuth.Scope.READ)
                assertTrue(got.ok(), "scope.get is a read verb: $got")
                val denied = ask("""{"verb":"scope.set","included":["/A"],"version":"${got.str("version")}"}""", IpcAuth.Scope.READ)
                assertEquals("forbidden", denied.str("error"))
                assertEquals(emptyList(), persisted, "a read connection changed nothing")
                assertEquals(emptyList(), ask("""{"verb":"scope.get"}""").strings("included"))

                val allowed = ask("""{"verb":"scope.set","included":["/A"],"version":"${got.str("version")}"}""")
                assertTrue(allowed.ok(), "the full scope may change it: $allowed")
            }
        }

    @Test
    fun scope_verbs_listed_in_daemon_status_capabilities() =
        runBlocking {
            daemon {
                val capabilities = ask("""{"verb":"daemon.status"}""").strings("capabilities")
                assertTrue("scope.get" in capabilities && "scope.set" in capabilities, "mount: $capabilities")
            }
            daemon(ProfileMode.MIRROR) {
                val capabilities = ask("""{"verb":"daemon.status"}""").strings("capabilities")
                assertTrue("scope.get" in capabilities, "mirror: $capabilities")
                assertFalse("scope.set" in capabilities, "a mirror daemon does not swap its scope live: $capabilities")
                val version = ask("""{"verb":"scope.get"}""").str("version")
                assertEquals("wrong_mode", ask("""{"verb":"scope.set","included":["/A"],"version":"$version"}""").str("error"))
            }
        }

    @Test
    fun scope_change_emits_scope_changed_event() =
        runBlocking {
            daemon {
                val syncSub = connect()
                val hydrationSub = connect(IpcAuth.Scope.READ)
                try {
                    syncSub.send("""{"verb":"sync.subscribe"}""")
                    awaitIpcLine(syncSub, "sync.subscribe reply") { !it.contains("\"event\"") }
                    hydrationSub.send("""{"verb":"hydration.subscribe"}""")
                    awaitIpcLine(hydrationSub, "hydration.subscribe reply") { !it.contains("\"event\"") }
                    val version = ask("""{"verb":"scope.get"}""").str("version")

                    val dry = ask("""{"verb":"scope.set","included":["/A"],"version":"$version","dry_run":true}""")
                    assertFalse(dry.getValue("applied").jsonPrimitive.boolean)
                    val applied = ask("""{"verb":"scope.set","included":["/A"],"version":"$version"}""")
                    assertTrue(applied.getValue("applied").jsonPrimitive.boolean, "$applied")

                    for ((sub, extra) in listOf(syncSub to emptySet(), hydrationSub to setOf("seq"))) {
                        val line = awaitIpcLine(sub, "scope.changed event") { it.contains("\"event\":\"scope.changed\"") }
                        val event = Json.parseToJsonElement(line).jsonObject
                        assertEquals(setOf("event", "included", "version") + extra, event.keys)
                        assertEquals(listOf("/A"), event.strings("included"))
                        assertEquals(applied.str("version"), event.str("version"), "the event names the version the reply gave")
                    }
                } finally {
                    syncSub.close()
                    hydrationSub.close()
                }
            }
        }

    @Test
    fun scope_set_is_atomic_and_versioned() =
        runBlocking {
            daemon {
                val first = ask("""{"verb":"scope.get"}""")
                assertEquals("missing_version", ask("""{"verb":"scope.set","included":["/A"]}""").str("error"))
                val stale = ask("""{"verb":"scope.set","included":["/A"],"version":"0000"}""")
                assertEquals("version_conflict", stale.str("error"))
                assertEquals(first.str("version"), stale.str("version"), "the refusal names the current version")
                assertEquals("invalid_included", ask("""{"verb":"scope.set","included":["A"],"version":"${first.str("version")}"}""").str("error"))
                assertEquals(emptyList(), persisted, "no refusal changed anything")

                val set = ask("""{"verb":"scope.set","included":["/B","/A"],"version":"${first.str("version")}"}""")
                assertEquals(listOf("/A", "/B"), set.strings("included"))
                assertEquals(first.str("version"), set.str("previous_version"))
                assertEquals(listOf(listOf("/A", "/B")), persisted)
                val now = ask("""{"verb":"scope.get"}""")
                assertEquals(set.str("version"), now.str("version"))
                assertEquals(listOf("/A", "/B"), ask("""{"verb":"daemon.status"}""").strings("sync_paths"), "daemon.status reports the live scope")

                // The old version is stale now: a second client's change on top of the first one's view is refused.
                assertEquals("version_conflict", ask("""{"verb":"scope.set","included":[],"version":"${first.str("version")}"}""").str("error"))

                val excluded = ask("""{"verb":"scope.get","list_excluded":true}""")
                assertEquals(listOf("/C"), excluded.strings("excluded"), "the cloud root's folders outside the scope, files left out")
                assertEquals(0, set.getValue("effect").jsonObject.getValue("unsynced").jsonObject.getValue("count").jsonPrimitive.int)
            }
        }
}
