package org.krost.unidrive.engine

import org.krost.unidrive.http.ResumableStaging
import org.krost.unidrive.http.ResumeIdentity
import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** #845: the resume partials a download left behind are swept when nothing will continue them, and only then. */
class StalePartialSweepTest {
    private lateinit var root: Path
    private val destination get() = root.resolve("sub").resolve("f.bin.mirror.unidrive-tmp")
    private val partial get() = root.resolve("sub").resolve("f.bin.mirror.unidrive-tmp" + ResumableStaging.PARTIAL_SUFFIX)
    private val sidecar get() = root.resolve("sub").resolve("f.bin.mirror.unidrive-tmp" + ResumableStaging.SIDECAR_SUFFIX)
    private val identity = ResumeIdentity("item-1", "etag-A", 10_000)

    @BeforeTest
    fun setUp() {
        root = TestScratch.directory("stale-partials")
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun keepPartial(ageMs: Long = 0L) {
        val staging = assertNotNull(ResumableStaging.open(destination, identity))
        staging.write(ByteArray(3_000) { 1 }, 0, 3_000)
        staging.release()
        if (ageMs > 0L) Files.setLastModifiedTime(sidecar, FileTime.fromMillis(System.currentTimeMillis() - ageMs))
    }

    @Test
    fun `a stale partial with its sidecar is swept`() {
        keepPartial(ageMs = ResumableStaging.MAX_AGE_MS + 60_000)

        StalePartialSweep.sweep(root) { true }

        assertTrue(Files.notExists(partial), "the stale partial goes")
        assertTrue(Files.notExists(sidecar), "and its record")
    }

    @Test
    fun `a fresh partial is kept`() {
        keepPartial()

        StalePartialSweep.sweep(root) { true }

        assertTrue(Files.exists(partial))
        assertTrue(Files.exists(sidecar))
        assertEquals(3_000L, ResumableStaging.keptOffset(destination, identity), "and it is still continued")
    }

    @Test
    fun `a partial whose remote id is no longer a row is swept`() {
        keepPartial()
        val asked = mutableListOf<String>()

        StalePartialSweep.sweep(root) { id -> asked += id; false }

        assertEquals(listOf("item-1"), asked)
        assertTrue(Files.notExists(partial))
        assertTrue(Files.notExists(sidecar))
    }

    @Test
    fun `a file without a sidecar is never touched`() {
        Files.createDirectories(partial.parent)
        Files.write(partial, ByteArray(100))
        Files.setLastModifiedTime(partial, FileTime.fromMillis(System.currentTimeMillis() - 10 * ResumableStaging.MAX_AGE_MS))
        // A sidecar that cannot be read is not a valid one either.
        val unreadable = partial.resolveSibling("g.bin" + ResumableStaging.PARTIAL_SUFFIX)
        Files.write(unreadable, ByteArray(100))
        Files.writeString(unreadable.resolveSibling("g.bin" + ResumableStaging.SIDECAR_SUFFIX), "not a record")

        StalePartialSweep.sweep(root) { false }

        assertTrue(Files.exists(partial), "no sidecar: left alone, however old")
        assertTrue(Files.exists(unreadable), "an unreadable sidecar: left alone")
        assertTrue(Files.exists(unreadable.resolveSibling("g.bin" + ResumableStaging.SIDECAR_SUFFIX)))
    }
}
