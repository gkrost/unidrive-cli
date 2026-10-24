package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CaseSensitivity
import org.krost.unidrive.CloudItem
import org.krost.unidrive.sync.model.ChangeState
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncAction
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two devices create the same name in different case on a provider that folds case (OneDrive): device A holds an
 * unsynced `case4.txt`, device B uploaded `CASE4.txt`, then A syncs. The two are one name at the provider (and on a
 * case-insensitive disk one file), so a download of `CASE4.txt` lands on A's bytes and an upload of `case4.txt`
 * collides with B's file. The engine must refuse both: one conflict, nothing transferred, and no later pass deletes
 * either side. The provider declares the policy, so the test runs the same on a case-sensitive host.
 */
class CaseInsensitiveTwinDevicesTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider

    private val deviceA = "bytes written on device A, unsynced"
    private val deviceB = "bytes uploaded by device B"

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-case-twins-root")
        db = StateDatabase(Files.createTempDirectory("unidrive-case-twins-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
        provider.caseSensitivity = CaseSensitivity.INSENSITIVE_PRESERVING
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private var streaming = false

    private fun engine(reporter: ProgressReporter) =
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = reporter,
            streamingReconciliation = streaming,
        )

    private fun remote(path: String) =
        CloudItem(
            id = "id-b",
            name = path.substringAfterLast('/'),
            path = path,
            size = deviceB.length.toLong(),
            isFolder = false,
            modified = Instant.parse("2026-03-28T12:00:00Z"),
            created = Instant.parse("2026-03-28T10:00:00Z"),
            hash = "hash-b",
            mimeType = null,
        )

    private fun names(): List<String> = Files.list(syncRoot).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    private fun transfers(): List<String> =
        provider.uploadedPaths.map { "up $it" } +
            provider.downloadByPathCalls.map { "down $it" } +
            provider.downloadByIdCalls.map { "down ${it.second}" }

    @Test
    fun `local NEW and remote NEW that differ only in case give one conflict and no transfer`() = runTest { twoDevices() }

    @Test
    fun `the same holds for a streamed pass`() =
        runTest {
            streaming = true
            twoDevices()
        }

    private suspend fun twoDevices() {
        Files.writeString(syncRoot.resolve("case4.txt"), deviceA)
        provider.files["/CASE4.txt"] = deviceB.toByteArray()
        provider.deltaItems = listOf(remote("/CASE4.txt"))

        val first = Recording()
        engine(first).syncOnce()

        assertEquals(emptyList(), transfers(), "nothing is transferred; plan was ${first.actions}")
        assertEquals(1, first.conflicts, "one conflict; plan was ${first.actions}")
        assertEquals(listOf("case4.txt"), names(), "device A's file keeps its name")
        assertEquals(deviceA, Files.readString(syncRoot.resolve("case4.txt")), "device A's bytes stay")

        // The next passes delete nothing on either side and still transfer nothing, also when device B edits its file.
        val edited = deviceB + ", edited"
        for (cursor in listOf("cursor-2", "cursor-3", "cursor-4")) {
            provider.deltaCursor = cursor
            provider.deltaItems =
                if (cursor == "cursor-3") {
                    provider.files["/CASE4.txt"] = edited.toByteArray()
                    listOf(remote("/CASE4.txt").copy(size = edited.length.toLong(), hash = "hash-b2", modified = Instant.parse("2026-03-29T12:00:00Z")))
                } else {
                    emptyList()
                }
            val next = Recording()
            engine(next).syncOnce()
            assertTrue(provider.deletedPaths.isEmpty(), "nothing is deleted in the cloud: ${provider.deletedPaths}")
            assertEquals(emptyList(), transfers(), "nothing is transferred later; plan was ${next.actions}")
            assertEquals(deviceA, Files.readString(syncRoot.resolve("case4.txt")), "device A's bytes stay")
            assertTrue(String(provider.files.getValue("/CASE4.txt")).startsWith(deviceB), "device B's file stays in the cloud")
        }
    }

    // The planner alone, so the case-sensitive half runs on any host: on a folding disk two such files cannot both exist.
    private fun plan(
        foldCase: Boolean,
        remote: Map<String, CloudItem>,
        local: Map<String, ChangeState>,
    ) = Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH).also { it.foldCase = foldCase }.reconcile(remote, local)

    @Test
    fun `the planner refuses a remote NEW and a local NEW case twin where case folds`() {
        val actions = plan(true, mapOf("/CASE4.txt" to remote("/CASE4.txt")), mapOf("/case4.txt" to ChangeState.NEW))

        assertEquals(listOf("/case4.txt"), actions.map { it.path }, "one action: $actions")
        val conflict = assertIs<SyncAction.Conflict>(actions.single())
        assertNull(conflict.remoteItem, "a refusal: the apply transfers nothing")
    }

    @Test
    fun `on a case-sensitive provider and file system the two names stay two files`() {
        val twins = plan(false, mapOf("/CASE4.txt" to remote("/CASE4.txt")), mapOf("/case4.txt" to ChangeState.NEW))
        assertEquals(
            setOf("Upload /case4.txt", "DownloadContent /CASE4.txt"),
            twins.map { "${it::class.simpleName} ${it.path}" }.toSet(),
        )

        val local = plan(false, emptyMap(), mapOf("/a.txt" to ChangeState.NEW, "/A.txt" to ChangeState.NEW))
        assertEquals(setOf("/a.txt", "/A.txt"), local.filterIsInstance<SyncAction.Upload>().map { it.path }.toSet(), "$local")
        assertTrue(local.none { it is SyncAction.Conflict }, "$local")
    }

    // The remote gather keys by the same policy: case twins in one listing are one name where the provider folds case,
    // even on a case-sensitive local file system, and two names only on a case-sensitive provider and file system.
    private suspend fun gatherTwins(policy: CaseSensitivity): List<String> {
        provider.caseSensitivity = policy
        provider.deltaItems = listOf(remote("/Readme").copy(id = "id-1"), remote("/README").copy(id = "id-2"))
        SyncEngine(
            provider = provider,
            db = db,
            syncRoot = syncRoot,
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            reporter = ProgressReporter.Silent,
            localFoldsCase = false,
        ).enumerateRemoteIntoState(reset = true)
        return listOf("/Readme", "/README").filter { db.getEntry(it) != null }
    }

    @Test
    fun `the gather folds remote case twins by the provider's policy, not by the host`() =
        runTest {
            assertEquals(1, gatherTwins(CaseSensitivity.INSENSITIVE_PRESERVING).size, "one name at a folding provider")
        }

    @Test
    fun `the gather keeps remote case twins apart on a case-sensitive provider and file system`() =
        runTest {
            assertEquals(listOf("/Readme", "/README"), gatherTwins(CaseSensitivity.SENSITIVE))
        }

    @Test
    fun `where case folds two local NEW twins are refused`() {
        val local = plan(true, emptyMap(), mapOf("/a.txt" to ChangeState.NEW, "/A.txt" to ChangeState.NEW))
        assertEquals(setOf("/a.txt", "/A.txt"), local.filterIsInstance<SyncAction.Conflict>().map { it.path }.toSet(), "$local")
        assertTrue(local.none { it is SyncAction.Upload }, "$local")
    }

    private class Recording : ProgressReporter {
        val actions = mutableListOf<String>()
        var conflicts = 0

        override fun onScanProgress(
            phase: String,
            count: Int,
        ) {}

        override fun onActionCount(
            total: Int,
            preFilterTotal: Int,
            filterReason: String?,
        ) {}

        override fun onActionProgress(
            index: Int,
            total: Int,
            action: String,
            path: String,
        ) {
            actions.add("$action $path")
        }

        override fun onTransferProgress(
            path: String,
            bytesTransferred: Long,
            totalBytes: Long,
        ) {}

        override fun onSyncComplete(
            downloaded: Int,
            uploaded: Int,
            conflicts: Int,
            durationMs: Long,
            actionCounts: Map<String, Int>,
            failed: Int,
        ) {
            this.conflicts += conflicts
        }

        override fun onWarning(message: String) {}
    }
}
