package org.krost.unidrive.tracking

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A provider that reports no content hash and no discriminating etag (Internxt) leaves
 * `remoteEtag == null` on every row it synced. A remote deletion of such a file must still
 * propagate: the row's remote side WAS established (it carries a remote id), so its absence from a
 * complete listing is a genuine deletion, exactly as for a provider that reports an etag. The
 * reconciler's tracking-set rules stay: untracked paths never delete, an incomplete listing never
 * counts absence as deletion, and a row whose remote side was never established is not "gone".
 */
class TrackingEngineHashlessRemoteDeleteTest {
    private val rec = TrackingReconciler()

    private fun local(
        exists: Boolean,
        hash: String? = "lh",
    ) = LocalObservation(exists, hash, if (exists) 10L else null, if (exists) Instant.EPOCH else null, null)

    private fun remote(
        exists: Boolean,
        etag: String? = null,
    ) = RemoteObservation(exists, if (exists) "rid" else null, etag, if (exists) 10L else null, null, if (exists) Instant.EPOCH else null)

    private fun hashlessTrack(
        state: TrackState = TrackState.TrackedSynced,
        remoteFileId: String? = "rid",
    ) = TrackingRecord(
        path = "/p",
        providerId = "fake",
        remoteFileId = remoteFileId,
        state = state,
        localHash = "lh",
        localSize = 10L,
        remoteEtag = null,
        remoteSize = 10L,
        lastSynced = Instant.EPOCH,
    )

    // ---- pure reconciler ----

    @Test
    fun `reconciler propagates a remote delete for a synced row whose remote etag is null`() {
        val action = rec.reconcile("/p", local(exists = true), remote(exists = false), hashlessTrack())
        assertEquals(ReconcileAction.PropagateRemoteDelete("/p"), action)
    }

    @Test
    fun `reconciler never uploads a locally edited hashless row against a dead remote id`() {
        val action = rec.reconcile("/p", local(exists = true, hash = "edited"), remote(exists = false), hashlessTrack())
        assertFalse(action is ReconcileAction.UploadLocal, "no upload against a dead remote id: $action")
    }

    @Test
    fun `guard - a hashless row whose remote side was never established is not remote-gone`() {
        // PendingUpload with no remote id: the upload never finished, so absence is not a deletion.
        val pending = hashlessTrack(state = TrackState.PendingUpload, remoteFileId = null)
        val action = rec.reconcile("/p", local(exists = true), remote(exists = false), pending)
        assertEquals(ReconcileAction.UploadLocal("/p"), action)
    }

    @Test
    fun `guard - a hashless row with the remote still present and unchanged is a no-op`() {
        val action = rec.reconcile("/p", local(exists = true), remote(exists = true), hashlessTrack())
        assertEquals(ReconcileAction.NoOp("/p"), action)
    }

    @Test
    fun `guard - an untracked path never deletes`() {
        val action = rec.reconcile("/p", local(exists = true), remote(exists = false), null)
        assertFalse(action is ReconcileAction.PropagateRemoteDelete)
        assertFalse(action is ReconcileAction.PropagateLocalDelete)
    }

    // ---- engine, end to end on a full-listing hashless provider ----

    private lateinit var workDir: Path
    private lateinit var syncRoot: Path
    private lateinit var dbPath: Path
    private lateinit var provider: HashlessFakeProvider
    private val permissive = BatchGuard(maxDeleteRatio = 1.0, maxDeleteAbsolute = Int.MAX_VALUE)

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("tracking-hashless-delete")
        syncRoot = workDir.resolve("sync-root").also { Files.createDirectories(it) }
        dbPath = workDir.resolve("tracking.db")
        provider =
            HashlessFakeProvider().apply {
                deltaIsFullListing = true // a full listing: absence on a complete page is a deletion
            }
    }

    @AfterTest
    fun tearDown() {
        workDir.toFile().deleteRecursively()
    }

    private fun open() = SqliteTrackingSet(dbPath).also { it.initialize() }

    private fun engine(tracking: TrackingSet) = TrackingEngine(provider, tracking, syncRoot, batchGuard = permissive)

    @Test
    fun `a remote deletion propagates for a provider that reports no hash or etag`() {
        provider.files["/a.txt"] = "v1".toByteArray()
        provider.files["/keep.txt"] = "k1".toByteArray()
        val tracking = open()
        try {
            engine(tracking).syncOnce()
            assertTrue(Files.exists(syncRoot.resolve("a.txt")), "precondition: pass 1 downloads the file")
            assertNull(tracking.lookup("/a.txt")?.remoteEtag, "precondition: the hashless provider leaves no etag")

            provider.files.remove("/a.txt")
            engine(tracking).syncOnce()

            assertFalse(Files.exists(syncRoot.resolve("a.txt")), "the remotely deleted file must be deleted locally")
            assertTrue(Files.exists(syncRoot.resolve("keep.txt")), "an unrelated file must survive")
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `guard - an incomplete listing never deletes a hashless file`() {
        provider.files["/a.txt"] = "v1".toByteArray()
        val tracking = open()
        try {
            engine(tracking).syncOnce()
            provider.files.remove("/a.txt")
            provider.deltaComplete = false
            engine(tracking).syncOnce()
            assertTrue(Files.exists(syncRoot.resolve("a.txt")), "absence on an incomplete listing is not a deletion")
            assertNotNull(tracking.lookup("/a.txt"))
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `guard - an untracked local file is never deleted when nothing is tracked`() {
        Files.writeString(syncRoot.resolve("local-only.txt"), "mine")
        val tracking = open()
        try {
            engine(tracking).syncOnce()
            assertTrue(Files.exists(syncRoot.resolve("local-only.txt")), "an untracked local file must never be deleted")
            assertTrue(provider.files.isEmpty() || provider.files.keys.all { it == "/local-only.txt" })
        } finally {
            tracking.close()
        }
    }
}
