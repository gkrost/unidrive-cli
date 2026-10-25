package org.krost.unidrive.engine

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.krost.unidrive.sync.SyncScope

/**
 * The checks every remote operation of one profile passes, whichever path
 * starts it: the mirror engine's sync pass and the mount's write verbs (archive#560 U2).
 *
 * - **Scope**: which remote paths state.db tracks — the standing scope plus any
 *   per-run `--sync-path`, and the folders leading to them. Empty = the whole drive.
 * - **Excludes**: the keep-local rule; an excluded path is never uploaded. Two sources: the profile's
 *   `exclude_patterns` (the old glob, unchanged) and, where the host passes them, the ignore rules ([ignore]).
 * - **Transfer budget**: one per-provider transfer cap shared by the sync pass and
 *   the hydration upload path (UD-263).
 * - **Trash namespace**: the virtual folder that shows what the provider keeps in its trash, which is read-only
 *   ([isReadOnlyPath], see [TrashNamespace]).
 * - **Views**: the virtual folders that list items living elsewhere in the drive (Recents, ...), read-only too
 *   ([isReadOnlyPath], see [ViewNamespaces]).
 *
 * Built once per engine; the instance holds the transfer semaphore, so the mirror
 * engine and the mount operations of one process must share the same instance.
 */
class RemoteOperationGuard(
    standingScope: List<String>,
    syncPaths: List<String>,
    // The effective exclude patterns (configured excludes union the defaults),
    // already validated by the caller.
    private val excludePatterns: List<String>,
    // The provider's transfer concurrency cap (ProviderMetadata.maxConcurrentTransfers).
    val maxConcurrentTransfers: Int,
    // The glob matcher of the exclude patterns. Injected: the matcher (with its
    // compiled-pattern cache) is shared with the planner and stays in :app:sync.
    private val matchesGlob: (path: String, pattern: String) -> Boolean,
    // archive#660: the ignore rules (rule files, account rules, the overridable content defaults), or null where the
    // host has none. Upload-side only: nothing here hides a remote item or holds a download back.
    private val ignore: UploadIgnoreCheck? = null,
    // The trash namespace of the profile: its name (profile key trash_folder_name) and, once something is in the trash,
    // where its root is. One per guard, so the sync pass, the mount verbs and the provider decorator all ask the same one.
    val trash: TrashNamespace = TrashNamespace(),
    // The views of the profile (see ViewNamespaces): none unless the host passes some, then the question
    // isReadOnlyPath asks of them costs nothing.
    val views: ViewNamespaces = ViewNamespaces.NONE,
) {
    /** The normalised tracked scope; empty means the whole drive. */
    val trackScope: List<String> =
        if (standingScope.isEmpty()) emptyList() else SyncScope.normalize(standingScope + syncPaths)
    private val trackAncestors: Set<String> = SyncScope.ancestors(trackScope)

    /** True when state.db tracks [remotePath]: inside the scope, or a folder leading to it. */
    fun isTracked(remotePath: String): Boolean = SyncScope.contains(remotePath, trackScope) || remotePath in trackAncestors

    /**
     * True when [path] lies outside the standing sync scope (config
     * `sync_path`); empty scope means the whole drive, so nothing is out of
     * scope. The hydration write verbs use this to refuse writes that would
     * create or move cloud data the mounted view can never show.
     */
    fun isOutOfScope(path: String): Boolean = !SyncScope.contains(path, trackScope)

    /**
     * True when [path] is in the trash namespace or in a view: a root folder or anything below it. The engine never sends
     * a write for such a path to the cloud and ignores local changes there; the mount verbs that would write refuse it
     * with `read_only`, and the sync pass drops the actions that would. Reading and copying out are not writes and stay
     * allowed (on a view path they resolve to the real item).
     */
    fun isReadOnlyPath(path: String): Boolean = trash.contains(path) || views.contains(path)

    /**
     * True when [path] matches the effective exclude patterns (configured
     * excludes union the defaults) or an ignore rule. Keep-local rule: such paths are never
     * uploaded. Shared by the upload path and the hydration write verbs, which
     * must report an excluded write instead of letting it present as in-sync.
     * [isDir] matters to the ignore rules only (a `build/` rule names folders).
     */
    fun isExcludedPath(
        path: String,
        isDir: Boolean = false,
    ): Boolean = excludePatterns.any { matchesGlob(path, it) } || ignore?.isIgnored(path, isDir) == true

    /**
     * True when no upload of [path] will ever be sent: the keep-local rules name it ([isExcludedPath]), it lies in the
     * read-only namespaces ([isReadOnlyPath]) or outside the standing scope ([isOutOfScope]). The one rule behind
     * what counts as a pending upload ([org.krost.unidrive.sync.PendingUploads]).
     */
    fun keepsLocal(path: String): Boolean = isExcludedPath(path) || isReadOnlyPath(path) || isOutOfScope(path)

    /** archive#660: the first of the profile's exclude patterns [path] matches, or null (the ignore rules not asked). */
    fun matchingExcludePattern(path: String): String? = excludePatterns.firstOrNull { matchesGlob(path, it) }

    /** archive#660: true when [path] is a rule file of the ignore rules: its cache copy is what the rules are read from. */
    fun isIgnoreRuleFile(path: String): Boolean = ignore?.isRuleFile(path) == true

    /** archive#660: a rule file was written at [path]; its folder's rules are read again before the next decision. */
    fun ignoreRuleFileWritten(path: String) {
        ignore?.ruleFileWritten(path)
    }

    /**
     * The per-provider transfer budget. The sync pass hands it to its transfer
     * executors; everything else goes through [withTransferPermit].
     */
    val transferBudget: Semaphore = Semaphore(maxConcurrentTransfers)

    /**
     * Run [block] holding one permit of the per-provider transfer budget. The
     * hydration upload path goes through this so mount writes and sync passes
     * share one cap instead of each running unbounded.
     */
    suspend fun <T> withTransferPermit(block: suspend () -> T): T =
        transferBudget.withPermit { withContext(holdsPermit.asContextElement(true)) { block() } }

    // Set on the thread while a coroutine inside [withTransferPermit] runs there: the ignore rules ask whether an
    // upload's path is ignored from inside its transfer, and a rule file downloaded then runs under that permit.
    private val holdsPermit = ThreadLocal<Boolean>()

    @Volatile
    private var pause: TransferPause? = null

    /** archive#778: the daemon's pause, attached by the mount front-end; the rule-file downloads ([ruleFileTransfers]) ask it. */
    fun attachPause(pause: TransferPause) {
        this.pause = pause
    }

    /** archive#778: the pause ended; a folder the ignore rules held back for it is probed again at the next question. */
    fun pauseEnded() {
        ignore?.revalidate()
    }

    /**
     * archive#660 / archive#778: the downloads of cloud-only rule files (`CloudRuleFiles`) as transfers. None starts while the daemon
     * is paused, one running when a pause begins is aborted, and each takes a permit of [transferBudget], unless the
     * thread that asks is inside a transfer that holds one already (an upload asking whether its path is ignored):
     * waiting for a second permit there could wait for itself.
     */
    val ruleFileTransfers: RuleFileTransfers =
        object : RuleFileTransfers {
            override val isPaused: Boolean get() = pause?.isPaused == true

            override fun runner(path: String): suspend (block: suspend () -> Unit) -> Unit {
                val nested = holdsPermit.get() == true
                return { block ->
                    val run: suspend () -> Unit = { if (nested) block() else withTransferPermit(block) }
                    pause?.track(path, run) ?: run()
                }
            }
        }
}

