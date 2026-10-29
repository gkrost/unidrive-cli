package org.krost.unidrive.cli

import picocli.CommandLine

/** The long names of the options of `daemon run` that take a value, read from the command itself (gkrost/unidrive-cli#201). */
internal object DaemonRunValuedOptions {
    fun fromCommand(): Set<String> =
        CommandLine(DaemonRunCommand()).commandSpec.options()
            .filter { !it.usageHelp() && !it.versionHelp() && it.arity().max() > 0 }
            .flatMap { it.names().toList() }
            .filter { it.startsWith("--") }
            .toSet()
}
