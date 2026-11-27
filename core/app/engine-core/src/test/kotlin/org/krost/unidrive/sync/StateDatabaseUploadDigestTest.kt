package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.sync.model.UploadDigest
import org.krost.unidrive.testing.TestScratch
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * gkrost/unidrive-cli#1018 item 16: the digest of what this machine's upload sent is compared when a download fetches the version
 * that upload produced. The hydration of an evicted cache copy is that download, so the digest must outlive the row's local bytes,
 * which the local hash does not; and the version key must tell a new version from the same one restamped.
 */
class StateDatabaseUploadDigestTest {
    private val db = StateDatabase(TestScratch.directory("ud-upload-digest").resolve("state.db")).also { it.initialize() }

    @AfterTest
    fun close() = db.close()

    private val uploaded =
        SyncEntry(
            path = "/a.txt",
            remoteId = "id-a",
            remoteHash = null,
            remoteSize = 5,
            remoteModified = Instant.parse("2026-11-01T10:00:00Z"),
            localMtime = 1_700_000_000_000,
            localSize = 5,
            isFolder = false,
            isPinned = false,
            isHydrated = true,
            lastSynced = Instant.parse("2026-11-01T10:00:00Z"),
            localHash = "aa",
        ).let { it.copy(uploadDigest = UploadDigest("aa", it.remoteVersionKey)) }

    @Test
    fun `the upload digest outlives the local bytes, where the local hash does not`() {
        db.upsertEntry(uploaded)
        assertEquals(uploaded.uploadDigest, db.getEntry("/a.txt")?.uploadDigest)

        // A row written without local bytes (the merge of a delivery after the cache copy was evicted).
        db.upsertEntry(uploaded.copy(isHydrated = false))

        val row = db.getEntry("/a.txt")
        assertNull(row?.localHash)
        assertEquals(uploaded.uploadDigest, row?.uploadDigest)
    }

    @Test
    fun `a version named by a hash or a token keeps its key when only its time moves, one named by nothing else does not`() {
        val restamped = Instant.parse("2026-11-01T10:00:01Z")
        val byHash = uploaded.copy(remoteHash = "uuid|t|5")
        val byToken = uploaded.copy(remoteVersion = "etag-1")

        assertEquals(byHash.remoteVersionKey, byHash.copy(remoteModified = restamped).remoteVersionKey)
        assertEquals(byToken.remoteVersionKey, byToken.copy(remoteModified = restamped).remoteVersionKey)
        assertNotEquals(uploaded.remoteVersionKey, uploaded.copy(remoteModified = restamped).remoteVersionKey)
        assertNotEquals(byHash.remoteVersionKey, byHash.copy(remoteHash = "uuid|t2|5").remoteVersionKey)
    }
}
