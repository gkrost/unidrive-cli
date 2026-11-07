package org.krost.unidrive.internxt.model

import kotlinx.serialization.Serializable

/**
 * One row of `GET /storage/trash/paginated`'s `{result: [...]}` (drive-server-wip `TrashController.getTrashedFilesPaginated`,
 * #500): the file or folder row as the server stores it, plus the `parent` summary and the `expiresAt` the controller adds.
 * The server answers files and folders in separate pages (`type=files|folders` is mandatory), so [InternxtTrashRow] serves
 * both and the row's own `type` field is not the discriminator. Unknown keys are ignored ([InternxtJson]); a folder has no
 * `size` on the wire, and `deletedAt` may be absent, so both are nullable.
 */
@Serializable
internal data class InternxtTrashRow(
    val uuid: String,
    val plainName: String? = null,
    val name: String? = null,
    val size: Long? = null,
    val deletedAt: String? = null,
    val updatedAt: String? = null,
    val parent: InternxtTrashParent? = null,
    val expiresAt: String? = null,
)

/** The trashed item's parent folder as the controller sums it up; `null` at the top of the tree. */
@Serializable
internal data class InternxtTrashParent(
    val uuid: String? = null,
    val plainName: String? = null,
    val status: String? = null,
)

/** The page envelope of `GET /storage/trash/paginated`. */
@Serializable
internal data class InternxtTrashPage(
    val result: List<InternxtTrashRow> = emptyList(),
)
