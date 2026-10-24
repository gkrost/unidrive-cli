package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HydrationImplNamespaceTest {

    private class NamespaceFakeProvider : CloudProvider {
        override val id = "fake-ns"
        override val displayName = "Fake (namespace test)"
        override var isAuthenticated = true

        var lastCreatedFolder: String? = null
        var lastDeletedPath: String? = null
        var throwOnCreate: Throwable? = null
        var throwOnDelete: Throwable? = null

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)
        override suspend fun authenticate() {}
        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()
        // A never-uploaded local file is genuinely absent on the cloud — the
        // rename/unlink ghost-probe expects a typed not-found, not a generic error.
        override suspend fun getMetadata(path: String): CloudItem = throw ProviderException("Item not found: $path")
        override suspend fun download(remotePath: String, destination: Path): Long = 0L
        override suspend fun downloadById(remoteId: String, remotePath: String, destination: Path): Long = 0L
        override suspend fun upload(localPath: Path, remotePath: String, existingRemoteId: String?, ifMatchETag: String?, onProgress: ((Long, Long) -> Unit)?): CloudItem = error("not used")
        override suspend fun delete(remotePath: String, ifMatchETag: String?) {
            lastDeletedPath = remotePath
            throwOnDelete?.also { throwOnDelete = null; throw it }
        }
        override suspend fun createFolder(path: String): CloudItem {
            lastCreatedFolder = path
            throwOnCreate?.also { throwOnCreate = null; throw it }
            return CloudItem(
                id = "rid-$path",
                name = path.substringAfterLast('/'),
                path = path,
                size = 0L,
                isFolder = true,
                modified = Instant.parse("2026-05-24T09:00:00Z"),
                created = null,
                hash = null,
                mimeType = null,
            )
        }
        override suspend fun move(fromPath: String, toPath: String): CloudItem = error("not used")
        override suspend fun delta(cursor: String?, onPageProgress: ((Int) -> Unit)?, scanContext: org.krost.unidrive.ScanContext?): DeltaPage = DeltaPage(items = emptyList(), cursor = "cursor", hasMore = false)
        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    private data class Env(
        val impl: HydrationImpl,
        val provider: NamespaceFakeProvider,
        val db: StateDatabase,
        val engine: SyncEngine,
    )

    private fun freshEnv(): Env {
        val cacheRoot = Files.createTempDirectory("unidrive-hydration-ns-cache")
        val dbPath = Files.createTempDirectory("unidrive-hydration-ns-db").resolve("state.db")
        val provider = NamespaceFakeProvider()
        val db = StateDatabase(dbPath = dbPath, inMemory = true)
        db.initialize()
        val syncRoot = Files.createTempDirectory("unidrive-hydration-ns-sync")
        val engine = SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            cacheRoot = cacheRoot,
        )
        val hydration = HydrationImpl(syncEngine = engine, stateDb = db)
        return Env(hydration, provider, db, engine)
    }

    @Test
    fun mkdir_creates_folder_on_provider_and_inserts_state_db_row() = runTest {
        val (impl, provider, stateDb) = freshEnv()

        val result = impl.mkdir("/foo")

        assertEquals(MkdirResult.Ok, result)
        assertEquals("/foo", provider.lastCreatedFolder)

        val entry = stateDb.getEntry("/foo")
        assertTrue(entry != null && entry.isFolder)
    }

    @Test
    fun unlink_refuses_folder_path_with_PathIsFolder() = runTest {
        val (impl, _, stateDb) = freshEnv()

        // Pre-populate a folder row.
        stateDb.upsertEntry(
            SyncEntry(
                path = "/foo",
                remoteId = "rid-foo",
                remoteHash = null,
                remoteSize = 0L,
                remoteModified = Instant.parse("2026-05-24T09:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = true,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )

        val result = impl.unlink("/foo")

        assertEquals(UnlinkResult.PathIsFolder, result)
    }

    @Test
    fun rmdir_refuses_file_path_with_PathIsFile() = runTest {
        val (impl, _, stateDb) = freshEnv()

        // Pre-populate a file row.
        stateDb.upsertEntry(
            SyncEntry(
                path = "/foo.txt",
                remoteId = "rid-foo",
                remoteHash = "hash",
                remoteSize = 100L,
                remoteModified = Instant.parse("2026-05-24T09:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = false,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )

        val result = impl.rmdir("/foo.txt")

        assertEquals(RmdirResult.PathIsFile, result)
    }

    @Test
    fun mkdir_detects_provider_parent_not_found_substring() = runTest {
        // OneDrive wording: GraphApiException("Create folder failed: 404 ...")
        val (impl1, provider1, _) = freshEnv()
        provider1.throwOnCreate = RuntimeException("Create folder failed: 404 Not Found - The resource could not be found.")
        assertEquals(MkdirResult.ParentNotFound, impl1.mkdir("/missing/child"))

        // Internxt wording: ProviderException("Folder not found: <seg> in <path>")
        val (impl2, provider2, _) = freshEnv()
        provider2.throwOnCreate = RuntimeException("Folder not found: missing in /missing/child")
        assertEquals(MkdirResult.ParentNotFound, impl2.mkdir("/missing/child"))
    }

    @Test
    fun unlink_of_never_uploaded_row_skips_provider_and_succeeds() = runTest {
        val (impl, provider, stateDb) = freshEnv()

        // A file created through the mount but not yet uploaded: state.db row
        // with remoteId = null (stored as a `local:` synthetic, surfaced back
        // as null). It only ever existed locally.
        stateDb.upsertEntry(
            SyncEntry(
                path = "/draft.txt",
                remoteId = null,
                remoteHash = null,
                remoteSize = 0L,
                remoteModified = null,
                localMtime = Instant.now().toEpochMilli(),
                localSize = 0L,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
            ),
        )
        val result = impl.unlink("/draft.txt")

        assertEquals(UnlinkResult.Ok, result, "rm of a never-uploaded file must succeed, not EIO")
        assertEquals(
            null,
            provider.lastDeletedPath,
            "provider.delete must NOT be called for a never-uploaded file (would 404)",
        )
        assertEquals(null, stateDb.getEntry("/draft.txt"), "row must no longer be alive")
        // Hard-delete, not tombstone: a never-uploaded file has no cloud
        // counterpart, so leaving a TRASHED tombstone would grow sync_entries
        // unboundedly under create/delete temp-file churn. The row must be gone
        // from the table entirely (no row of ANY status at that path).
        assertEquals(
            emptyList<SyncEntry>(),
            stateDb.recovery.allEntriesAnyStatus().filter { it.path == "/draft.txt" },
            "never-uploaded unlink must hard-delete the row, leaving no tombstone",
        )
    }

    @Test
    fun unlink_of_uploaded_row_does_call_provider_delete() = runTest {
        // Contrast case: a normally-synced file (real remoteId) still routes
        // through the provider delete — the skip is scoped strictly to the
        // remoteId == null case.
        val (impl, provider, stateDb) = freshEnv()
        stateDb.upsertEntry(
            SyncEntry(
                path = "/synced.txt",
                remoteId = "rid-synced",
                remoteHash = "hash",
                remoteSize = 4L,
                remoteModified = Instant.parse("2026-05-24T09:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = false,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )

        val result = impl.unlink("/synced.txt")

        assertEquals(UnlinkResult.Ok, result)
        assertEquals("/synced.txt", provider.lastDeletedPath, "uploaded file must hit provider.delete")
    }

    @Test
    fun unlink_treats_remote_already_gone_404_as_success() = runTest {
        // Guards: rm of a stale (already-deleted-on-cloud) file must return Ok,
        // not EIO. The row must be tombstoned so the reconciler tracks the deletion.
        // Uses the typed resolution-error shape (ProviderException with "Folder not found: "
        // prefix) that InternxtProvider.resolveFolder emits when a parent is gone.
        val (impl, provider, stateDb) = freshEnv()
        stateDb.upsertEntry(
            SyncEntry(
                path = "/gone.txt",
                remoteId = "rid-gone",
                remoteHash = "hash",
                remoteSize = 10L,
                remoteModified = Instant.parse("2026-05-24T09:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = false,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
        provider.throwOnDelete = ProviderException("Folder not found: gone in /gone.txt")

        val result = impl.unlink("/gone.txt")

        assertEquals(UnlinkResult.Ok, result, "rm of already-gone remote file must succeed, not EIO")
        // Row must be tombstoned (status DELETED) — getEntry returns null (alive view), but
        // the tombstone is present so the reconciler can track the cloud deletion.
        assertEquals(null, stateDb.getEntry("/gone.txt"), "row must no longer be alive after unlink")
        val tombstone = stateDb.recovery.allEntriesAnyStatus().find { it.path == "/gone.txt" }
        assertTrue(tombstone != null, "a DELETED tombstone must remain for reconciler tracking")
    }

    @Test
    fun unlink_does_not_swallow_5xx_mentioning_404_in_message() = runTest {
        // Guards: codex P2 — a ProviderException whose MESSAGE contains "404" or
        // "not found" but does NOT carry a recognised typed not-found prefix must
        // surface as Failed, not Ok. Proves the free-text misclassification hole is closed.
        val (impl, provider, stateDb) = freshEnv()
        stateDb.upsertEntry(
            SyncEntry(
                path = "/live.txt",
                remoteId = "rid-live",
                remoteHash = "hash",
                remoteSize = 10L,
                remoteModified = Instant.parse("2026-05-24T09:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = false,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
        // 502 proxy error whose body contains both "404" and "not found" —
        // old free-text check would have returned Ok and tombstoned a live file.
        provider.throwOnDelete = ProviderException("502 Bad Gateway: upstream /live.txt not found (404)")

        val result = impl.unlink("/live.txt")

        assertTrue(result is UnlinkResult.Failed, "5xx with '404' in message must be Failed, not Ok")
        assertTrue(stateDb.getEntry("/live.txt") != null, "row must remain alive — 5xx must not tombstone a live file")
    }

    @Test
    fun unlink_does_not_mask_non_notfound_errors() = runTest {
        // Guards: idempotent-on-gone must NOT become swallow-all-errors.
        // A real server error (5xx, auth, throttle) must surface as Failed, not Ok.
        // The row must remain alive — a real error leaves the file in an unknown state.
        val (impl, provider, stateDb) = freshEnv()
        stateDb.upsertEntry(
            SyncEntry(
                path = "/real-error.txt",
                remoteId = "rid-real",
                remoteHash = "hash",
                remoteSize = 10L,
                remoteModified = Instant.parse("2026-05-24T09:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = false,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
        provider.throwOnDelete = ProviderException("500 Internal Server Error")

        val result = impl.unlink("/real-error.txt")

        assertTrue(result is UnlinkResult.Failed, "5xx error must surface as Failed, not Ok")
        assertTrue(stateDb.getEntry("/real-error.txt") != null, "row must remain alive after a real error")
    }

    @Test
    fun rmdir_treats_remote_already_gone_404_as_success() = runTest {
        // Guards: rm -d of a folder whose cloud copy is already gone must return Ok.
        // "Not empty" errors must NOT be masked — they are a distinct failure class.
        val (impl, provider, stateDb) = freshEnv()
        stateDb.upsertEntry(
            SyncEntry(
                path = "/gone-dir",
                remoteId = "rid-gone-dir",
                remoteHash = null,
                remoteSize = 0L,
                remoteModified = Instant.parse("2026-05-24T09:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = true,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
        provider.throwOnDelete = ProviderException("Folder not found: gone-dir in /gone-dir")

        val result = impl.rmdir("/gone-dir")

        assertEquals(RmdirResult.Ok, result, "rmdir of already-gone remote folder must succeed, not EIO")
        assertEquals(null, stateDb.getEntry("/gone-dir"), "row must no longer be alive after rmdir")
        // "not empty" must still fail — not-found idempotency must not swallow a different error class
        val (impl2, provider2, stateDb2) = freshEnv()
        stateDb2.upsertEntry(
            SyncEntry(
                path = "/nonempty-dir",
                remoteId = "rid-nonempty",
                remoteHash = null,
                remoteSize = 0L,
                remoteModified = Instant.parse("2026-05-24T09:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = true,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
        provider2.throwOnDelete = RuntimeException("Folder not empty")
        assertEquals(RmdirResult.NotEmpty, impl2.rmdir("/nonempty-dir"), "not-empty must not be swallowed as gone")
    }

    @Test
    fun rmdir_detects_provider_not_empty_substring() = runTest {
        // OneDrive wording
        val (impl1, provider1, stateDb1) = freshEnv()
        stateDb1.upsertEntry(
            SyncEntry(
                path = "/foo",
                remoteId = "rid-foo",
                remoteHash = null,
                remoteSize = 0L,
                remoteModified = Instant.parse("2026-05-24T09:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = true,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
        provider1.throwOnDelete = RuntimeException("OneDrive responded 409 Conflict: Folder not empty")
        assertEquals(RmdirResult.NotEmpty, impl1.rmdir("/foo"))

        // Internxt wording
        val (impl2, provider2, stateDb2) = freshEnv()
        stateDb2.upsertEntry(
            SyncEntry(
                path = "/foo",
                remoteId = "rid-foo",
                remoteHash = null,
                remoteSize = 0L,
                remoteModified = Instant.parse("2026-05-24T09:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = true,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
        provider2.throwOnDelete = RuntimeException("Internxt API: cannot delete non-empty folder")
        assertEquals(RmdirResult.NotEmpty, impl2.rmdir("/foo"))
    }

    @Test
    fun rmdir_maps_typed_FolderNotEmpty_to_NotEmpty() = runTest {
        // The typed signal must map to ENOTEMPTY regardless of the message text:
        // a provider raising FolderNotEmptyException (even with a message that
        // contains none of the substrings the fallback matcher looks for) is
        // surfaced as NotEmpty, not Failed.
        val env = freshEnv()
        env.db.upsertEntry(
            SyncEntry(
                path = "/typed-dir",
                remoteId = "rid-typed",
                remoteHash = null,
                remoteSize = 0L,
                remoteModified = Instant.parse("2026-05-24T09:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = true,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
        env.provider.throwOnDelete =
            org.krost.unidrive.FolderNotEmptyException("Verzeichnis nicht leer")

        assertEquals(RmdirResult.NotEmpty, env.impl.rmdir("/typed-dir"))
    }

    @Test
    fun unlink_evicts_hydration_cache_file() = runTest {
        val env = freshEnv()
        env.db.upsertEntry(
            SyncEntry(
                path = "/cached.txt",
                remoteId = "rid-cached",
                remoteHash = "hash",
                remoteSize = 5L,
                remoteModified = Instant.parse("2026-05-24T09:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
            ),
        )
        val cacheFile = env.engine.resolveCachePath("/cached.txt")
        Files.createDirectories(cacheFile.parent)
        Files.writeString(cacheFile, "stale")
        assertTrue(Files.exists(cacheFile), "precondition: cache file must exist before unlink")

        val result = env.impl.unlink("/cached.txt")

        assertEquals(UnlinkResult.Ok, result)
        assertTrue(
            Files.notExists(cacheFile),
            "unlink must evict the hydration-cache file so stale bytes do not linger",
        )
    }

    @Test
    fun rmdir_evicts_hydration_cache_subtree() = runTest {
        val env = freshEnv()
        env.db.upsertEntry(
            SyncEntry(
                path = "/cdir",
                remoteId = "rid-cdir",
                remoteHash = null,
                remoteSize = 0L,
                remoteModified = Instant.parse("2026-05-24T09:00:00Z"),
                localMtime = null,
                localSize = null,
                isFolder = true,
                isPinned = false,
                isHydrated = false,
                lastSynced = Instant.now(),
            ),
        )
        // Populate the folder's cache subtree with a nested hydrated file.
        val nested = env.engine.resolveCachePath("/cdir/sub/file.bin")
        Files.createDirectories(nested.parent)
        Files.writeString(nested, "bytes")
        val cacheDir = env.engine.resolveCachePath("/cdir")
        assertTrue(Files.exists(cacheDir), "precondition: cache subtree must exist before rmdir")

        val result = env.impl.rmdir("/cdir")

        assertEquals(RmdirResult.Ok, result)
        assertTrue(
            Files.notExists(cacheDir),
            "rmdir must recursively evict the folder's hydration-cache subtree",
        )
    }

    // ── archive#1035: a plain rmdir is strict, a whole tree needs the recursive opt-in ──

    private fun row(
        path: String,
        remoteId: String?,
        isFolder: Boolean = false,
        isHydrated: Boolean = !isFolder,
        editBase: String? = null,
    ) = SyncEntry(
        path = path,
        remoteId = remoteId,
        remoteHash = null,
        remoteSize = if (isFolder) 0L else 5L,
        remoteModified = Instant.parse("2026-10-20T09:00:00Z"),
        localMtime = null,
        localSize = null,
        isFolder = isFolder,
        isPinned = false,
        isHydrated = isHydrated,
        lastSynced = Instant.now(),
        editBase = editBase,
    )

    private fun Env.cacheBytes(path: String): Path =
        engine.resolveCachePath(path).also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "bytes")
        }

    // A hydrated file as the mount leaves it: the cache copy written first, then the row that records its stamp as the
    // baseline (MountEngine's hydrate does exactly this), so the copy is clean.
    private fun Env.syncedFile(path: String, remoteId: String): Path {
        val cached = cacheBytes(path)
        db.upsertEntry(
            row(path, remoteId).copy(
                localMtime = Files.getLastModifiedTime(cached).toMillis(),
                localSize = Files.size(cached),
                lastSynced = Instant.now(),
            ),
        )
        return cached
    }

    @Test
    fun rmdir_refuses_a_folder_with_a_never_uploaded_child_before_any_provider_call() = runTest {
        val env = freshEnv()
        env.db.upsertEntry(row("/dir", "rid-dir", isFolder = true))
        env.db.upsertEntry(row("/dir/new.txt", remoteId = null)) // created in the mount, upload not done (no slot: it failed)
        val only = env.cacheBytes("/dir/new.txt")

        val result = env.impl.rmdir("/dir")

        assertEquals(RmdirResult.NotEmpty, result)
        assertEquals(null, env.provider.lastDeletedPath, "nothing may be deleted in the cloud")
        assertTrue(env.db.getEntry("/dir/new.txt") != null, "the child row stays")
        assertTrue(Files.exists(only), "the cache file is the only copy of the never-uploaded bytes and must stay")
    }

    @Test
    fun rmdir_refuses_a_folder_with_a_synced_child_without_the_opt_in() = runTest {
        val env = freshEnv()
        env.db.upsertEntry(row("/dir", "rid-dir", isFolder = true))
        env.db.upsertEntry(row("/dir/a.txt", "rid-a"))

        assertEquals(RmdirResult.NotEmpty, env.impl.rmdir("/dir"))
        assertEquals(null, env.provider.lastDeletedPath)
    }

    @Test
    fun rmdir_recursive_deletes_a_synced_tree() = runTest {
        val env = freshEnv()
        env.db.upsertEntry(row("/dir", "rid-dir", isFolder = true))
        env.db.upsertEntry(row("/dir/sub", "rid-sub", isFolder = true))
        val cached = env.syncedFile("/dir/sub/a.txt", "rid-a")

        val result = env.impl.rmdir("/dir", recursive = true)

        assertEquals(RmdirResult.Ok, result)
        assertEquals("/dir", env.provider.lastDeletedPath)
        assertEquals(null, env.db.getEntry("/dir/sub/a.txt"), "the subtree's rows are tombstoned with the folder")
        assertTrue(Files.notExists(cached), "a synced file's cache copy goes with the folder")
    }

    @Test
    fun rmdir_recursive_refuses_while_a_file_below_has_bytes_only_this_computer_holds() = runTest {
        for ((name, child) in listOf(
            "a never-uploaded file" to row("/dir/sub/new.txt", remoteId = null),
            "an edit that failed to upload" to row("/dir/sub/new.txt", "rid-new", editBase = "base-hash"),
        )) {
            val env = freshEnv()
            env.db.upsertEntry(row("/dir", "rid-dir", isFolder = true))
            env.db.upsertEntry(row("/dir/sub", "rid-sub", isFolder = true))
            env.db.upsertEntry(child)
            val only = env.cacheBytes(child.path)

            val result = env.impl.rmdir("/dir", recursive = true)

            assertEquals(RmdirResult.NotEmpty, result, name)
            assertEquals(null, env.provider.lastDeletedPath, "$name: nothing may be deleted in the cloud")
            assertTrue(env.db.getEntry(child.path) != null, "$name: the row stays")
            assertTrue(Files.exists(only), "$name: the only copy of the bytes stays")
        }
    }

    @Test
    fun rmdir_refuses_a_folder_whose_child_lies_below_a_missing_intermediate_folder_row() = runTest {
        // Rows /dir and /dir/sub/file.txt, no row /dir/sub: listDirectChildren("/dir") is empty, the folder is not.
        val env = freshEnv()
        env.db.upsertEntry(row("/dir", "rid-dir", isFolder = true))
        val cached = env.syncedFile("/dir/sub/file.txt", "rid-file")

        assertEquals(RmdirResult.NotEmpty, env.impl.rmdir("/dir"))
        assertEquals(null, env.provider.lastDeletedPath, "nothing may be deleted in the cloud")
        assertTrue(env.db.getEntry("/dir/sub/file.txt") != null, "the row stays")
        assertTrue(Files.exists(cached), "the cache copy stays")
    }

    @Test
    fun rmdir_recursive_deletes_a_tree_with_a_missing_intermediate_folder_row() = runTest {
        val env = freshEnv()
        env.db.upsertEntry(row("/dir", "rid-dir", isFolder = true))
        val cached = env.syncedFile("/dir/sub/file.txt", "rid-file")

        assertEquals(RmdirResult.Ok, env.impl.rmdir("/dir", recursive = true))
        assertEquals("/dir", env.provider.lastDeletedPath)
        assertTrue(Files.notExists(cached))
    }

    @Test
    fun rmdir_recursive_refuses_a_synced_file_whose_cache_copy_changed_while_the_daemon_was_stopped() = runTest {
        // The replay's dirty overwrite (archive#605): no editBase, not a pending upload, but the cache copy is not the baseline.
        // Each change leaves the copy's mtime before the row's last sync, so only the baseline comparison can see it.
        val before = java.nio.file.attribute.FileTime.fromMillis(Instant.now().minusSeconds(60).toEpochMilli())
        for (change in listOf<Pair<String, (Path) -> Unit>>(
            "another size" to { p ->
                Files.writeString(p, "bytes, and then some")
                Files.setLastModifiedTime(p, before)
            },
            "the same size, another mtime" to { p ->
                Files.writeString(p, "BYTES")
                Files.setLastModifiedTime(p, before)
            },
        )) {
            val env = freshEnv()
            env.db.upsertEntry(row("/dir", "rid-dir", isFolder = true))
            env.db.upsertEntry(row("/dir/sub", "rid-sub", isFolder = true))
            val cached = env.syncedFile("/dir/sub/edited.txt", "rid-edited")
            val baseline = env.db.getEntry("/dir/sub/edited.txt")!!
            assertEquals(null, baseline.editBase, "precondition: no edit mark")
            assertTrue(!baseline.isPendingUpload, "precondition: not a pending upload")
            change.second(cached)

            val result = env.impl.rmdir("/dir", recursive = true)

            assertEquals(RmdirResult.NotEmpty, result, change.first)
            assertEquals(null, env.provider.lastDeletedPath, "${change.first}: nothing may be deleted in the cloud")
            assertTrue(env.db.getEntry("/dir/sub/edited.txt") != null, "${change.first}: the row stays")
            assertTrue(Files.exists(cached), "${change.first}: the edit in the cache stays")
        }
    }

    @Test
    fun rmdir_recursive_refuses_a_synced_file_whose_cache_copy_is_newer_than_its_last_sync() = runTest {
        // The reap's rule: a baseline-less row (an old one) whose cache copy was written after the row was.
        val env = freshEnv()
        env.db.upsertEntry(row("/dir", "rid-dir", isFolder = true))
        // The last sync a minute back, not "now": Linux stamps a file from a coarse clock that can lag Instant.now() by a
        // few milliseconds, and a copy written right after an Instant.now() row then looked older than the row (red on CI).
        env.db.upsertEntry(row("/dir/old.txt", "rid-old").copy(lastSynced = Instant.now().minusSeconds(60)))
        val cached = env.cacheBytes("/dir/old.txt")

        assertEquals(RmdirResult.NotEmpty, env.impl.rmdir("/dir", recursive = true))
        assertEquals(null, env.provider.lastDeletedPath)
        assertTrue(Files.exists(cached))
    }

    @Test
    fun rmdir_recursive_deletes_a_cloud_only_tree_with_no_cache_copies() = runTest {
        val env = freshEnv()
        env.db.upsertEntry(row("/dir", "rid-dir", isFolder = true))
        env.db.upsertEntry(row("/dir/a.txt", "rid-a", isHydrated = false))

        assertEquals(RmdirResult.Ok, env.impl.rmdir("/dir", recursive = true))
        assertEquals("/dir", env.provider.lastDeletedPath)
    }

    // ── archive#1040 M5: Free up space (dehydrate) never deletes the only copy of an edit ──

    @Test
    fun dehydrate_keeps_a_synced_file_whose_cache_copy_changed_while_the_daemon_was_stopped() = runTest {
        // No editBase and not a pending upload: only the copy against the row's baseline shows the edit (archive#605's test).
        val before = java.nio.file.attribute.FileTime.fromMillis(Instant.now().minusSeconds(60).toEpochMilli())
        for (change in listOf<Pair<String, (Path) -> Unit>>(
            "another size" to { p ->
                Files.writeString(p, "bytes, and then some")
                Files.setLastModifiedTime(p, before)
            },
            "the same size, another mtime" to { p ->
                Files.writeString(p, "BYTES")
                Files.setLastModifiedTime(p, before)
            },
        )) {
            val env = freshEnv()
            val cached = env.syncedFile("/edited.txt", "rid-edited")
            change.second(cached)

            assertEquals(DehydrateResult.Busy, env.impl.dehydrate("/edited.txt"), change.first)
            assertTrue(Files.exists(cached), "${change.first}: the edit in the cache stays")
            assertTrue(env.db.getEntry("/edited.txt")!!.isHydrated, "${change.first}: the row stays hydrated")
        }
    }

    @Test
    fun dehydrate_keeps_a_cache_copy_newer_than_its_last_sync() = runTest {
        // The reap's rule: a baseline-less row (an old one) whose cache copy was written after the row was. The last sync
        // a minute back, not now: Linux stamps a file from a coarse clock that can lag Instant.now().
        val env = freshEnv()
        env.db.upsertEntry(row("/old.txt", "rid-old").copy(lastSynced = Instant.now().minusSeconds(60)))
        val cached = env.cacheBytes("/old.txt")

        assertEquals(DehydrateResult.Busy, env.impl.dehydrate("/old.txt"))
        assertTrue(Files.exists(cached))
    }

    @Test
    fun dehydrate_keeps_an_edit_that_failed_to_upload() = runTest {
        // The edit was noted (editBase) when it began; its upload failed and the slot is gone.
        val env = freshEnv()
        val cached = env.syncedFile("/failed.txt", "rid-failed")
        env.db.markEdited("/failed.txt", "base-hash")
        Files.writeString(cached, "edited")

        assertEquals(DehydrateResult.Busy, env.impl.dehydrate("/failed.txt"))
        assertTrue(Files.exists(cached))
    }

    @Test
    fun dehydrate_still_frees_a_clean_synced_copy() = runTest {
        val env = freshEnv()
        val cached = env.syncedFile("/clean.txt", "rid-clean")

        assertEquals(DehydrateResult.Ok, env.impl.dehydrate("/clean.txt"))
        assertTrue(Files.notExists(cached))
        assertTrue(!env.db.getEntry("/clean.txt")!!.isHydrated)
    }
}
