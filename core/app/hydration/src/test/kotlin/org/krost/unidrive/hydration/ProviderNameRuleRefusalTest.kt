package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * A provider that refuses some names by its own rules before any request (Internxt's `internxt_strict_names`): the
 * mount's verbs that make a new name, mkdir, create and rename, ask it first and answer its refusal, typed
 * (`name_incompatible: <rule>`), before anything is changed here or asked of the drive. A provider without such rules
 * (the default, and Internxt with the mode off) changes nothing.
 */
class ProviderNameRuleRefusalTest {
    private class RuleProvider(
        private val refused: Map<String, String>,
    ) : CloudProvider {
        override val id = "fake-name-rules"
        override val displayName = "Fake (name rules)"
        override var isAuthenticated = true
        val calls = mutableListOf<String>()

        override fun newNameIssue(path: String): String? =
            path.split('/').firstNotNullOfOrNull { refused[it] }?.let { "name_incompatible: $it" }

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList<CloudItem>().also { calls += "list $path" }

        override suspend fun getMetadata(path: String): CloudItem {
            calls += "metadata $path"
            error("not used: metadata $path")
        }

        override suspend fun download(remotePath: String, destination: Path): Long = 0L.also { calls += "download $remotePath" }

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            calls += "upload $remotePath"
            return item(remotePath, isFolder = false)
        }

        override suspend fun delete(remotePath: String, ifMatchETag: String?) {
            calls += "delete $remotePath"
        }

        override suspend fun createFolder(path: String): CloudItem {
            calls += "createFolder $path"
            return item(path, isFolder = true)
        }

        override suspend fun move(fromPath: String, toPath: String): CloudItem {
            calls += "move $fromPath $toPath"
            return item(toPath, isFolder = false)
        }

        override suspend fun delta(cursor: String?, onPageProgress: ((Int) -> Unit)?, scanContext: org.krost.unidrive.ScanContext?): DeltaPage =
            DeltaPage(items = emptyList(), cursor = "c", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)

        private fun item(path: String, isFolder: Boolean) =
            CloudItem(
                id = "id-$path", name = path.substringAfterLast('/'), path = path, size = 0L, isFolder = isFolder,
                modified = Instant.parse("2026-01-01T00:00:00Z"), created = null, hash = null, mimeType = null,
            )
    }

    private val long = "n".repeat(300)

    private class Env(val hydration: HydrationImpl, val provider: RuleProvider, val db: StateDatabase)

    private fun env(refused: Map<String, String>): Env {
        val provider = RuleProvider(refused)
        val db = StateDatabase(dbPath = Files.createTempDirectory("ud-name-rule-db").resolve("state.db"), inMemory = true)
        db.initialize()
        val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = Files.createTempDirectory("ud-name-rule-sync"),
                cacheRoot = Files.createTempDirectory("ud-name-rule-cache"),
            )
        db.upsertEntry(
            SyncEntry(
                path = "/f.txt", remoteId = "id-f", remoteHash = null, remoteSize = 1L, remoteModified = Instant.parse("2026-01-01T00:00:00Z"),
                localMtime = null, localSize = null, isFolder = false, isPinned = false, isHydrated = false,
                lastSynced = Instant.parse("2026-01-01T00:00:00Z"),
            ),
        )
        return Env(HydrationImpl(syncEngine = engine, stateDb = db), provider, db)
    }

    private val rules = mapOf("a:b" to "windows_character", "x " to "trailing_whitespace", "CON.txt" to "device_name", long to "too_long_utf16")

    @Test
    fun `mkdir create rename and a long name are refused with the provider's typed reason before any provider call`() =
        runTest {
            val e = env(rules)

            assertEquals(MkdirResult.Failed(HydrationError.NameRefused("name_incompatible: windows_character", null)), e.hydration.mkdir("/a:b"))
            assertEquals(
                CreateResult.Failed(HydrationError.NameRefused("name_incompatible: trailing_whitespace", null)),
                e.hydration.create("c", "h1", "/x "),
            )
            assertEquals(
                RenameResult.Failed(HydrationError.NameRefused("name_incompatible: device_name", null)),
                e.hydration.rename("/f.txt", "/CON.txt"),
            )
            assertEquals(
                CreateResult.Failed(HydrationError.NameRefused("name_incompatible: too_long_utf16", null)),
                e.hydration.create("c", "h2", "/$long"),
            )

            assertEquals(emptyList(), e.provider.calls, "no provider call")
            assertNull(e.db.getEntry("/a:b"))
            assertNull(e.db.getEntry("/x "))
            assertNull(e.db.getEntry("/CON.txt"))
            assertEquals("id-f", e.db.getEntry("/f.txt")?.remoteId, "the source of the refused rename is untouched")
        }

    @Test
    fun `the wire form of the refusal is the typed reason`() {
        assertEquals("name_incompatible: windows_character", HydrationError.NameRefused("name_incompatible: windows_character", null).message)
    }

    @Test
    fun `without provider rules the same verbs behave as before`() =
        runTest {
            val e = env(emptyMap())
            val refusedByName = { r: Any -> r.toString().contains("name_incompatible") }

            assertNotEquals(true, refusedByName(e.hydration.mkdir("/a:b")))
            assertNotEquals(true, refusedByName(e.hydration.create("c", "h1", "/x ")))
            assertNotEquals(true, refusedByName(e.hydration.rename("/f.txt", "/CON.txt")))
            assertEquals(MkdirResult.Ok, e.hydration.mkdir("/plain"))
            assertEquals(listOf("createFolder /plain"), e.provider.calls.filter { it.startsWith("createFolder /plain") })
        }
}
