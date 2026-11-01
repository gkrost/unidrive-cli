package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** gkrost/unidrive-cli#29: `daemon.status` counts the strict-name blocks with one query, by the reason's prefix as text. */
class NameBlockCountTest {
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        db = StateDatabase(Files.createTempDirectory("unidrive-name-block-count").resolve("state.db")).also { it.initialize() }
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
    fun `only alive rows blocked with the reason prefix are counted`() {
        for (p in listOf("/a", "/b", "/c", "/d", "/e")) db.upsertEntry(row(p))
        db.markNameBlocked("/a", "name_incompatible: windows_character", null)
        db.markNameBlocked("/b", "name_incompatible: trailing_whitespace", null)
        db.markNameBlocked("/c", "similar_name", "C")
        db.markNameBlocked("/d", "nameXincompatible: look-alike", null) // the underscore is not a wildcard
        db.markNameBlocked("/e", "name_incompatible: windows_character", null)
        db.setStatusTrashed("id-/e")

        assertEquals(2, db.countNameBlocked("name_incompatible"))
        assertEquals(0, StateDatabase(Files.createTempDirectory("unidrive-name-block-count-empty").resolve("state.db")).also { it.initialize() }.countNameBlocked("name_incompatible"))
    }
}
