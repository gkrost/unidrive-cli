package org.krost.unidrive.tracking

import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Data-loss safety around the hashless remote-delete rule: a row is remote-gone when its remote side was
 * established (an etag or a remote id), so a hashless row follows exactly the rules an etag row already has.
 */
class TrackingHashlessRemoteDeleteSafetyTest : HashlessEngineTestBase() {
    private val rec = TrackingReconciler()

    private fun local(
        exists: Boolean,
        hash: String? = "lh",
    ) = LocalObservation(exists, if (exists) hash else null, if (exists) 10L else null, if (exists) Instant.EPOCH else null, null)

    private val goneRemote = RemoteObservation(false, null, null, null, null, null)

    private fun row(
        etag: String?,
        remoteFileId: String? = "rid",
        state: TrackState = TrackState.TrackedSynced,
        localHash: String? = "lh",
    ) = TrackingRecord(
        path = "/p",
        providerId = "fake",
        remoteFileId = remoteFileId,
        state = state,
        localHash = localHash,
        localSize = localHash?.let { 10L },
        remoteEtag = etag,
        remoteSize = 10L,
        lastSynced = Instant.EPOCH,
    )

    // ---- 1. edited / unchanged / locally deleted, hashless == etag ----

    @Test
    fun `a hashless row reconciles exactly like an etag row for every local state against a gone remote`() {
        for (l in listOf(local(true), local(true, "edited"), local(false))) {
            val hashless = rec.reconcile("/p", l, goneRemote, row(etag = null))
            val withEtag = rec.reconcile("/p", l, goneRemote, row(etag = "e"))
            assertEquals(withEtag, hashless, "local=$l")
        }
    }

    @Test
    fun `an edited hashless row with a gone remote is a remote-delete intent never an upload`() {
        val action = rec.reconcile("/p", local(true, "edited"), goneRemote, row(etag = null))
        assertEquals(ReconcileAction.PropagateRemoteDelete("/p"), action)
    }

    @Test
    fun `a hashless row gone on both sides is a no-op`() {
        assertEquals(ReconcileAction.NoOp("/p"), rec.reconcile("/p", local(false), goneRemote, row(etag = null)))
    }

    @Test
    fun `an etag row without a remote id is still remote-gone`() {
        // Pins the etag clause of the established-remote rule: dropping it would leave this row stuck.
        val action = rec.reconcile("/p", local(true), goneRemote, row(etag = "e", remoteFileId = null))
        assertEquals(ReconcileAction.PropagateRemoteDelete("/p"), action)
    }

    @Test
    fun `a claim-style pending upload with a remote id and no local snapshot is remote-gone like an etag row`() {
        val claim = { etag: String? -> row(etag = etag, state = TrackState.PendingUpload, localHash = null) }
        val hashless = rec.reconcile("/p", local(false), goneRemote, claim(null))
        assertEquals(rec.reconcile("/p", local(false), goneRemote, claim("e")), hashless)
        assertEquals(ReconcileAction.PropagateRemoteDelete("/p"), hashless)
    }

    // ---- 2. PendingUpload rows ----

    @Test
    fun `a pending upload with a stale remote id and local present re-derives the upload`() {
        val pending = row(etag = null, state = TrackState.PendingUpload)
        assertEquals(ReconcileAction.UploadLocal("/p"), rec.reconcile("/p", local(true), goneRemote, pending))
    }

    @Test
    fun `a pending upload whose local file is also gone is a no-op never a delete`() {
        val pending = row(etag = null, state = TrackState.PendingUpload)
        assertEquals(ReconcileAction.NoOp("/p"), rec.reconcile("/p", local(false), goneRemote, pending))
        val unproven = row(etag = null, remoteFileId = null, state = TrackState.PendingUpload, localHash = null)
        assertEquals(ReconcileAction.NoOp("/p"), rec.reconcile("/p", local(false), goneRemote, unproven))
    }

    @Test
    fun `a synced row with neither etag nor remote id is never remote-gone`() {
        // The only test that pins the "neither" clause: a PendingUpload row with a null id and the local file present
        // is answered by the PendingUpload short-circuit before remoteGone is read, so it cannot tell the rules apart.
        val action = rec.reconcile("/p", local(true), goneRemote, row(etag = null, remoteFileId = null))
        assertFalse(action is ReconcileAction.PropagateRemoteDelete, "$action")
    }

    // ---- engine ----

