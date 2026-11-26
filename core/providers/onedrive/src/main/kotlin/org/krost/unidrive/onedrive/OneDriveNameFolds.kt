package org.krost.unidrive.onedrive

import org.krost.unidrive.NameRefusedException
import java.text.Normalizer
import java.util.Locale

/**
 * Names OneDrive judges as one although a local file system keeps them apart (gkrost/unidrive-cli#950). A create of
 * such a name answers 409 `nameAlreadyExists` "Name already exists" (the answer of a true duplicate), and the
 * keep-both `rename` then lands the file as "<sibling> 1.ext": the folded name is refused instead, naming the sibling.
 *
 * The fold key, from the folds measured against a OneDrive Personal profile (the golden-unicode-v2 smoke of
 * 2026-11-24, the table in the issue) and Graph's documented case-insensitive names:
 *  - NFKC (`U+FB01` ligature fi = `fi`);
 *  - case, by full case mapping both ways (`Straße` = `STRASSE`, `ς` = `Σ`, `Report` = `report`);
 *  - zero-width and joiner code points U+200B..U+200D, U+2060, U+FEFF dropped;
 *  - emoji and other variation selectors U+FE00..U+FE0F and U+E0100..U+E01EF dropped (`U+2764` = `U+2764 U+FE0F`).
 *
 * The key never decides alone: it is consulted only after the drive itself answered 409 to a create, and only to find
 * the sibling to name. A fold the key does not know (the drive's full set is not documented, see
 * [OneDriveServerNames]) keeps the keep-both copy it had.
 */
internal object OneDriveNameFolds {
    fun key(name: String): String {
        val kept = StringBuilder(name.length)
        Normalizer.normalize(name, Normalizer.Form.NFKC).codePoints().forEach { cp ->
            if (!ignorable(cp)) kept.appendCodePoint(cp)
        }
        return kept.toString().uppercase(Locale.ROOT).lowercase(Locale.ROOT)
    }

    private fun ignorable(cp: Int): Boolean =
        cp in 0x200B..0x200D || cp == 0x2060 || cp == 0xFEFF || cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF

    /** Whether a name of [siblings] is exactly [name] (NFC, case-sensitive): a true duplicate, not a fold. */
    fun hasExact(name: String, siblings: List<String>): Boolean {
        val nfc = Normalizer.normalize(name, Normalizer.Form.NFC)
        return siblings.any { Normalizer.normalize(it, Normalizer.Form.NFC) == nfc }
    }

    /**
     * The sibling [name] folds onto: a name of [siblings] that is not [name] itself (NFC, exact) and has its key; the
     * smallest when several. Null when none does, and null when a sibling bears exactly [name]: that is a true
     * duplicate, which keeps its own handling.
     */
    fun foldedSibling(name: String, siblings: List<String>): String? {
        if (hasExact(name, siblings)) return null
        val mine = key(name)
        return siblings.filter { key(it) == mine }.minOrNull()
    }
}

private val foldLog = org.slf4j.LoggerFactory.getLogger(OneDriveNameFolds::class.java)

/**
 * cli#950 (owner decision: no silent " 1" copies): the drive answered a create 409. When the parent's listing holds
 * no sibling of exactly that name, it is no true duplicate: the drive folded the name onto something, and the keep-both
 * `rename` would land it as "<sibling> 1.ext" with the original missing. The name is refused as `similar_name`,
 * naming the sibling the fold key matches ([OneDriveNameFolds.foldedSibling]) or none when the key does not know the
 * fold (WARN with the name and the listing's names). An exact-name sibling returns null: a true duplicate, which keeps
 * its own handling (keep-both), as does a listing that fails (nothing is known).
 */
internal suspend fun GraphApiService.refuseFoldedName(path: String, refused: GraphApiException): Nothing? {
    val parent = path.substringBeforeLast('/', "").ifEmpty { "/" }
    val name = path.substringAfterLast('/')
    val names =
        try {
            listChildren(parent).mapNotNull { it.name }
        } catch (_: GraphApiException) {
            return null
        } catch (_: java.io.IOException) {
            return null
        }
    if (OneDriveNameFolds.hasExact(name, names)) return null
    val sibling = OneDriveNameFolds.foldedSibling(name, names)
    if (sibling == null) {
        foldLog.warn(
            "OneDrive answered 409 for '{}' but the folder lists no sibling of that name and none the fold key matches; refusing it as " +
                "similar_name (other names there: {}).",
            path,
            names.takeIf { it.isNotEmpty() }?.take(20)?.joinToString(", ") { "'$it'" } ?: "none found",
        )
    }
    throw NameRefusedException(
        "OneDrive treats the name of '$path' as identical to ${sibling?.let { "'$it'" } ?: "another item"} in the same folder and refuses it (409 nameAlreadyExists): ${refused.message}",
        reason = NameRefusedException.SIMILAR_NAME,
        sibling = sibling,
        cause = refused,
        requestId = refused.requestId,
    )
}
