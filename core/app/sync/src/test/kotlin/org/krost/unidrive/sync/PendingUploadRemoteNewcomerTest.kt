package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncAction
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// #743 finding 1: a local file with a pending-upload row (scanned, never uploaded) and a remote file
// another device later created at the same path. The remote state classifies as NEW against the
// pending row, but the local side is UNCHANGED or MODIFIED, so the plan used to overwrite the local
// bytes with a bare DownloadContent (no conflict copy) or fall through to the recovery loop's blind
// Upload (no write guard). The pending pair must get the both-new treatment: adopt on identical
// bytes, else keep both.
//
// #743 finding 2: provenSame (the both-modified content comparison) read the local file without
// tolerating an IOException, so a file locked by another application aborted the whole pass. An
// unreadable file must answer "not proven same" and keep the conflict. The test injects a
// sameContent that throws; the real lock (FileShare.None) is not reproducible on Linux CI, where
// advisory locks do not block reads.
class PendingUploadRemoteNewcomerTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-743-root")
        db = StateDatabase(Files.createTempDirectory("unidrive-743-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider().apply { hashAlgorithmOverride = HashAlgorithm.Sha256Hex }
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun engine() =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
        )

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun cloudFile(
        path: String,
        bytes: ByteArray,
        id: String = "id-$path",
        modified: Instant = Instant.parse("2026-03-28T12:00:00Z"),
    ): CloudItem {
        provider.files[path] = bytes
        return CloudItem(
            id = id,
            name = path.substringAfterLast("/"),
            path = path,
            size = bytes.size.toLong(),
            isFolder = false,
            modified = modified,
            created = modified,
            hash = sha256(bytes),
            mimeType = "application/octet-stream",
        )
    }

    // A row LocalScanner would have written for the file: pending (never uploaded), hydrated, and
    // matching the file's mtime and size, so the next scan reports nothing (UNCHANGED).
    private fun seedPendingRow(
        path: String,
        size: Long,
    ) {
        val file = syncRoot.resolve(path.removePrefix("/"))
        db.upsertEntry(
            SyncEntry(
                path = path,
                remoteId = null,
                remoteHash = null,
                remoteSize = 0,
                remoteModified = null,
                localMtime = Files.getLastModifiedTime(file).toMillis(),
                localSize = size,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.EPOCH,
            ),
        )
    }

    private fun copies(): List<Path> =
        Files.list(syncRoot).use { s -> s.filter { it.fileName.toString().contains(".conflict-local-") }.toList() }

    @Test
    fun `a pending upload the remote later answered with a different file is kept both, not overwritten`() = runTest {
        val local = syncRoot.resolve("a.txt")
        Files.writeString(local, "local!")
        seedPendingRow("/a.txt", size = "local!".length.toLong())
        provider.deltaItems = listOf(cloudFile("/a.txt", "cloud!".toByteArray()))

        engine().syncOnce()

        assertEquals("cloud!", Files.readString(local), "the cloud's bytes take the path")
        assertEquals("local!", Files.readString(copies().single()), "the local bytes survive as a conflict copy")
        assertEquals("cloud!", String(provider.files.getValue("/a.txt")), "the cloud file is not overwritten")
        assertTrue(provider.uploadedPaths.none { it == "/a.txt" }, "the newcomer is not overwritten: ${provider.uploadedPaths}")
        assertTrue(provider.uploadedPaths.any { it.contains(".conflict-local-") }, "the copy goes up: ${provider.uploadedPaths}")
    }

    @Test
    fun `a pending upload edited before the remote newcomer is kept both, not uploaded over it`() = runTest {
        val local = syncRoot.resolve("a.txt")
        Files.writeString(local, "local!")
        seedPendingRow("/a.txt", size = "local!".length.toLong())
        // The user edits the file before the next pass: the scanner reports MODIFIED over the pending row.
        Files.writeString(local, "edited!")
        provider.deltaItems = listOf(cloudFile("/a.txt", "cloud!".toByteArray()))

        engine().syncOnce()

        assertEquals("cloud!", Files.readString(local), "the cloud's bytes take the path")
        assertEquals("edited!", Files.readString(copies().single()), "the user's edit survives as a conflict copy")
        assertEquals("cloud!", String(provider.files.getValue("/a.txt")), "the blind recovery upload must not overwrite the newcomer")
        assertTrue(provider.uploadedPaths.none { it == "/a.txt" }, "no write-guarded upload replaces the newcomer: ${provider.uploadedPaths}")
    }

    @Test
    fun `a pending upload whose bytes match the remote newcomer is adopted without a transfer`() = runTest {
        val local = syncRoot.resolve("a.txt")
        Files.writeString(local, "same!")
        seedPendingRow("/a.txt", size = "same!".length.toLong())
        provider.deltaItems = listOf(cloudFile("/a.txt", "same!".toByteArray()))

        engine().syncOnce()

        assertEquals(emptyList(), provider.downloadByIdCalls, "identical bytes are adopted, not re-downloaded")
        assertTrue(provider.uploadedPaths.isEmpty(), "nothing uploads: ${provider.uploadedPaths}")
        assertTrue(copies().isEmpty(), "no conflict copy for identical bytes")
        assertEquals("same!", Files.readString(local))
        val row = assertNotNull(db.getEntry("/a.txt"))
        assertEquals("id-/a.txt", row.remoteId, "the pending row is promoted to a tracked one")
    }

    // Plan-level: the reconciler routes the pending pair through the both-new branch.
    private fun planReconciler(sameContent: ((Path, CloudItem) -> Boolean?)? = null) =
        Reconciler(
            db,
            syncRoot,
            ConflictPolicy.KEEP_BOTH,
            sameContent = sameContent,
        )

    private fun pendingPlanRow(path: String) =
        SyncEntry(
            path = path,
            remoteId = null,
            remoteHash = null,
            remoteSize = 0,
            remoteModified = null,
            localMtime = Files.getLastModifiedTime(syncRoot.resolve(path.removePrefix("/"))).toMillis(),
            localSize = Files.size(syncRoot.resolve(path.removePrefix("/"))),
            isFolder = false,
            isPinned = false,
            isHydrated = true,
            lastSynced = Instant.EPOCH,
        )

    @Test
    fun `an unchanged pending upload against a remote newcomer plans the both-new conflict, not a download`() {
        Files.writeString(syncRoot.resolve("a.txt"), "local!")
        db.upsertEntry(pendingPlanRow("/a.txt"))

        val actions =
            planReconciler { _, _ -> false }.reconcile(
                remoteChanges = mapOf("/a.txt" to cloudFile("/a.txt", "cloud!".toByteArray())),
                localChanges = emptyMap(),
            )

        assertTrue(actions.none { it is SyncAction.DownloadContent }, "the local bytes are not overwritten: $actions")
        assertTrue(actions.any { it is SyncAction.Conflict }, "keep both: $actions")
    }

    @Test
    fun `an unchanged pending upload with identical remote bytes plans the adoption`() {
        Files.writeString(syncRoot.resolve("a.txt"), "same!")
        db.upsertEntry(pendingPlanRow("/a.txt"))

        val actions =
            planReconciler { _, _ -> true }.reconcile(
                remoteChanges = mapOf("/a.txt" to cloudFile("/a.txt", "same!".toByteArray())),
                localChanges = emptyMap(),
            )

        assertTrue(actions.none { it is SyncAction.DownloadContent }, "identical bytes are adopted: $actions")
        assertTrue(actions.none { it is SyncAction.Conflict }, "identical bytes are adopted: $actions")
        assertTrue(actions.any { it is SyncAction.CreatePlaceholder }, "the adoption records the remote item: $actions")
    }

    @Test
    fun `a modified pending upload against a remote newcomer plans the both-new conflict, not a blind upload`() {
        Files.writeString(syncRoot.resolve("a.txt"), "edited!")
        db.upsertEntry(pendingPlanRow("/a.txt"))

        val actions =
            planReconciler { _, _ -> false }.reconcile(
                remoteChanges = mapOf("/a.txt" to cloudFile("/a.txt", "cloud!".toByteArray())),
                localChanges = mapOf("/a.txt" to ChangeState.MODIFIED),
            )

        assertTrue(actions.none { it is SyncAction.Upload }, "the recovery loop's blind upload has no write guard: $actions")
        assertTrue(actions.any { it is SyncAction.Conflict }, "keep both: $actions")
    }

    // #743 finding 2: the both-modified content comparison reads the local file; a file locked by
    // another application must keep the conflict, not abort the pass (the scanner already tolerates
    // the same condition, #1056).
    @Test
    fun `an unreadable local file during a both-modified comparison keeps the conflict instead of failing the pass`() {
        Files.writeString(syncRoot.resolve("c.txt"), "MINE")
        db.upsertEntry(
            SyncEntry(
                path = "/c.txt",
                remoteId = "id-c",
                remoteHash = "base",
                remoteSize = 4,
                remoteModified = Instant.parse("2026-01-01T00:00:00Z"),
                localMtime = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(),
                localSize = 4,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.parse("2026-01-01T00:00:00Z"),
            ),
        )
        val remote = cloudFile("/c.txt", "THEIRS".toByteArray(), id = "id-c").copy(modified = Instant.parse("2026-01-02T00:00:00Z"))

        val actions =
            planReconciler { _, _ -> throw java.io.IOException("locked by another process") }.reconcile(
                remoteChanges = mapOf("/c.txt" to remote),
                localChanges = mapOf("/c.txt" to ChangeState.MODIFIED),
            )

        assertTrue(actions.any { it is SyncAction.Conflict }, "an unreadable file answers not-proven-same: $actions")
    }

    // A pending upload's bytes were never synced, so "cannot tell" is not evidence of a match: the
    // #97 adoption rule (cannot tell adopts) holds only for the real both-new pair, where the local
    // bytes stayed on disk across a re-widened scope.
    @Test
    fun `an unreadable pending file during the both-new comparison keeps both rather than failing the pass`() {
        Files.writeString(syncRoot.resolve("a.txt"), "local!")
        db.upsertEntry(pendingPlanRow("/a.txt"))

        val actions =
            planReconciler { _, _ -> throw java.io.IOException("locked by another process") }.reconcile(
                remoteChanges = mapOf("/a.txt" to cloudFile("/a.txt", "cloud!".toByteArray())),
                localChanges = emptyMap(),
            )

        assertTrue(actions.none { it is SyncAction.DownloadContent }, "the local bytes are not overwritten: $actions")
        assertTrue(actions.any { it is SyncAction.Conflict }, "an unreadable pending file is not proven same: keep both: $actions")
    }

    @Test
    fun `a proven-match pending file during the both-new comparison still adopts`() {
        Files.writeString(syncRoot.resolve("a.txt"), "same!")
        db.upsertEntry(pendingPlanRow("/a.txt"))

        val actions =
            planReconciler { _, _ -> true }.reconcile(
                remoteChanges = mapOf("/a.txt" to cloudFile("/a.txt", "same!".toByteArray())),
                localChanges = emptyMap(),
            )

        assertTrue(actions.none { it is SyncAction.DownloadContent }, "a proven match adopts: $actions")
        assertTrue(actions.any { it is SyncAction.CreatePlaceholder }, "a proven match adopts: $actions")
    }

    // No-hash provider (the CloudProvider default: Internxt, localfs): the content comparison cannot
    // tell, and the pending pair must keep both. The engine-level test pins the end-to-end outcome:
    // one conflict copy holding the local bytes, the newcomer at the path, the row not promoted.
    @Test
    fun `a pending upload against an equal-size newcomer on a no-hash provider is kept both`() = runTest {
        provider.hashAlgorithmOverride = null
        val local = syncRoot.resolve("a.txt")
        Files.writeString(local, "local!")
        seedPendingRow("/a.txt", size = "local!".length.toLong())
        provider.deltaItems = listOf(cloudFile("/a.txt", "cloud!".toByteArray()))

        engine().syncOnce()

        assertEquals("cloud!", Files.readString(local), "the cloud's bytes take the path")
        assertEquals("local!", Files.readString(copies().single()), "the local bytes survive as a conflict copy")
        assertTrue(copies().size == 1, "exactly one conflict copy: ${copies()}")
    }

    @Test
    fun `a readable file still proves the both-modified pair different and keeps the conflict`() {
        Files.writeString(syncRoot.resolve("c.txt"), "MINE")
        db.upsertEntry(
            SyncEntry(
                path = "/c.txt",
                remoteId = "id-c",
                remoteHash = "base",
                remoteSize = 4,
                remoteModified = Instant.parse("2026-01-01T00:00:00Z"),
                localMtime = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(),
                localSize = 4,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.parse("2026-01-01T00:00:00Z"),
            ),
        )
        val remote = cloudFile("/c.txt", "THEIRS".toByteArray(), id = "id-c").copy(modified = Instant.parse("2026-01-02T00:00:00Z"))

        val actions =
            planReconciler { _, _ -> false }.reconcile(
                remoteChanges = mapOf("/c.txt" to remote),
                localChanges = mapOf("/c.txt" to ChangeState.MODIFIED),
            )

        assertTrue(actions.any { it is SyncAction.Conflict }, "proven different keeps the conflict: $actions")
    }

    // A both-modified pass over a readable file does not silently change: prove the injected
    // comparator is actually consulted (true converges, no conflict).
    @Test
    fun `a readable file proven same converges the both-modified pair without a conflict`() {
        Files.writeString(syncRoot.resolve("c.txt"), "MINE")
        db.upsertEntry(
            SyncEntry(
                path = "/c.txt",
                remoteId = "id-c",
                remoteHash = "base",
                remoteSize = 4,
                remoteModified = Instant.parse("2026-01-01T00:00:00Z"),
                localMtime = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(),
                localSize = 4,
                isFolder = false,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.parse("2026-01-01T00:00:00Z"),
            ),
        )
        val remote = cloudFile("/c.txt", "THEIRS".toByteArray(), id = "id-c").copy(modified = Instant.parse("2026-01-02T00:00:00Z"))

        val actions =
            planReconciler { _, _ -> true }.reconcile(
                remoteChanges = mapOf("/c.txt" to remote),
                localChanges = mapOf("/c.txt" to ChangeState.MODIFIED),
            )

        assertTrue(actions.none { it is SyncAction.Conflict }, "proven same converges: $actions")
        assertTrue(actions.any { it is SyncAction.CreatePlaceholder }, "proven same converges: $actions")
    }
}