    @Test
    fun `a locally edited hashless file whose remote was deleted is kept and not re-uploaded`() {
        provider.deltaIsFullListing = true
        provider.files["/a.txt"] = "v1".toByteArray()
        val tracking = open()
        try {
            engine(tracking).syncOnce()
            Files.writeString(syncRoot.resolve("a.txt"), "edited locally, longer")
            provider.files.remove("/a.txt")

            engine(tracking).syncOnce()

            assertEquals("edited locally, longer", Files.readString(syncRoot.resolve("a.txt")), "the edit must survive")
            assertFalse(provider.files.containsKey("/a.txt"), "no upload against the dead remote id")
            assertNotNull(tracking.lookup("/a.txt"), "the row stays so the collision is retried, not forgotten")
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `a hashless file deleted on both sides drops its row and resurrects nothing`() {
        provider.deltaIsFullListing = true
        provider.files["/a.txt"] = "v1".toByteArray()
        val tracking = open()
        try {
            engine(tracking).syncOnce()
            Files.delete(syncRoot.resolve("a.txt"))
            provider.files.remove("/a.txt")

            engine(tracking).syncOnce()

            assertFalse(Files.exists(syncRoot.resolve("a.txt")))
            assertTrue(provider.files.isEmpty())
            assertNull(tracking.lookup("/a.txt"))
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `an empty tracking set deletes nothing`() {
        provider.deltaIsFullListing = true
        Files.writeString(syncRoot.resolve("mine.txt"), "mine")
        val tracking = open()
        try {
            val report = engine(tracking).syncOnce()
            assertTrue(report.plan.none { it is ReconcileAction.PropagateRemoteDelete || it is ReconcileAction.PropagateLocalDelete })
            assertTrue(Files.exists(syncRoot.resolve("mine.txt")))
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `the batch guard still holds back hashless remote deletes and the cursor does not advance`() {
        provider.deltaIsFullListing = true
        for (n in 1..4) provider.files["/f$n.txt"] = "v$n".toByteArray()
        val tracking = open()
        try {
            provider.cursorValue = "c1"
            engine(tracking).syncOnce()
            assertEquals("c1", tracking.loadDeltaCursor(), "precondition: a clean pass saves the cursor")
            provider.cursorValue = "c2"
            provider.files.remove("/f1.txt")
            provider.files.remove("/f2.txt")
            provider.files.remove("/f3.txt")

            val report = engine(tracking, BatchGuard(maxDeleteRatio = 0.5, maxDeleteAbsolute = 50)).syncOnce()

            assertTrue(report.guardVerdict is BatchGuard.Verdict.Deny)
            assertTrue(report.plan.count { it is ReconcileAction.PropagateRemoteDelete } == 3, "the plan sees the deletes")
            assertTrue(report.effectivePlan.none { it is ReconcileAction.PropagateRemoteDelete })
            for (n in 1..4) assertTrue(Files.exists(syncRoot.resolve("f$n.txt")), "f$n must survive")
            assertEquals("c1", tracking.loadDeltaCursor(), "the cursor stays held while the guard defers the deletes")
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `an incomplete listing suppresses hashless remote deletes`() {
        provider.deltaIsFullListing = true
        provider.files["/a.txt"] = "v1".toByteArray()
        provider.files["/b.txt"] = "v2".toByteArray()
        val tracking = open()
        try {
            provider.cursorValue = "c1"
            engine(tracking).syncOnce()
            provider.cursorValue = "c2"
            provider.files.remove("/a.txt")
            provider.deltaComplete = false

            val report = engine(tracking).syncOnce()

            assertTrue(report.effectivePlan.none { it is ReconcileAction.PropagateRemoteDelete })
            assertTrue(Files.exists(syncRoot.resolve("a.txt")))
            assertEquals("c1", tracking.loadDeltaCursor(), "an incomplete pass never advances the cursor")
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `absence from an incremental delta never marks a hashless row gone`() {
        // deltaIsFullListing stays false: the second pass resumes from the saved cursor, so a tracked path the
        // delta does not mention is unchanged-present, however the provider's own listing looks.
        provider.files["/a.txt"] = "v1".toByteArray()
        val tracking = open()
        try {
            engine(tracking).syncOnce()
            assertNotNull(tracking.loadDeltaCursor(), "precondition: the first pass saved a cursor")
            provider.files.remove("/a.txt")

            val report = engine(tracking).syncOnce()

            assertTrue(report.plan.none { it is ReconcileAction.PropagateRemoteDelete })
            assertTrue(Files.exists(syncRoot.resolve("a.txt")))
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `a clean pass that deletes a hashless file advances the cursor`() {
        provider.deltaIsFullListing = true
        provider.files["/a.txt"] = "v1".toByteArray()
        provider.files["/b.txt"] = "v2".toByteArray()
        val tracking = open()
        try {
            provider.cursorValue = "c1"
            engine(tracking).syncOnce()
            provider.cursorValue = "c2"
            provider.files.remove("/a.txt")

            engine(tracking).syncOnce()

            assertFalse(Files.exists(syncRoot.resolve("a.txt")))
            assertEquals("c2", tracking.loadDeltaCursor(), "the cursor advances once the plan applied")
        } finally {
            tracking.close()
        }
    }

    @Test
    fun `a claim-style pending upload row whose local and remote are both gone settles without failing`() {
        provider.deltaIsFullListing = true
        val tracking = open()
        try {
            tracking.upsert(row(etag = null, state = TrackState.PendingUpload, localHash = null).copy(providerId = provider.id))

            val report = engine(tracking).syncOnce()

            assertTrue(report.applied.none { it.outcome == ApplyOutcome.FAILED }, "${report.applied}")
            assertNull(tracking.lookup("/p"), "nothing is left to sync for the path, so the row settles")
        } finally {
            tracking.close()
        }
    }
}
