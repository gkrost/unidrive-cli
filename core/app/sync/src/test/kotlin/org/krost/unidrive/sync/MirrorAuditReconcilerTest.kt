package org.krost.unidrive.sync

import org.krost.unidrive.CloudItem
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Mirror-engine audit candidates X1, X5 and X6 (gkrost/unidrive-cli#55), each as the smallest reconcile call that shows it.
 */
class MirrorAuditReconcilerTest {
    private lateinit var db: StateDatabase
    private lateinit var syncRoot: Path

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-audit55-root")
        db = StateDatabase(Files.createTempDirectory("unidrive-audit55-db").resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun cloudItem(
        path: String,
        size: Long,
        hash: String?,
    ) = CloudItem(
        id = "id-$path",
        name = path.substringAfterLast("/"),
        path = path,
        size = size,
        isFolder = false,
        modified = Instant.parse("2026-03-28T12:00:00Z"),
        created = null,
        hash = hash,
        mimeType = "text/plain",
    )

    // X1: a first sync into an existing folder meets a local and a remote file of one path and one size. Size alone is
    // not content: with a content check that says "different", the pair must be a conflict, not an adoption.
    @Test
    fun `X1 equal size on both sides is not adopted when the content differs`() {
        Files.writeString(syncRoot.resolve("a.txt"), "AAAA")
        val reconciler = Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH, sameContent = { _, _ -> false })
        val actions =
            reconciler.reconcile(
                mapOf("/a.txt" to cloudItem("/a.txt", size = 4, hash = "hash-of-other-bytes")),
                mapOf("/a.txt" to ChangeState.NEW),
            )
        assertFalse(actions.any { it is SyncAction.CreatePlaceholder }, "adopted a different file of equal size: $actions")
        assertTrue(actions.any { it is SyncAction.Conflict }, "expected a conflict, got $actions")
    }

    // X5: a profile synced unscoped and narrowed to /A/B keeps /A hydrated; a scoped scan reports an ancestor of the
    // scope that is gone locally as DELETED, and the plan then deletes it in the cloud, with everything outside the scope.
    @Test
    fun `X5 a scoped run plans no DeleteRemote for an ancestor of the scope`() {
        db.upsertEntry(
            SyncEntry(
                path = "/A",
                remoteId = "id-A",
                remoteHash = null,
                remoteSize = 0,
                remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                localMtime = 1L,
                localSize = 0,
                isFolder = true,
                isPinned = false,
                isHydrated = true,
                lastSynced = Instant.now(),
            ),
        )
        val actions =
            Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH)
                .reconcile(emptyMap(), mapOf("/A" to ChangeState.DELETED), syncPaths = listOf("/A/B"))
        assertEquals(emptyList(), actions.filterIsInstance<SyncAction.DeleteRemote>(), "planned $actions")
    }

    private fun conflictPolicyAt(
        overrides: Map<String, ConflictPolicy>,
        path: String,
    ): ConflictPolicy {
        val local = syncRoot.resolve(path.removePrefix("/"))
        Files.createDirectories(local.parent)
        Files.writeString(local, "local")
        val actions =
            Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH, conflictOverrides = overrides).reconcile(
                mapOf(path to cloudItem(path, size = 99, hash = "h")),
                mapOf(path to ChangeState.NEW),
            )
        return actions.filterIsInstance<SyncAction.Conflict>().single().policy
    }

    // X6: an override for `Docs` is a folder, not a string prefix.
    @Test
    fun `X6 an override for Docs does not apply to Docs-old`() {
        val policy = conflictPolicyAt(mapOf("Docs" to ConflictPolicy.LAST_WRITER_WINS), "/Docs-old/x.txt")
        assertEquals(ConflictPolicy.KEEP_BOTH, policy)
    }

    // X6: the most specific override wins, whatever the map order.
    @Test
    fun `X6 the longest matching override wins`() {
        val policy =
            conflictPolicyAt(
                linkedMapOf("Docs" to ConflictPolicy.LAST_WRITER_WINS, "Docs/Sub" to ConflictPolicy.KEEP_BOTH),
                "/Docs/Sub/x.txt",
            )
        assertEquals(ConflictPolicy.KEEP_BOTH, policy)
    }
}
