package org.krost.unidrive.cli

import org.krost.unidrive.AuthRequiredException
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.NotSignedInException
import org.krost.unidrive.SignInConsole
import org.krost.unidrive.TerminalSignInConsole
import org.krost.unidrive.authenticateAndLog

/**
 * gkrost/unidrive-cli#267: a CLI command's authentication. The provider authenticates with what is stored; when nothing
 * usable is, the CLI signs in through [console] (the terminal: the browser, the device code, Internxt's prompts), as
 * `authenticate()` itself did before it became non-interactive. The daemon and `status` never come here.
 */
internal suspend fun CloudProvider.authenticateOrSignIn(console: SignInConsole = TerminalSignInConsole) {
    try {
        authenticateAndLog()
    } catch (_: NotSignedInException) {
        signIn(console)
    } catch (_: AuthRequiredException) {
        signIn(console)
    }
}
