package org.krost.unidrive.internxt.model

import kotlinx.serialization.Serializable

@Serializable
data class Farmer(
    val userAgent: String = "",
    val protocol: String = "https",
    val address: String,
    val port: Int,
    val nodeID: String = "",
    val lastSeen: String = "",
)

@Serializable
data class Mirror(
    val index: Int = 0,
    val hash: String = "",
    val size: Long = 0,
    val parity: Boolean = false,
    val token: String = "",
    val healthy: Boolean? = null,
    val farmer: Farmer,
    val url: String,
    val operation: String = "",
)

@Serializable
data class BridgeShard(
    val index: Int = 0,
    val hash: String = "",
    val url: String = "", // presigned download URL
)

@Serializable
data class BridgeFileInfo(
    val bucket: String,
    val mimetype: String = "",
    val filename: String = "",
    val frame: String = "",
    val size: Long = 0,
    val id: String,
    val index: String, // 64-char hex — the encryption index
    val shards: List<BridgeShard> = emptyList(),
)

@Serializable
data class UploadDescriptor(
    val index: Int = 0,
    val uuid: String,
    val url: String? = null,
    // archive#578: a multipart session answers ONE descriptor whose `urls` array holds one
    // presigned PUT URL per part, plus the S3-style multipart upload id the finish
    // call echoes back. `url` stays the single-part form.
    val urls: List<String> = emptyList(),
    @kotlinx.serialization.SerialName("UploadId") val uploadId: String? = null,
)

@Serializable
data class StartUploadResponse(
    val uploads: List<UploadDescriptor>,
)

@Serializable
data class ShardMeta(
    val hash: String,
    val uuid: String,
)

// archive#578: the multipart finish echoes the session's upload id and the completed part
// list (S3 conventions: 1-based part numbers, the server's ETag per part). The wire
// keys are capitalised the way the bridge expects ("UploadId", "PartNumber", "ETag").
@Serializable
data class MultipartPart(
    @kotlinx.serialization.SerialName("PartNumber") val partNumber: Int,
    @kotlinx.serialization.SerialName("ETag") val etag: String,
)

@Serializable
data class MultipartShardMeta(
    val uuid: String,
    val hash: String,
    @kotlinx.serialization.SerialName("UploadId") val uploadId: String,
    val parts: List<MultipartPart>,
)

@Serializable
data class FinishMultipartUploadRequest(
    val index: String, // 64-char hex
    val shards: List<MultipartShardMeta>,
)

@Serializable
data class FinishUploadRequest(
    val index: String, // 64-char hex
    val shards: List<ShardMeta>,
)

@Serializable
data class BucketEntry(
    val id: String, // fileId for drive registration
    val index: String = "",
    val bucket: String = "",
    val name: String = "",
)
