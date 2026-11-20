package org.krost.unidrive.cli

import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import org.krost.unidrive.testing.TestScratch
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue

/** #841 (#749 finding 8): the hydration-drift check reads its sample (or, under `--full`, every hydrated file) and nothing else. */
class DoctorHydrationSampleTest {
    private val t0 = Instant.parse("2026-03-28T12:00:00Z")

    private fun row(
        path: String,
        hydrated: Boolean,
        isFolder: Boolean = false,
    ) = SyncEntry(
        path = path,
        remoteId = "id$path",
        remoteHash = null,
        remoteSize = 1,
        remoteModified = t0,
        localMtime = null,
        localSize = null,
        isFolder = isFolder,
        isPinned = false,
        isHydrated = hydrated,
        lastSynced = t0,
    )

    @Test
    fun `the drift check samples the hydrated files and reads all of them under full`() {
        val dir = TestScratch.directory("doctor-sample")
        val db = StateDatabase(dir.resolve("state.db"))
        db.initialize()
        try {
            val sample = DoctorCommand.HYDRATION_SAMPLE
            db.batch {
                db.upsertEntry(row("/dir", hydrated = true, isFolder = true))
                for (i in 0 until sample + 30) db.upsertEntry(row("/f$i", hydrated = true))
                for (i in 0 until 40) db.upsertEntry(row("/stub$i", hydrated = false))
            }
            val root = dir.resolve("root")

            val sampled = DoctorCommand().checkHydrationDrift(db, root, full = false).summary
            assertTrue(sampled.startsWith("$sample of $sample hydrated rows") && "(sample $sample rows)" in sampled, sampled)
            val all = DoctorCommand().checkHydrationDrift(db, root, full = true).summary
            assertTrue(all.startsWith("${sample + 30} of ${sample + 30} hydrated rows") && "(full table)" in all, all)
        } finally {
            db.close()
        }
    }
}
