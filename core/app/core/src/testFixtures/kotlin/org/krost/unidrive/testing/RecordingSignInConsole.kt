package org.krost.unidrive.testing

import org.krost.unidrive.SignInConsole

/**
 * A [SignInConsole] that records everything a sign-in shows, opens or asks, in order (`show:`, `warn:`, `open:`,
 * `line:`, `secret:`), and answers the reads from [lines] and [secrets] (null once they run out). [onOpenBrowser] stands
 * in for the user in the browser (a test calls the loopback redirect from it).
 */
class RecordingSignInConsole(
    override val isInteractive: Boolean = true,
    lines: List<String?> = emptyList(),
    secrets: List<String?> = emptyList(),
    private val onOpenBrowser: (String) -> Unit = {},
) : SignInConsole {
    val events: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    private val lines = ArrayDeque(lines)
    private val secrets = ArrayDeque(secrets)

    override fun show(line: String) {
        events += "show:$line"
    }

    override fun warn(line: String) {
        events += "warn:$line"
    }

    override fun openBrowser(url: String) {
        events += "open:$url"
        onOpenBrowser(url)
    }

    override fun readLine(prompt: String): String? {
        events += "line:$prompt"
        return lines.removeFirstOrNull()
    }

    override fun readSecret(prompt: String): CharArray? {
        events += "secret:$prompt"
        return secrets.removeFirstOrNull()?.toCharArray()
    }
}
