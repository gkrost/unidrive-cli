package org.krost.unidrive.tracking

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * archive#1256 A: the engine's look at the remote item caught every exception and answered "the remote is not there". A
 * throttle or a dropped connection was then adopted as absence. Only a proven absence is absence; anything else stops the
 * call and leaves the tracking set as it was.
 */
class TrackingEngineRemoteProbeTest {
    private lateinit var workDir: Path
    private lateinit var syncRoot: Path
    private lateinit var tracking: SqliteTrackingSet
    private lateinit var provider: FakeTrackingProvider

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("ts-probe")
        syncRoot = workDir.resolve("sync-root").also { Files.createDirectories(it) }
        tracking = SqliteTrackingSet(workDir.resolve("tracking.db")).also { it.initialize() }
        provider = FakeTrackingProvider()
        Files.writeString(syncRoot.resolve("a.txt"), "local content")
    }

    @AfterTest
    fun tearDown() {
        tracking.close()
        workDir.toFile().deleteRecursively()
    }

    @Test
    fun `a transient failure of the remote lookup is not recorded as the remote being gone`() {
        provider.getMetadataError = IOException("connection reset")

        val failure = assertFailsWith<IOException> { TrackingEngine(provider, tracking, syncRoot).claim("/a.txt") }

        assertEquals("connection reset", failure.message)
        assertNull(tracking.lookup("/a.txt"), "nothing was adopted from a lookup that failed")
    }

    @Test
    fun `a proven absence is still recorded as the remote being gone`() {
        TrackingEngine(provider, tracking, syncRoot).claim("/a.txt")

        assertNotNull(tracking.lookup("/a.txt"))
    }
}
