package org.krost.unidrive.sync.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** gkrost/unidrive-cli#849: what a download stamps (`stampedRemoteIfVersionMovedFrom`) and what it leaves alone. */
class SyncEntryStampTest {
    private fun row(
        hash: String? = "h1",
        version: String? = null,
    ) = SyncEntry(
        path = "/f.txt",
        remoteId = "id-f",
        remoteHash = hash,
        remoteVersion = version,
        remoteSize = 5,
        remoteModified = Instant.parse("2026-11-01T10:00:00Z"),
        localMtime = null,
        localSize = null,
        isFolder = false,
        isPinned = false,
        isHydrated = false,
        lastSynced = Instant.parse("2026-11-01T10:00:00Z"),
        changedBy = "local",
        changedAtMs = 7,
    )

    @Test
    fun `a download of the version the row recorded changes nothing`() {
        val before = row()
        val after = before.copy(isHydrated = true, localMtime = 1, localSize = 5, lastSynced = Instant.parse("2026-11-02T10:00:00Z"))
        assertEquals(after, after.stampedRemoteIfVersionMovedFrom(before))
    }

    @Test
    fun `a download that found another hash, token, size or item is a change the cloud made`() {
        val before = row(version = "v1")
        val others =
            listOf(
                before.copy(remoteHash = "h2"),
                before.copy(remoteVersion = "v2"),
                before.copy(remoteSize = 9),
                before.copy(remoteId = "id-other"),
            )
        for (after in others) {
            val stamped = after.stampedRemoteIfVersionMovedFrom(before)
            assertEquals("remote", stamped.changedBy, "$after")
            assertTrue(assertNotNull(stamped.changedAtMs) > 7)
        }
    }

    @Test
    fun `a hash or token the row did not know yet is learned, not changed`() {
        val before = row(hash = null, version = null)
        val after = before.copy(remoteHash = "h1", remoteVersion = "v1")
        assertEquals(after, after.stampedRemoteIfVersionMovedFrom(before))
    }
}
