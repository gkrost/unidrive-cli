package org.krost.unidrive.s3

import org.krost.unidrive.PermanentUploadFailureException

/**
 * A key with a `.` or `..` path segment cannot be addressed (gkrost/unidrive-cli#845, item #748-4): the client normalises
 * the URL path before it sends, so the signature would cover a different path than the one requested, and the request
 * would fail or reach another object. Such a key is left out of listings and never signed.
 */
internal object S3Keys {
    /** True when a whole `/`-separated component of [key] is `.` or `..`. `a.b`, `..x` and `x..` are ordinary names. */
    fun hasDotSegment(key: String): Boolean = key.split('/').any { it == "." || it == ".." }

    fun requireAddressable(key: String) {
        if (hasDotSegment(key)) throw S3DotSegmentKeyException(key)
    }

    fun requireAddressable(keys: Collection<String>) = keys.forEach(::requireAddressable)
}

/** The key has a `.` or `..` segment, which an S3 request cannot carry intact. The same key fails the same way again: no retry. */
class S3DotSegmentKeyException(
    val key: String,
) : PermanentUploadFailureException("S3 key '$key' has a '.' or '..' path segment; a request for it cannot be signed correctly, so it is refused")
