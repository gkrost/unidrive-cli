package org.krost.unidrive.onedrive

import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPathPart
import org.krost.unidrive.NameRefusedException
import org.krost.unidrive.onedrive.model.DriveItem
import org.krost.unidrive.onedrive.model.DriveItemCollectionResponse

/**
 * A rename onto a name the drive judges as a sibling's (cli#950): refused as similar_name naming the sibling, as a create is.
 * An exact-name sibling is a true duplicate and keeps the 409.
 *
 * The siblings are those of the folder the item lands in, in the drive that holds the item: the caller's own drive for an
 * ordinary item, and for a shared-with-me item ([shared], renamed in place) the other user's drive, below the item's own
 * parent [sharedParentId]. A shared item whose parent is unknown has no listing to ask: the 409 stays as it came.
 */
internal suspend fun <T> GraphApiService.refusingNames(
    toPath: String,
    shared: SharedItemRef? = null,
    sharedParentId: String? = null,
    block: suspend () -> T,
): T =
    try {
        block()
    } catch (e: GraphApiException) {
        e.throwIfPermanentNameRefusal(toPath)
        val list: (suspend () -> List<DriveItem>)? =
            when {
                shared == null -> null
                sharedParentId != null -> ({ listChildrenOfItem(shared.driveId, sharedParentId) })
                else -> ({ emptyList() })
            }
        if (e.isSimilarName()) throw similarNameRefusal(toPath, e, list)
        if (e.isNameAlreadyExists() && !(shared != null && sharedParentId == null)) refuseFoldedName(toPath, e, list)
        throw e
    }

// The refusal of a "similar name" 409: the drive said so itself, so the listing only names the sibling (a listing that fails names none).
private suspend fun GraphApiService.similarNameRefusal(
    path: String,
    refused: GraphApiException,
    list: (suspend () -> List<DriveItem>)?,
): NameRefusedException {
    val name = path.substringAfterLast('/')
    val names =
        try {
            (list ?: { listChildren(path.substringBeforeLast('/', "").ifEmpty { "/" }) })().mapNotNull { it.name }
        } catch (_: GraphApiException) {
            emptyList()
        } catch (_: java.io.IOException) {
            emptyList()
        }
    val mine = OneDriveServerNames.skeleton(name)
    val sibling =
        OneDriveNameFolds.foldedSibling(name, names)
            ?: names.filter { !OneDriveServerNames.sameName(it, name) && OneDriveServerNames.skeleton(it) == mine }.minOrNull()
    return NameRefusedException(
        "OneDrive treats the name of '$path' as identical to ${sibling?.let { "'$it'" } ?: "another item"} in the same folder " +
            "and refuses it (409 nameAlreadyExists): ${refused.message}",
        reason = NameRefusedException.SIMILAR_NAME,
        sibling = sibling,
        cause = refused,
        requestId = refused.requestId,
    )
}

/** The children of item [itemId] in drive [driveId] (a shared-with-me folder lives in another user's drive). */
internal suspend fun GraphApiService.listChildrenOfItem(
    driveId: String,
    itemId: String,
): List<DriveItem> {
    val items = mutableListOf<DriveItem>()
    var next: String? = "$baseUrl/drives/${driveId.encodeURLPathPart()}/items/${itemId.encodeURLPathPart()}/children?\$top=999"
    var pages = 0
    while (next != null && pages++ < MAX_SIBLING_PAGES) {
        val page = json.decodeFromString<DriveItemCollectionResponse>(authenticatedRequest(next).bodyAsText())
        items += page.value
        next = page.nextLink
    }
    return items
}

private const val MAX_SIBLING_PAGES = 1000
