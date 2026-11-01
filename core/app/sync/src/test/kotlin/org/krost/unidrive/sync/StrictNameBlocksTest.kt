package org.krost.unidrive.sync

import org.krost.unidrive.engine.StrictNameBlocks
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * gkrost/unidrive-cli#29: at start the strict-name blocks follow the provider's mode, for rows that no delivery touches: a mode turned
 * on marks the settled rows whose names it refuses, a mode turned off lifts its own blocks, and a block of another kind stays.
 */
class StrictNameBlocksTest {
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    @BeforeTest
    fun setUp() {
        db = StateDatabase(Files.createTempDirectory("unidrive-strict-sweep").resolve("state.db")).also { it.initialize() }
        provider = FakeCloudProvider().also { it.strictNameMode = true }
        for (p in listOf("/ok.txt", "/bad.txt", "/twin.txt")) db.upsertEntry(row(p))
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun row(path: String) =
        SyncEntry(
            path = path,
            remoteId = "id-$path",
            remoteHash = null,
            remoteSize = 1,
            remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
            localMtime = null,
            localSize = null,
            isFolder = false,
            isPinned = false,
            isHydrated = false,
            lastSynced = Instant.now(),
        )

    @Test
    fun `a mode turned on marks the settled rows it refuses and only those`() {
        provider.existingNameIssues["/bad.txt"] = "name_incompatible: windows_character"

        assertEquals(1, StrictNameBlocks.reconcileAll(db, provider))

        assertEquals("name_incompatible: windows_character", db.nameBlock("/bad.txt")?.reason)
        assertNull(db.nameBlock("/ok.txt"))
        assertEquals(0, StrictNameBlocks.reconcileAll(db, provider), "a second run changes nothing")
    }

    @Test
    fun `a mode turned off lifts its own blocks and leaves a block of another kind`() {
        db.markNameBlocked("/bad.txt", "name_incompatible: windows_character", null)
        db.markNameBlocked("/twin.txt", "similar_name", "Twin.txt")
        provider.strictNameMode = false

        assertEquals(1, StrictNameBlocks.reconcileAll(db, provider))

        assertNull(db.nameBlock("/bad.txt"))
        assertEquals("similar_name", db.nameBlock("/twin.txt")?.reason)
    }

    @Test
    fun `a provider without a strict-name mode is left alone`() {
        provider.strictNameMode = null
        provider.existingNameIssues["/bad.txt"] = "name_incompatible: windows_character"

        assertEquals(0, StrictNameBlocks.reconcileAll(db, provider))

        assertNull(db.nameBlock("/bad.txt"))
    }
}
