package org.krost.unidrive.io

import java.io.IOException

/**
 * UD-348: shared "open this URL in the user's default browser"
 * helper for OAuth authorization-code flows.
 *
 * Lifted here so an authorization-code provider inherits one browser opener.
 *
 * Uses the platform's opener directly (`rundll32` / `open` /
 * `xdg-open`) and does not touch `java.awt.Desktop` any more:
 * a jlink runtime image without `java.desktop` (~10 MB saved) would
 * throw `NoClassDefFoundError` on the old Desktop-first path — an
 * `Error`, which the previous `catch (Exception)` did not catch, so
 * the fallback never ran exactly where it was needed. `rundll32`
 * also avoids `cmd /c start`'s `&`-splits an OAuth URL's query
 * string into commands.
 *
 * Best effort: if no opener can be launched the failure is reported
 * on stderr and the flow continues — every caller prints the URL
 * itself so the user can paste it into a browser.
 */
public fun openBrowser(url: String) {
    val os = Os.of(System.getProperty("os.name"))
    val cmd = when {
        os.isWindows -> arrayOf("rundll32", "url.dll,FileProtocolHandler", url)
        os.isMac -> arrayOf("open", url)
        else -> arrayOf("xdg-open", url)
    }
    try {
        // ProcessBuilder, not Runtime.exec: the opener is a third-party program that must not inherit
        // UNIDRIVE_VAULT_PASS (issue#83 E).
        childProcessBuilder(cmd.toList()).start()
    } catch (e: IOException) {
        System.err.println("Could not open a browser (${e.message}); paste the printed URL manually.")
    }
}
