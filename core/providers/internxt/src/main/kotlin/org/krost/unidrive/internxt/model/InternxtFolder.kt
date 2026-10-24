package org.krost.unidrive.internxt.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.Instant

@Serializable
data class InternxtFolder(
    val id: Long = 0,
    val uuid: String,
    val name: String? = null,
    val plainName: String? = null,
    val type: String? = null,
    // Null is the top of a tree: the server's own rule (Folder.isRootFolder is `parentId === null`), sent as an
    // explicit null together with a null parentUuid. Nothing reads the number; parentUuid builds the path.
    val parentId: Long? = null,
    val parentUuid: String? = null,
    val bucket: String? = null,
    val encryptVersion: String? = null,
    val status: String = "EXISTS",
    val size: Long = 0,
    val creationTime: String? = null,
    val modificationTime: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val removed: Boolean = false,
    val deleted: Boolean = false,
) {
    // UD-372: see InternxtFile — eagerly parse the two timestamps that the hot delta() loop
    // consumes; createdAt / updatedAt stay as String (cursor lexicographic max only).
    @Transient
    val creationInstant: Instant? = tryParseInternxtInstant(creationTime)

    @Transient
    val modificationInstant: Instant? = tryParseInternxtInstant(modificationTime)
}

// One entry of `GET /backup/deviceAsFolder`: a folder row of the backups bucket and a few device fields, of which only
// the uuid is read.
@Serializable
data class BackupDeviceFolder(
    val uuid: String,
)

@Serializable
data class FolderListResponse(
    val list: List<InternxtFolder> = emptyList(),
)
