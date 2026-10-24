package org.krost.unidrive

/**
 * The outcome of a proof, made WITHOUT downloading the cloud copy, that a local file does or does not hold the bytes of a
 * cloud item ([CloudProvider.contentMatchesLocal], gkrost/unidrive#1219).
 *
 * [same] is true only when a real comparison found the two equal (a content hash or the provider's own checksum of
 * the stored object matched), false only when one found them different (a hash that can be compared did not match, the
 * sizes differ). "No proof possible" is never a [ContentProof]: the provider answers null for it, and the caller falls
 * back to something else (a download and a byte compare).
 *
 * [method] names how the answer was reached, for the client and for tests: it carries no hash and no key material.
 */
data class ContentProof(
    val same: Boolean,
    val method: String,
)
