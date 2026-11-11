package org.krost.unidrive.s3

import org.krost.unidrive.auth.maskSecret
import org.krost.unidrive.io.defaultTokenPath
import java.net.URI
import java.nio.file.Path

/**
 * One S3 profile: a bucket on an endpoint, addressed path-style (`<endpoint>/<bucket>/<key>`), which every vendor
 * serves. What differs between vendors is read off the endpoint's host ([vendor]): how many parts a multipart upload
 * may have, and whether a write can be made conditional on the ETag the engine saw.
 */
data class S3Config(
    val bucket: String,
    val region: String,
    val endpoint: String,
    val accessKey: String,
    val secretKey: String,
    val tokenPath: Path = defaultTokenPath("s3"),
    val multipartThreshold: Long = MULTIPART_THRESHOLD,
    val minPartSize: Long = MIN_PART_SIZE,
    val vendor: Vendor = Vendor.of(endpoint),
    /**
     * Profile key `s3_create_from_listing`, default off (gkrost/unidrive-cli#573). Only a vendor that refuses `If-None-Match`
     * is affected: by default each of its creates is guarded by a HEAD (the key must be free) before the PUT. With this on, that
     * HEAD is not sent and the sync's own listing is what says the key was free, which widens the window in which an object
     * written by someone else after that listing is replaced from "the HEAD just before the PUT" to "the listing at plan time".
     * A caller that has no listing (a mount's direct upload) is trusted all the same, so turn it on only where nothing else
     * writes to the bucket.
     */
    val createFromListing: Boolean = false,
) {
    val host: String get() = URI(endpoint).let { if (it.port == -1) it.host else "${it.host}:${it.port}" }

    override fun toString(): String =
        "S3Config(bucket=$bucket, region=$region, endpoint=$endpoint, accessKey=${maskSecret(accessKey)}, " +
            "secretKey=${maskSecret(secretKey)}, tokenPath=$tokenPath, multipartThreshold=$multipartThreshold, " +
            "minPartSize=$minPartSize, vendor=$vendor, createFromListing=$createFromListing)"

    /** What sets one S3 implementation apart from another for this provider. */
    data class Vendor(
        val name: String,
        /** Most parts one multipart upload may have. */
        val maxParts: Int,
        /** Whether PUT and CompleteMultipartUpload honour `If-Match` (412 when the ETag moved on). */
        val conditionalWrites: Boolean,
    ) {
        companion object {
            // AWS: 10,000 parts, and If-Match on PutObject and CompleteMultipartUpload. Scaleway documents 1,000 parts.
            // Every other vendor: AWS's part limit, and a HEAD comparison instead of a conditional write.
            fun of(endpoint: String): Vendor {
                val host = runCatching { URI(endpoint).host?.lowercase() }.getOrNull() ?: ""
                return when {
                    host == "amazonaws.com" || host.endsWith(".amazonaws.com") -> Vendor("aws", 10_000, conditionalWrites = true)
                    host.endsWith(".scw.cloud") -> Vendor("scaleway", 1_000, conditionalWrites = false)
                    else -> Vendor("s3-compatible", 10_000, conditionalWrites = false)
                }
            }
        }
    }

    companion object {
        const val MULTIPART_THRESHOLD: Long = 100L * 1024 * 1024
        const val MIN_PART_SIZE: Long = 16L * 1024 * 1024

        /** S3's own bounds: a part (and a single copy) holds at most 5 GiB, every part but the last at least 5 MiB. */
        const val MAX_PART_SIZE: Long = 5L * 1024 * 1024 * 1024
        const val MAX_SINGLE_COPY: Long = 5L * 1024 * 1024 * 1024

        /** The endpoint when the profile names none: AWS in [region]. */
        fun awsEndpoint(region: String): String = if (region == "us-east-1") "https://s3.amazonaws.com" else "https://s3.$region.amazonaws.com"
    }
}
