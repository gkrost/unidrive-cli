package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ScanContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#414: the row a listing reaches keeps the stored object of the version it names, so a download of
 * the row after a daemon restart seeds the hint from it instead of paying the metadata call. A listing interrupted after
 * a hand-over staged its files with the payload (which carries the bucket, the encryptVersion and the updatedAt); the
 * gather that resumes it holding its files (no read-back of the staging, as a preview runs) used to rebuild every file
 * from its row alone, whose columns carry no bucket, and those rows paid the metadata call until their next delivery.
 * The fake drive sends `2026-07-01T10:00:00.000Z` for every row: a time whose zero fraction a rebuild from the parsed
 * Instant would drop.
 */
class InternxtResumedHintTest {
    private val bucket = "6928426c1a2316b856c9ab81"

    private fun filesWithObjects() = fakeAccountFiles().map { it.copy(fileId = "fid-${it.uuid}", bucket = bucket) }

    private fun storedObjects(page: DeltaPage) = page.items.filter { !it.isFolder }.associate { it.id to it.storedObject }

    @Test
    fun `a resumed listing that holds its files gives every row the stored object the hand-over staged`() =
        runTest {
            val fresh = internxtProviderOn(FakeDrive(fakeAccountFolders(), filesWithObjects()).engine).delta(null, null, FakeStaging().context())
            assertTrue(storedObjects(fresh).isNotEmpty() && storedObjects(fresh).values.all { it != null }, "the fresh listing names the objects: ${storedObjects(fresh)}")

            // The first attempt hands its files over from the staging, and dies once both streams are stored: every
            // row it leaves carries the payload, naming the enumeration it was staged by.
            val drive = FakeDrive(fakeAccountFolders(), filesWithObjects())
            val staging = FakeStaging()
            staging.dieWhen = { raw -> decodeCursorMarker(raw)!!.let { it.folders.done && it.files.done } }
            assertFailsWith<StagingKilled> { internxtProviderOn(drive.engine).delta(null, null, staging.context().withReadBack(staging.rows)) }
            staging.dieWhen = null
            assertTrue(
                staging.rows.values.filter { !it.isFolder }.all { stagedFilePayloadOf(it) != null },
                "the hand-over staged the files with the payload",
            )

            // The resume holds its files (no read-back of the staging): every file is rebuilt from its staged row.
            val resumed = internxtProviderOn(drive.engine).delta(null, null, staging.context())

            assertEquals(storedObjects(fresh), storedObjects(resumed), "every resumed row names the object its page named")
        }

    @Test
    fun `a row whose payload names another enumeration is rebuilt from the row alone, and names no stored object`() =
        runTest {
            val fresh = internxtProviderOn(FakeDrive(fakeAccountFolders(), filesWithObjects()).engine).delta(null, null, FakeStaging().context())

            val drive = FakeDrive(fakeAccountFolders(), filesWithObjects())
            val staging = FakeStaging()
            staging.dieWhen = { raw -> decodeCursorMarker(raw)!!.let { it.folders.done && it.files.done } }
            assertFailsWith<StagingKilled> { internxtProviderOn(drive.engine).delta(null, null, staging.context().withReadBack(staging.rows)) }
            staging.dieWhen = null

            // One row's payload names an enumeration of its own (an abandoned one the staging kept): not this resume's
            // to take, the way [GatherStream.fileOf] leaves it.
            val foreign = staging.rows.values.first { !it.isFolder }
            val payload = assertNotNull(stagedFilePayloadOf(foreign), "the row carries a payload")
            staging.rows[foreign.id] = foreign.copy(version = stagedFilePayload("an enumeration of its own", payload.file))

            val resumed = internxtProviderOn(drive.engine).delta(null, null, staging.context())

            assertNull(storedObjects(resumed)[foreign.id], "another enumeration's payload is not taken; the row alone is rebuilt")
            assertEquals(storedObjects(fresh) - foreign.id, storedObjects(resumed) - foreign.id, "the other rows carry theirs")
        }

    // The context with the engine's read-back of the staging: the rows in id order, as the engine keeps them.
    private fun ScanContext.withReadBack(rows: ConcurrentHashMap<String, CloudItem>): ScanContext =
        copy(
            stagedPage = { afterId, limit ->
                rows.values
                    .sortedBy { it.id }
                    .filter { afterId == null || it.id > afterId }
                    .take(limit)
                    .map { it.copy(path = "/${it.name}", created = null, mimeType = null) }
            },
        )
}
