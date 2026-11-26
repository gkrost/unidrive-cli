package org.krost.unidrive.onedrive

import org.krost.unidrive.PermanentUploadFailureException

// Reads of a Graph name refusal, shared by the provider's creates and renames (cli#950).
internal fun GraphApiException.isInvalidRequest(): Boolean =
    statusCode == 400 && message?.contains("\"code\":\"invalidRequest\"") == true

internal fun GraphApiException.isNameAlreadyExists(): Boolean =
    statusCode == 409 && message?.contains("nameAlreadyExists") == true

// A name the drive compares as equal to a sibling's although it differs: the file shapes of the refusal say
// so ("The specified item name is incompatible with a similar name on an existing item.").
internal fun GraphApiException.isSimilarName(): Boolean =
    isNameAlreadyExists() && message?.contains("similar name", ignoreCase = true) == true

internal fun GraphApiException.throwIfPermanentNameRefusal(path: String) {
    val issue = OneDriveNameRules.issue(path)
    if (isInvalidRequest() || statusCode == 403 && path.contains("_vti_", ignoreCase = true)) {
        val reason = issue ?: if (statusCode == 403) "name contains the reserved _vti_ token" else "Graph rejected this name or path as invalidRequest"
        throw PermanentUploadFailureException(
            "OneDrive rejected '$path': $reason${if (isInvalidRequest()) " (invalidRequest)" else " (403 accessDenied)"}; Graph said: $message",
            cause = this,
        )
    }
}
