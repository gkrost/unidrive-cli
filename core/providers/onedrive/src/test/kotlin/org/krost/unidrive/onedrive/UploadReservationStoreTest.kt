package org.krost.unidrive.onedrive

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.krost.unidrive.testing.TestScratch

class UploadReservationStoreTest {
    private lateinit var dir: Path
    private var now = Instant.parse("2026-11-22T12:00:00Z")

    @BeforeTest
    fun setUp() {
        dir = TestScratch.directory("ud-reservations")
    }

    @AfterTest
    fun tearDown() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun store() = UploadReservationStore(dir) { now }

    @Test
    fun `a reservation is held by its path and id only`() {
        store().record("/a.bin", "id-1")

        assertTrue(store().holds("/a.bin", "id-1"))
        assertFalse(store().holds("/a.bin", "id-2"), "another item at the name is not the reservation")
        assertFalse(store().holds("/b.bin", "id-1"), "the id under another path is not the reservation")
    }

    @Test
    fun `a reservation outlives the upload session it was recorded with`() {
        // The stub can stay after its session expired on the server (hours, not days); the reservation must still tell it.
        store().record("/a.bin", "id-1")
        now = now.plus(Duration.ofDays(3))

        assertTrue(store().holds("/a.bin", "id-1"))
    }

    @Test
    fun `a reservation goes after its maximum age`() {
        store().record("/a.bin", "id-1")
        now = now.plus(UploadReservationStore.MAX_AGE).plusSeconds(1)

        assertFalse(store().holds("/a.bin", "id-1"))
    }

    @Test
    fun `a released reservation is not held`() {
        store().record("/a.bin", "id-1")
        store().record("/b.bin", "id-2")
        store().release("/a.bin")

        assertFalse(store().holds("/a.bin", "id-1"))
        assertTrue(store().holds("/b.bin", "id-2"), "only the released path goes")
    }

    @Test
    fun `reservations live apart from the upload session store`() {
        store().record("/a.bin", "id-1")

        assertTrue(Files.exists(dir.resolve(UploadReservationStore.FILE_NAME)))
        assertFalse(Files.exists(dir.resolve(org.krost.unidrive.io.ResumeSessionStore.FILE_NAME)))
    }
}