/** archive#778: the daemon's pause as engine-core sees it (the mount front-end's `PauseGate`). */
interface TransferPause {
    val isPaused: Boolean

    /** Runs [block] as a transfer in flight; throws when the daemon is paused, or when a pause that began aborts it. */
    suspend fun track(
        path: String,
        block: suspend () -> Unit,
    )
}

/** archive#660 / archive#778: see [RemoteOperationGuard.ruleFileTransfers]. */
interface RuleFileTransfers {
    /** True while the daemon is paused: no rule file is downloaded then. */
    val isPaused: Boolean

    /** How to run the download of the rule file [path], decided on the thread that asks. */
    fun runner(path: String): suspend (block: suspend () -> Unit) -> Unit

    companion object {
        /** Never paused, no permit: for a host without a transfer budget (tests, the one-shot commands). */
        val DIRECT: RuleFileTransfers =
            object : RuleFileTransfers {
                override val isPaused: Boolean get() = false

                override fun runner(path: String): suspend (block: suspend () -> Unit) -> Unit = { block -> block() }
            }
    }
}

/**
 * archive#660: the ignore rules as the upload path asks them. Implemented in :app:sync (`UploadIgnore`), over the
 * rule files of the host's local copy; engine-core only asks. Paths are logical (`/a/b`).
 */
interface UploadIgnoreCheck {
    fun isIgnored(
        path: String,
        isDir: Boolean,
    ): Boolean

    fun isRuleFile(path: String): Boolean

    fun ruleFileWritten(path: String)

    /** Every rule file is probed again at the next question. */
    fun revalidate() {}
}
