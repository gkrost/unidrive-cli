package org.krost.unidrive.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.krost.unidrive.http.Priority

/**
 * The coroutine scopes of a daemon, beside [DaemonRuntime]'s companion (which they would grow past its
 * sizeRatchet baseline). Same package, so every caller resolves as before.
 */

/**
 * The serve scope: the daemon's launches (enumerations, event writers, pollers) run on [Dispatchers.Default],
 * not on the event loop of the `runBlocking` that calls [DaemonRuntime.start]. That loop is one thread, which the shutdown's
 * `closeSignal.await()` also needs: a blocking span on it would stall every writer and the stop. The IPC handlers
 * already run on their own pool, so no state here relied on that single thread. Blocking JDBC and file work still
 * goes through `withContext(Dispatchers.IO)` at its call site, so it does not hold a Default thread.
 */
internal fun serveScopeOf(
    parent: kotlin.coroutines.CoroutineContext,
    job: Job,
): CoroutineScope = CoroutineScope(parent + job + Dispatchers.Default)

/**
 * The scope the mount's hydration uploads and replays run in: a scope of its own (the shutdown joins it apart from
 * the serve scope), so it carries the daemon's [logContext] itself (archive#150, gkrost/unidrive-cli#225). A mount upload
 * is a user's write, so it runs on the user lane and may take the reserved Drive slot (gkrost/unidrive-cli#74).
 */
internal fun hydrationUploadScope(profileName: String): CoroutineScope =
    CoroutineScope(
        SupervisorJob() +
            Dispatchers.IO +
            logContext(profileName) +
            Priority.Foreground,
    )
