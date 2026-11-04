package org.krost.unidrive.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#269: the cursor and recovery keys of `sync_state` are read and written through [CursorState]
 * only. A `getSyncState("…")`/`setSyncState("…")` call with a literal key anywhere in the main sources is how a typo
 * or a second, different default for an absent key slips in. This test walks every module's `src/main` and fails on
 * such a literal outside [CursorState], unless the (file, key) pair is on [ALLOWED] below. StateDatabase
 * keeps one checkpoint read of `last_full_scan`; any other safety key there fails.
 *
 * The test task runs in the module directory (see SyncCommandNoHydrationWiringTest in :app:cli), so `../..` is `core`.
 */
class SyncStateLiteralKeyGuardTest {
    private val core = File("../..").canonicalFile

    private val owners = setOf("CursorState.kt")

    private val call = Regex("""\b(?:get|set)SyncState\(\s*"([^"]*)"""")

    private fun mainSources(): List<File> =
        listOf("app", "providers")
            .flatMap { File(core, it).listFiles().orEmpty().toList() }
            .map { File(it, "src/main") }
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    /** (file name, key) of every literal-key call outside the owners. */
    private fun literalCalls(): List<Pair<String, String>> =
        mainSources()
            .filter { it.name !in owners }
            .flatMap { file -> call.findAll(file.readText()).map { file.name to it.groupValues[1] }.toList() }

    @Test
    fun `the main sources found are the whole engine`() {
        val names = mainSources().map { it.name }.toSet()
        // A wrong working directory would make the walk empty and the guard pass on nothing.
        assertTrue("RemoteGather.kt" in names && "SyncEngine.kt" in names && "DoctorCommand.kt" in names, "walked $core")
    }

    @Test
    fun `no literal sync_state key outside CursorState and the allow-list`() {
        val offending = literalCalls().filterNot { it in ALLOWED || it in SAFETY_CALLS_ALLOWED }.distinct()
        assertEquals(
            emptyList(),
            offending,
            "use CursorState for the cursor and recovery keys, or a named constant; see gkrost/unidrive-cli#269",
        )
    }

    @Test
    fun `the allow-list holds no safety key and no stale entry`() {
        assertEquals(emptyList(), ALLOWED.filter { it.second in SAFETY_KEYS }, "a safety key goes through CursorState")
        val present = literalCalls().toSet()
        assertEquals(emptyList(), ALLOWED.filterNot { it in present }, "drop allow-list entries the code no longer has")
        assertEquals(
            emptyList(),
            SAFETY_CALLS_ALLOWED.filterNot { it in present },
            "drop the checkpoint exception if StateDatabase no longer reads it",
        )
    }

    @Test
    fun `the safety keys appear as text only where they are owned`() {
        // Beyond the call sites: a private constant of the same text (`DELTA_CURSOR_KEY = "delta_cursor"`) is a second owner.
        val quoted = SAFETY_KEYS.map { "\"$it\"" }
        val found =
            mainSources()
                .filter { it.name !in owners }
                .flatMap { file ->
                    val allowed = SAFETY_TEXT_ALLOWED[file.name].orEmpty()
                    quoted.filter { key -> key in file.readText() && key !in allowed }.map { file.name to it }
                }
        assertEquals(emptyList(), found, "the safety keys are owned by CursorState")
    }

    private companion object {
        val SAFETY_KEYS =
            setOf(
                "delta_cursor",
                "pending_cursor",
                "pending_cursor_complete",
                "pending_cursor_skipped_paths",
                "last_gather_full",
                "last_full_scan",
            )

        /**
         * Quoted safety-key text that is not a [CursorState] call. RemoteGather names the skipped-paths key that
         * CursorState reads. StateDatabase reads and deletes `last_full_scan` on the checkpoint path. tracking.db
         * (SqliteTrackingSet) keeps a `delta_cursor` of its own in its own table. Any other quoted safety key fails.
         */
        val SAFETY_TEXT_ALLOWED: Map<String, Set<String>> =
            mapOf(
                "RemoteGather.kt" to setOf("\"pending_cursor_skipped_paths\""),
                "StateDatabase.kt" to setOf("\"last_full_scan\""),
                "TrackingSet.kt" to setOf("\"delta_cursor\""),
            )

        /** The one safety-key call that stays outside [CursorState]: the checkpoint read, not a second default. */
        val SAFETY_CALLS_ALLOWED: Set<Pair<String, String>> = setOf("StateDatabase.kt" to "last_full_scan")

        /**
         * TODO(gkrost/unidrive-cli#269): keys with no deletion safety that still use literal keys: the quota snapshot, the
         * scan timings, the sync root and the effective scope. A typed holder for them is later work; until then the list
         * may only shrink (the stale-entry test above).
         */
        val ALLOWED: Set<Pair<String, String>> =
            setOf(
                "DoctorCommand.kt" to "effective_scope",
                "DoctorCommand.kt" to "quota_fetched_at",
                // In a comment (check 8, state-vs-config drift), not a call.
                "DoctorCommand.kt" to "sync_root",
                "QuotaCommand.kt" to "quota_used",
                "QuotaCommand.kt" to "quota_total",
                "QuotaCommand.kt" to "quota_remaining",
                "QuotaCommand.kt" to "quota_fetched_at",
                "QuotaSnapshotMonitor.kt" to "quota_used",
                "QuotaSnapshotMonitor.kt" to "quota_total",
                "QuotaSnapshotMonitor.kt" to "quota_remaining",
                "QuotaSnapshotMonitor.kt" to "quota_fetched_at",
                "StatusCommand.kt" to "effective_scope",
                "SyncCommand.kt" to "sync_root",
                "SyncEngine.kt" to "sync_root",
                "SyncEngine.kt" to "effective_scope",
                "SyncEngine.kt" to "last_scan_secs_remote",
                "SyncEngine.kt" to "last_scan_count_remote",
                "SyncEngine.kt" to "last_scan_secs_local",
                "SyncEngine.kt" to "last_scan_count_local",
            )
    }
}
