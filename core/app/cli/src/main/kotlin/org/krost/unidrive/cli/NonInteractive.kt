package org.krost.unidrive.cli

import picocli.CommandLine

/**
 * #845 (item #749-6): a `--json` command never reads the console and always answers with one JSON object.
 * [Main.isNonInteractive] is true for such a run; a step that would need input then throws a [LifecycleError]
 * (printed as the lifecycle JSON refusal) instead of prompting or exiting the JVM.
 */
internal val Main.isNonInteractive: Boolean
    get() =
        nonInteractive
            ?: runCatching { spec.commandLine()?.parseResult?.jsonRequested() == true }.getOrDefault(false)

/** [Main.passphraseSourcesFactory]'s sources, with the console removed when the run is non-interactive. */
internal fun Main.effectivePassphraseSources(): PassphraseSources {
    val s = passphraseSourcesFactory()
    if (!isNonInteractive) return s
    return PassphraseSources(env = s.env, consolePresent = { false }, consoleRead = { null }, helper = s.helper, vaultSecret = s.vaultSecret)
}

/**
 * A refusal: in a non-interactive (`--json`) run the typed [LifecycleError] the command prints as JSON;
 * otherwise [stderrLines] on stderr and [System.exit] with [exitCode], as before.
 */
internal fun Main.refuse(
    token: String,
    message: String,
    stderrLines: List<String> = listOf(message),
    exitCode: Int = 1,
): Nothing {
    if (isNonInteractive) throw LifecycleError(token, message, exitCode = exitCode)
    stderrLines.forEach { System.err.println(it) }
    System.exit(exitCode)
    error("unreachable")
}

/** True when the parsed command line, or any subcommand on it, matched a `--json` option. */
internal fun CommandLine.ParseResult.jsonRequested(): Boolean =
    generateSequence(this) { it.subcommand() }.any { it.hasMatchedOption("--json") }
