package org.krost.unidrive.cli

/** A daemon's refusal: its error [token] and, on a not_empty rmdir (gkrost/unidrive-cli#717), the [reason] and one [child]. */
internal class DaemonRefusal(
    val token: String,
    val reason: String? = null,
    val child: String? = null,
)

/**
 * The refusal of a recursive delete the daemon answered not_empty, by its reason (gkrost/unidrive-cli#717): bytes only
 * this computer has are not the same as a cloud listing the engine has not caught up with, and only the first wants the
 * user to act. An engine that sends no reason predates #717 and meant unsynced bytes; a reason this CLI does not know
 * is passed on verbatim as the token.
 */
internal fun notEmptyRefusal(
    refusal: DaemonRefusal,
    path: String,
): LifecycleError {
    val named = refusal.child?.let { " ($it)" } ?: ""
    val nothing = "nothing was deleted"
    return when (val reason = refusal.reason) {
        null, "unsynced_bytes" ->
            LifecycleError(
                "unsynced_below",
                "a file below '$path'$named holds changes the cloud has not received yet; $nothing. " +
                    "Let the upload finish, or delete that file on its own first.",
            )
        "remote_unobserved" ->
            LifecycleError(reason, "the cloud lists items in '$path'$named the engine has not seen yet; $nothing; try again shortly")
        "remote_changed" ->
            LifecycleError(reason, "an item in '$path'$named changed in the cloud since the engine last saw it; $nothing; try again shortly")
        "remote_listing_repeats" ->
            LifecycleError(reason, "the cloud listing of '$path'$named repeats an item; $nothing; try again shortly")
        "provider_not_empty" ->
            LifecycleError(reason, "the cloud still lists items in '$path' (it may still be removing them); $nothing; try again shortly")
        else -> LifecycleError(reason, "the daemon refused to delete '$path'$named: not_empty, reason $reason; $nothing")
    }
}
