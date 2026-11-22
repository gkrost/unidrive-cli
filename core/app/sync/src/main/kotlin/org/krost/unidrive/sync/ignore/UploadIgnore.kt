package org.krost.unidrive.sync.ignore

import org.krost.unidrive.engine.UploadIgnoreCheck
import org.krost.unidrive.io.Os
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The ignore rules of one sync root as the upload path asks them (docs/dev/specs/ignore-layering-design.md, "The
 * upload path"): an [IgnoreTree] asked with the engine's logical paths (`/a/b`).
 *
 * - **No restart for a rule change.** The tree is revalidated at most every [revalidateAfterMs]: the next question
 *   after that probes the rule files again, and an edited, added or removed file takes effect.
 * - **Never silent.** A rule file that holds its directory back (unreadable, too large) is logged as a WARN, at most
 *   once per file every [warnEveryMs], and [heldBack] lists the directories for `daemon.status`.
 *
 * Thread-safe, like the tree.
 */
class UploadIgnore(
    source: IgnoreFileSource,
    val settings: IgnoreSettings,
    private val revalidateAfterMs: Long = DEFAULT_REVALIDATE_AFTER_MS,
    private val warnEveryMs: Long = DEFAULT_WARN_EVERY_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : UploadIgnoreCheck {
    private val log = LoggerFactory.getLogger(UploadIgnore::class.java)
    private val tree = IgnoreTree(source, settings, ::report)
    private val lastRevalidate = AtomicLong(Long.MIN_VALUE)
    private val warned = ConcurrentHashMap<String, Long>()

    /** True when the upload of [path] (a logical path, `/a/b`) is held back by an ignore rule. */
    override fun isIgnored(
        path: String,
        isDir: Boolean,
    ): Boolean = explain(path, isDir).ignored

    /** The verdict for [path] (a logical path); the paths inside it are relative to the sync root, as the tree's. */
    fun explain(
        path: String,
        isDir: Boolean = false,
    ): IgnoreExplanation {
        maybeRevalidate()
        return tree.explain(path.trim('/'), isDir)
    }

    /** True when [path]'s name is a rule file this account reads (`.unidrive.ignore`, `.gitignore` with the opt-in). */
    override fun isRuleFile(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name == IgnoreTree.UNIDRIVE_FILE || (settings.honorGitignore && name == IgnoreTree.GIT_FILE)
    }

    /** The rule file [path] was written: its folder's rule files are read again at their next use. */
    override fun ruleFileWritten(path: String) = tree.invalidate(path.trim('/').substringBeforeLast('/', ""))

    /**
     * Probe every rule file now and stamp that time, so the next question inside the interval does not probe again.
     * The pause ending and a mirror pass both ask this: the folder held back during the pause is read on the next
     * question, and a pass starts from the files as they are now.
     */
    override fun revalidate() {
        tree.revalidate()
        lastRevalidate.set(clock())
    }

    /** The directories held back now (re-probed when a rule file may have changed since), as logical paths. */
    fun heldBack(): List<HeldBackDir> {
        maybeRevalidate()
        return tree.heldBack().map { HeldBackDir("/" + it.dir, "/" + it.file, it.reason) }
    }

    /** When the rule files were last revalidated (epoch ms), null before the first question. */
    val checkedAtMs: Long? get() = lastRevalidate.get().takeIf { it != Long.MIN_VALUE }

    private fun maybeRevalidate() {
        val now = clock()
        val last = lastRevalidate.get()
        if (last != Long.MIN_VALUE && now - last < revalidateAfterMs) return
        if (lastRevalidate.compareAndSet(last, now)) tree.revalidate()
    }

    private fun report(problem: IgnoreProblem) {
        val now = clock()
        val previous = warned[problem.file]
        if (previous != null && now - previous < warnEveryMs) return
        if (warned.size >= WARNED_CAP) warned.clear()
        warned[problem.file] = now
        if (problem.heldBack) {
            val dir = problem.file.substringBeforeLast('/', "")
            log.warn(
                "ignore rule file /{} cannot be read ({}): nothing below /{} is uploaded until it can be",
                problem.file,
                problem.reason,
                dir,
            )
        } else {
            log.info("ignore rule file /{} is not read ({})", problem.file, problem.reason)
        }
    }

    companion object {
        const val DEFAULT_REVALIDATE_AFTER_MS = 5_000L
        const val DEFAULT_WARN_EVERY_MS = 15 * 60_000L
        private const val WARNED_CAP = 1_000

        /**
         * The settings of a profile: its `honor_gitignore` and `ignore_rules` keys, with case folding where the local
         * file system folds case (Windows, macOS) and NFC names (the engine's names are NFC).
         */
        fun settingsFor(
            honorGitignore: Boolean,
            accountRules: List<String>,
        ): IgnoreSettings =
            IgnoreSettings(
                honorGitignore = honorGitignore,
                accountRules = accountRules,
                options = IgnoreOptions(ignoreCase = foldsCase(), normalizeNfc = true),
            )

        private fun foldsCase(): Boolean =
            File.separatorChar == '\\' || Os.isMac()
    }
}

/**
 * The rule files of a mount, read from its hydration cache ([cacheDir]): a rule file's bytes are there once it was
 * written through the mount or opened. A rule file the cloud holds but the cache does not ([inCloud], asked with the
 * logical path) is downloaded on purpose by [fetched] (capped, outside the cache), so its folder's rules apply before
 * the file is ever opened. Without [fetched], or while that download is too large or fails, its directory is held
 * back (fail closed).
 *
 * gkrost/unidrive-cli#775: a rule file only this computer has (never uploaded, often because it ignores itself, as a venv's
 * own `.gitignore` does) is known to the engine through its cache copy alone. When that copy is gone while its row still
 * says the bytes are here ([lost], asked with the logical path: a wiped or emptied hydration cache), the rules are not
 * absent but unknown. So such a file is also kept outside the cache ([kept], taken while its cache copy is there), and its
 * rules are read from that copy then. With no copy kept (larger than the cap, or wiped before it was ever probed), its
 * directory is held back (fail closed, with the held-back WARN) instead of uploading what they kept local. Writing the
 * file through the mount again ends the hold, and deleting it through the mount drops its row (and its kept copy).
 */
class CacheIgnoreSource(
    cacheDir: Path,
    private val fetched: CloudRuleFiles? = null,
    private val lost: (path: String) -> Boolean = { false },
    private val kept: LocalRuleFiles? = null,
    private val inCloud: (path: String) -> Boolean,
) : IgnoreFileSource {
    private val files = FileSystemIgnoreSource(cacheDir)
    private val fromCloud = ConcurrentHashMap.newKeySet<String>()
    private val fromKept = ConcurrentHashMap.newKeySet<String>()

    override fun probe(path: String): RuleFileProbe =
        when (val probe = files.probe(path)) {
            RuleFileProbe.Absent ->
                when {
                    inCloud("/$path") -> cloudProbe(path).also { kept?.forget("/$path") }
                    lost("/$path") -> keptProbe(path)
                    else -> probe.also { kept?.forget("/$path") }
                }
            else ->
                probe.also {
                    fromCloud.remove(path)
                    fromKept.remove(path)
                    keepOrForget(path, probe)
                }
        }

    override fun read(
        path: String,
        maxBytes: Long,
    ): ByteArray? =
        when {
            path in fromCloud && fetched != null -> fetched.read("/$path", maxBytes)
            path in fromKept && kept != null -> kept.read("/$path", maxBytes)
            else -> files.read(path, maxBytes)
        }

    // A local-only rule file (never uploaded, bytes here) gets a kept copy of its cache copy; any other loses one it had.
    private fun keepOrForget(
        path: String,
        probe: RuleFileProbe,
    ) {
        val store = kept ?: return
        if (probe !is RuleFileProbe.Present) return
        if (lost("/$path")) store.keep("/$path", probe) { max -> files.read(path, max) } else store.forget("/$path")
    }

    private fun keptProbe(path: String): RuleFileProbe {
        val probe = kept?.probe("/$path") ?: return RuleFileProbe.Unreadable(LOST)
        fromKept.add(path)
        return probe
    }

    private fun cloudProbe(path: String): RuleFileProbe {
        val probe = fetched?.probe("/$path") ?: return RuleFileProbe.Unreadable(NOT_DOWNLOADED)
        if (probe is RuleFileProbe.Present) fromCloud.add(path) else fromCloud.remove(path)
        return probe
    }

    companion object {
        const val NOT_DOWNLOADED = "in the cloud but not downloaded to this computer; open it once"

        /** gkrost/unidrive-cli#775: the reason of a local-only rule file whose only copy (in the hydration cache) is gone. */
        const val LOST =
            "never uploaded, and its only copy in the hydration cache is gone; write it through the mount again " +
                "(or delete it there) to end the hold"
    }
}

/**
 * The rule files of a mirror, read from its sync root. A rule file the cloud holds whose bytes are not here
 * ([notDownloaded], asked with the logical path: its download is still to come, or failed) cannot be read, so its
 * directory is held back (fail closed) until the sync has downloaded it.
 */
class SyncRootIgnoreSource(
    syncRoot: Path,
    private val notDownloaded: (path: String) -> Boolean,
    // True while this pass's listing is not visible yet. An absent local rule file is then undecided, not absent:
    // caching "no rule file" would let a file the listing is about to report through for the rest of the pass.
    private val listingPending: () -> Boolean = { false },
) : IgnoreFileSource {
    private val files = FileSystemIgnoreSource(syncRoot)

    override fun probe(path: String): RuleFileProbe {
        if (notDownloaded("/$path")) return RuleFileProbe.Unreadable(NOT_DOWNLOADED)
        val found = files.probe(path)
        if (found == RuleFileProbe.Absent && listingPending()) return RuleFileProbe.Undecided
        return found
    }

    override fun read(
        path: String,
        maxBytes: Long,
    ): ByteArray? = files.read(path, maxBytes)

    companion object {
        const val NOT_DOWNLOADED = "in the cloud but not downloaded to this computer yet; the sync downloads it"
    }
}
