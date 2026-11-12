package org.krost.unidrive.hydration

import kotlinx.coroutines.CancellationException
import org.krost.unidrive.CloudItem
import org.krost.unidrive.NameRefusedException
import org.krost.unidrive.sync.PathNormalizer
import org.slf4j.Logger

/**
 * gkrost/unidrive-cli#22: the mount creates a `sync_path` scope root that only the local side has, as the sync does since
 * PR #299. Which roots qualify is the gather's rule (`RemoteGather.creatableScopeRoots`): a folder in the local root, spelled
 * exactly so, that state.db knows from no remote item. A root that synced before and is gone from the cloud is not one
 * of them and is never created here.
 *
 * It runs after an enumeration that succeeded ([MountEngine.enumerateRemoteIntoState]), so a root the cloud has is
 * already a row and not asked for. Each root is created once, topmost missing folder first, and every level is looked up
 * in its parent's listing before anything is created:
 *  - a folder of exactly that name is taken into its row (another device, or this one, made it in the meantime);
 *  - a folder whose name differs only in case, or a file of that name, stops it: nothing is created ([Outcome.NameTaken]);
 *  - a folder the drive refuses by its name is recorded as a blocked folder, which the status reports ([Outcome.Refused]).
 *
 * Those three are settled for this daemon run: the root is not asked for again until the next start. A failure that is
 * none of them (an outage, a listing that fails) is left for the next enumeration ([Outcome.Failed]), which the poller
 * spaces out.
 */
internal class ScopeRootCreator(
    private val ops: Ops,
    private val log: Logger,
) {
    /** What the creator needs from the mount; [MountEngine] implements it over the provider and state.db. */
    interface Ops {
        /** The scope roots the gather would let the provider find missing. */
        fun creatableRoots(): Set<String>

        /** True when state.db has a row at [path] that the cloud backs (a remote id). */
        fun hasRemoteRow(path: String): Boolean

        /** True when the drive refused [path] by name before (a blocked row): it is retried at the next start, not here. */
        fun isNameBlocked(path: String): Boolean

        /** The live children of the remote folder of [path]. */
        suspend fun listChildren(path: String): List<CloudItem>

        /** Creates the folder [path] and writes its row. */
        suspend fun createFolder(path: String): CloudItem

        /** Takes the folder the cloud holds at [path] into its row; see [MountEngine.existingFolderAt]. */
        suspend fun adoptExisting(path: String): ExistingFolder

        /** Records [path] as a folder the drive refuses by name. */
        fun recordRefused(
            path: String,
            reason: String,
            sibling: String?,
        )

        /** Tells the clients that the folder [parent] has a new child. */
        fun folderChanged(parent: String)
    }

    /** What became of a scope root. */
    sealed interface Outcome {
        /** The folders created for it, topmost first (empty when every level was there already). */
        data class Created(val folders: List<String>) : Outcome

        /** The name is taken in its parent by a folder spelled otherwise, or by a file: nothing was created. */
        data class NameTaken(val at: String, val remoteName: String) : Outcome

        /** The drive refused the folder [at] by its name. */
        data class Refused(val at: String, val reason: String) : Outcome

        /** Not settled: tried again after the next enumeration. */
        data class Failed(val message: String) : Outcome
    }

    private val outcomes = java.util.concurrent.ConcurrentHashMap<String, Outcome>()

    /** The outcome of every scope root this run looked at. */
    fun outcomes(): Map<String, Outcome> = outcomes.toMap()

    /** Creates the scope roots that are still missing; returns the outcome of each root it looked at in this call. */
    suspend fun createMissing(): Map<String, Outcome> {
        val asked = LinkedHashMap<String, Outcome>()
        val roots =
            ops.creatableRoots()
                .filter { root -> outcomes[root].let { it == null || it is Outcome.Failed } && !ops.isNameBlocked(root) }
                .sortedBy { it.count { c -> c == '/' } }
        for (root in roots) {
            val outcome = create(root)
            outcomes[root] = outcome
            asked[root] = outcome
        }
        return asked
    }

    @Suppress("TooGenericExceptionCaught") // any failure but the settled ones is left for the next enumeration
    private suspend fun create(root: String): Outcome {
        val created = ArrayList<String>()
        return try {
            for (level in levelsOf(root)) {
                if (ops.hasRemoteRow(level)) continue
                when (val step = createLevel(level, parentWasCreated = created.lastOrNull() == parentOf(level))) {
                    null -> created += level
                    is LevelResult.Present -> Unit
                    is LevelResult.Stop -> return step.outcome
                }
            }
            if (created.isNotEmpty()) {
                log.info("#22: the scope root {} was missing in the cloud and was created (folders: {})", root, created.joinToString())
            }
            Outcome.Created(created)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            log.warn("#22: the scope root {} could not be created; tried again after the next enumeration: {}", root, message)
            Outcome.Failed(message)
        }
    }

    private sealed interface LevelResult {
        data object Present : LevelResult

        data class Stop(val outcome: Outcome) : LevelResult
    }

    // Null when the folder [level] was created.
    @Suppress("TooGenericExceptionCaught") // a failed create is probed for a racing creator, then rethrown
    private suspend fun createLevel(
        level: String,
        parentWasCreated: Boolean,
    ): LevelResult? {
        val name = PathNormalizer.nfc(level.substringAfterLast('/'))
        // A parent created a moment ago has no children to look at.
        val sameName =
            if (parentWasCreated) {
                null
            } else {
                ops.listChildren(parentOf(level)).firstOrNull { !it.deleted && PathNormalizer.nfc(it.name).equals(name, ignoreCase = true) }
            }
        if (sameName != null) {
            if (!sameName.isFolder || PathNormalizer.nfc(sameName.name) != name) return nameTaken(level, sameName.name)
            return adopt(level)
        }
        return try {
            ops.createFolder(level)
            ops.folderChanged(parentOf(level))
            null
        } catch (e: NameRefusedException) {
            ops.recordRefused(level, e.reason, e.sibling)
            log.warn("#22: the drive refuses the scope root folder {} by its name ({}); nothing is created", level, e.reason)
            LevelResult.Stop(Outcome.Refused(level, e.reason))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Another creator may have been first (a 409): the folder that is there now is taken. Anything else is the failure.
            if (ops.adoptExisting(level) is ExistingFolder.Adopted) LevelResult.Present else throw e
        }
    }

    private suspend fun adopt(level: String): LevelResult =
        when (ops.adoptExisting(level)) {
            is ExistingFolder.Adopted -> LevelResult.Present
            ExistingFolder.HeldByFile -> nameTaken(level, level.substringAfterLast('/'))
            ExistingFolder.Unknown -> error("the folder $level is listed but cannot be read")
        }

    private fun nameTaken(
        level: String,
        remoteName: String,
    ): LevelResult {
        log.warn("#22: the scope root folder {} is not created: its parent in the cloud holds '{}' under that name", level, remoteName)
        return LevelResult.Stop(Outcome.NameTaken(level, remoteName))
    }

    private fun parentOf(path: String): String = path.substringBeforeLast('/').ifEmpty { "/" }

    // "/a/b/c" -> ["/a", "/a/b", "/a/b/c"]
    private fun levelsOf(root: String): List<String> {
        val segments = root.split('/').filter { it.isNotEmpty() }
        return segments.indices.map { "/" + segments.take(it + 1).joinToString("/") }
    }
}
