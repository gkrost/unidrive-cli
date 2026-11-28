package org.krost.unidrive.hydration

import org.krost.unidrive.InsufficientLocalSpaceException
import org.krost.unidrive.InsufficientRemoteSpaceException
import org.krost.unidrive.PermanentUploadFailureException
import org.krost.unidrive.ProviderException
import org.krost.unidrive.sync.StateDatabase

/**
 * An upload refused for space, carried through the upload queue as the refusal it is for now ([HydrationError.SpaceDeferred]):
 * deferred under its own token instead of retried at once. The provider's exception stays the cause.
 */
internal class UploadDeferredForSpaceException(
    val token: String,
    cause: ProviderException,
) : PermanentUploadFailureException(cause.message ?: token, cause, cause.requestId)

/** [e] as an [UploadDeferredForSpaceException] when it is a typed space refusal, else null. */
internal fun spaceDeferralOf(e: ProviderException): UploadDeferredForSpaceException? =
    when (e) {
        is InsufficientLocalSpaceException -> UploadDeferredForSpaceException(HydrationError.SpaceDeferred.LOCAL_SPACE_TOKEN, e)
        is InsufficientRemoteSpaceException -> UploadDeferredForSpaceException(HydrationError.SpaceDeferred.REMOTE_SPACE_TOKEN, e)
        else -> null
    }

/** Whether the upload refusal recorded on [path] (`<mtime>|<size>|<retry at>|<reason>`) is a space deferral. */
internal fun StateDatabase.holdsSpaceDeferral(path: String): Boolean =
    uploadRefusal(path)?.split('|', limit = 4)?.getOrNull(3)?.let { HydrationError.SpaceDeferred.parse(it) } != null

// #1073: the account-level hold of a full remote drive. The daemon arms it through the pause gate; the
// hydration layer only reads the gate's state — the hold is the daemon's, the deferrals stay the rows' own.

/** The hold of a full remote drive is in force: the gate is paused with [PauseGate.QUOTA_HOLD_REASON]. */
internal val HydrationImpl.quotaHoldActive: Boolean
    get() = pause.current?.reason == PauseGate.QUOTA_HOLD_REASON

/** The refusal recorded on [path] is the account's storage having no room for these bytes (`remote_space`). */
internal fun HydrationImpl.holdsRemoteSpaceDeferral(path: String): Boolean =
    runCatching { stateDb.uploadRefusal(path) }.getOrNull()?.split('|', limit = 4)?.getOrNull(3) ==
        HydrationError.SpaceDeferred.REMOTE_SPACE_TOKEN
