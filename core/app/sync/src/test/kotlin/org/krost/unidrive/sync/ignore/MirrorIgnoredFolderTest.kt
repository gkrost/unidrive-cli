package org.krost.unidrive.sync.ignore

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.FakeCloudProvider
import org.krost.unidrive.sync.ProgressReporter
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#68: on a mirror, a folder made in the sync root that the ignore rules exclude stays here: the scan
 * does not report it, so it is never created in the cloud, and nothing in it is uploaded.
 */
class MirrorIgnoredFolderTest {
    private val syncRoot = Files.createTempDirectory("unidrive-68-root")
    private lateinit var db: StateDatabase
    private val provider = FakeCloudProvider()

    @BeforeTest
    fun setUp() {
        db = StateDatabase(Files.createTempDirectory("unidrive-68-db").resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `an ignored folder made in the sync root is never created in the cloud`() =
        runTest {
            Files.writeString(syncRoot.resolve(".unidrive.ignore"), "build/\n")
            Files.createDirectories(syncRoot.resolve("build"))
            Files.writeString(syncRoot.resolve("build/out.bin"), "build output")
            Files.createDirectories(syncRoot.resolve("src"))

            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = syncRoot,
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                reporter = ProgressReporter.Silent,
                mirrorIgnore = IgnoreSettings(),
            ).syncOnce()

            assertTrue("/build" !in provider.createdFolders, "created: ${provider.createdFolders}")
            assertTrue("/src" in provider.createdFolders, "its neighbour is created: ${provider.createdFolders}")
            assertEquals(listOf("/.unidrive.ignore"), provider.uploadedPaths, "only the rule file goes up")
            assertTrue(Files.isDirectory(syncRoot.resolve("build")), "the folder stays here")
        }
}
