package org.krost.unidrive

/**
 * gkrost/unidrive-cli#267: everything an interactive sign-in shows, opens or asks, owned by the client. A provider never
 * prints, reads a console or launches a browser itself: [CloudProvider.signIn] does it all through the console its caller
 * hands in. The CLI hands in [TerminalSignInConsole]; a test hands in a fake.
 */
interface SignInConsole {
    /** False when nobody can answer [readLine] or [readSecret] (no interactive terminal): a flow that needs them refuses. */
    val isInteractive: Boolean

    /** One line of the sign-in's text (the URL to open, the device code, what it waits for). */
    fun show(line: String)

    /** One line about a failed step the flow retries (a wrong two-factor code). */
    fun warn(line: String)

    /** Opens [url] in the user's browser, best effort: the flow shows the URL as well. */
    fun openBrowser(url: String)

    /** One line typed after [prompt], or null when there is no input. */
    fun readLine(prompt: String): String?

    /** A secret typed after [prompt] without echo, or null when there is no input. The caller zeroes it. */
    fun readSecret(prompt: String): CharArray?
}

/** The [SignInConsole] of a human on a terminal: stdout, stderr, the platform's browser opener and [System.console]. */
object TerminalSignInConsole : SignInConsole {
    // archive#865: a real interactive terminal on stdin, not merely a Console handle. JDK 22+ hands out a Console even
    // when the streams are redirected (its isTerminal() then answers false).
    override val isInteractive: Boolean get() = System.console()?.isTerminal == true

    override fun show(line: String) = println(line)

    override fun warn(line: String) = System.err.println(line)

    override fun openBrowser(url: String) = org.krost.unidrive.io.openBrowser(url)

    override fun readLine(prompt: String): String? = System.console()?.readLine(prompt)

    override fun readSecret(prompt: String): CharArray? = System.console()?.readPassword(prompt)
}
