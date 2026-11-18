package org.krost.unidrive.hydration

import org.krost.unidrive.InsufficientLocalSpaceException
import org.krost.unidrive.InsufficientRemoteSpaceException
import org.krost.unidrive.PermanentUploadFailureException
import org.krost.unidrive.ProviderException

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
