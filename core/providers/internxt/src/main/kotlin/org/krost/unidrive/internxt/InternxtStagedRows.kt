package org.krost.unidrive.internxt

import kotlinx.serialization.Serializable
import org.krost.unidrive.CloudItem
import org.krost.unidrive.internxt.model.InternxtFile
import org.krost.unidrive.internxt.model.InternxtJson

// The version token of a file ("<uuid>|<modificationTime>|<size>", see InternxtProvider.internxtVersionToken) is built from the
// modification time string the server sent. A staged row keeps no such string (scan_staging holds the time as an Instant, whose
// toString() drops a zero fraction: `...09Z` for `...09.000Z`), so a file resumed from its row got a token no fresh listing
// gives, and its first edit conflicted with no remote change (archive#1039). The hash of a staged row is the listing's own tag, so
// the file rows of every listing carry the raw string as the suffix of that tag. A tag with an empty suffix says the
// server sent none; a row with no such tag (staged by an earlier version) has only its Instant.

/** The prefix of the tag of a file row staged by the offset listing. */
internal const val OFFSET_FILE_PREFIX: String = "offset-listing|"

/** The tag of the staged row of [file]: the listing's [prefix] and the time string as the server sent it. */
internal fun stagedFileTag(
    prefix: String,
    file: InternxtFile,
): String = prefix + file.modificationTime.orEmpty()

/** The time string a staged file row carries in its tag [hash]: "" when the server sent none, null when the row carries no time. */
internal fun stagedModificationTime(hash: String?): String? {
    if (hash == null) return null
    val prefix = listOf(TREE_FILE_PREFIX, CURSOR_FILE_PREFIX, OFFSET_FILE_PREFIX).firstOrNull { hash.startsWith(it) } ?: return null
    return hash.removePrefix(prefix)
}

// A gather that cannot hold its files hands them over from the staging (ScanContext.stagedPage, archive#638). A staged row
// keeps the cloud identity only, so a file read back from its row alone would differ from the one the listing delivered: the
// creation time is not stored, the name of a legacy row is the ciphertext, and the root-level parent is collapsed to null.
// The file rows of such a gather therefore carry the file itself, trimmed to what the item is built from, in the row's
// version column (nothing reads a version of a staged row but this): the item built from the row is the item built from
// the page. The fileId rides along so the item keeps its contentId (the stored object's name, see internxtContentId) —
// a file read back without it built the item of the held gather with a null contentId. The payload names the gather it
// belongs to ([StagedFilePayload.gather], the instant the enumeration began,
// which a resume keeps), so the rows an earlier, abandoned listing left in the same scan are not taken for this one's.

@Serializable
internal class StagedFilePayload(
    val gather: String,
    val file: InternxtFile,
)

/** The payload of the staged row of [file] for the gather [gather]. */
internal fun stagedFilePayload(
    gather: String,
    file: InternxtFile,
): String =
    InternxtJson.encodeToString(
        StagedFilePayload.serializer(),
        StagedFilePayload(
            gather,
            InternxtFile(
                uuid = file.uuid,
                fileId = file.fileId,
                name = file.name,
                plainName = file.plainName,
                type = file.type,
                size = file.size,
                folderUuid = file.folderUuid,
                creationTime = file.creationTime,
                modificationTime = file.modificationTime,
            ),
        ),
    )

/** The payload [row] carries, or null for a row without one (staged by an earlier version or by a gather that holds its files). */
internal fun stagedFilePayloadOf(row: CloudItem): StagedFilePayload? {
    val raw = row.version ?: return null
    return try {
        InternxtJson.decodeFromString(StagedFilePayload.serializer(), raw)
    } catch (_: IllegalArgumentException) {
        null
    }
}

// The cursor a full gather names between the chunks of its hand-over (see InternxtProvider.GatherHandover). It is no poll cursor,
// which is a timestamp; the provider tells them apart by this prefix.
internal const val GATHER_CURSOR_PREFIX: String = "internxt-gather|"
