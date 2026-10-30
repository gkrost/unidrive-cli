package org.krost.unidrive.sync

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * #218: the one check of `exclude_patterns`. The engine and every command that matches them take the set this
 * accepts ([SyncConfig.effectiveExcludePatterns] goes through it), so `sync`, `status`, `verify`, `doctor`, `get`/`ls`
 * and the pending-upload gate always agree on what is excluded.
 *
 * The syntax is the matcher's own ([Reconciler.matchesGlob]; `docs/config-schema/config.example.toml` documents it),
 * and so is the check: a pattern is dropped only when that matcher's compile step cannot build it. `[`, `]`, `{` and
 * `}` stand for themselves there, so a pattern with one of them is kept and matched as written (`*.{tmp,bak}` excludes
 * only names ending in `.{tmp,bak}`), with one warning, since it reads like a glob class or alternation the matcher
 * does not have. The check used to be the JDK's glob, another dialect: it let `*.{tmp,bak}` through silently and
 * dropped `My [Draft*` from sync while the commands still honoured it.
 */
object ExcludePatterns {
    private val log = LoggerFactory.getLogger(ExcludePatterns::class.java)

    // Glob syntax elsewhere, plain characters here.
    private const val LITERAL_BRACKETS = "[]{}"

    // One warning per pattern and process: sync and the daemon check the same patterns in more than one place.
    private val warned = ConcurrentHashMap.newKeySet<String>()

    /** The [patterns] the matcher can compile, in their order; warns once for each one it drops or matches literally. */
    fun accepted(patterns: List<String>): List<String> = accepted(patterns, Reconciler::compileGlobOrThrow)

    /** [accepted] with the compile step passed in, so a test can reach the drop that no real pattern triggers. */
    internal fun accepted(
        patterns: List<String>,
        compile: (String) -> Unit,
    ): List<String> =
        patterns.filter { pattern ->
            val failure =
                try {
                    compile(pattern)
                    null
                } catch (e: IllegalArgumentException) {
                    e
                }
            if (failure != null) {
                if (warned.add(pattern)) log.warn("Invalid exclude pattern '{}', skipping: {}", pattern, failure.message)
                false
            } else {
                val warning = literalWarning(pattern)
                if (warning != null && warned.add(pattern)) log.warn(warning)
                true
            }
        }

    /** The warning for a pattern whose `[`, `]`, `{` or `}` are matched as written; null for a pattern without them. */
    internal fun literalWarning(pattern: String): String? =
        if (pattern.none { it in LITERAL_BRACKETS }) {
            null
        } else {
            "Exclude pattern '$pattern' is kept, but its [ ] { } match themselves: " +
                "exclude_patterns have no [...] classes and no {a,b} alternation"
        }

    /** Forgets which patterns were warned about, so a test sees the warning of a pattern another test checked first. */
    internal fun forgetWarnings() {
        warned.clear()
    }
}
