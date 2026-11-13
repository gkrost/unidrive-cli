package org.krost.unidrive.internxt

import org.krost.unidrive.internxt.InternxtProvider.Companion.isLive
import org.krost.unidrive.internxt.model.InternxtFile
import org.krost.unidrive.internxt.model.InternxtFolder
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.ZoneOffset

// The generated-name and name-matching helpers of InternxtProvider, top-level in the package so
// the provider's own file keeps to the size ratchet. Everything here is a pure function of its
// arguments; the provider's instance state (api, crypto, config) stays behind in the class.

/**
 * UD-369: split a leaf filename into (plainName, type). Returned type is the bare extension or null.
 *
 * The listing joins the two again as `plainName.type`, but strips control characters from both ends of the
 * plainName and does not repeat a type the plainName already ends in ([fileToCloudItem]). A name for which that
 * join is not the name itself is therefore kept whole in the plainName, with no type: `a.txt.txt` (split as
 * `a.txt` + `txt` it is listed as `a.txt`), `a.` (type empty, listed as `a`), and a dotfile (an empty plainName,
 * which the drive refuses). A name that is listed under another name is a rename of the user's file as far as the
 * engine can tell, and a path lookup of the uploaded name no longer finds it.
 *
 * A stem or type with whitespace at an end (`Scan 1 .pdf`, ` leading space.txt`) is also kept whole. Until #144 the
 * listing trimmed spaces too, and that is the shape such names have been uploaded in; keeping it means the fix
 * changes only how a name is listed, not what is sent.
 */
internal fun splitLeaf(filename: String): Pair<String, String?> {
    val dot = filename.lastIndexOf('.')
    if (dot <= 0 || dot == filename.length - 1) return filename to null
    val stem = filename.substring(0, dot)
    val type = filename.substring(dot + 1)
    if (stem != stem.trim() || type != type.trim() || stem.endsWith(".$type")) return filename to null
    return stem to type
}

internal fun stripExtension(filename: String): String = splitLeaf(filename).first

internal fun newFileType(filename: String): String? = splitLeaf(filename).second

// Build the archive plainName for the destructive-overwrite guard.
// Returns `${plainName}.unidrive-prev-${yyyy-MM-dd'T'HH-mm-ss}` in UTC
// (`-` instead of `:` for NTFS portability). [counter] > 1 appends a
// `-N` suffix to break collisions when two replaces of the same file
// fall inside the same wall-clock second. archive#1091: the plainName is cut (on a
// code-point boundary) so that plainName + suffix + `.` + [type] stays within
// 255 UTF-16 units; [type] is the file's extension, which is appended after.
internal fun keepOverwrittenArchiveName(
    plainName: String,
    now: Instant,
    counter: Int = 1,
    type: String? = null,
    strict: Boolean = false,
): String {
    val stamp =
        DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH-mm-ss")
            .withZone(ZoneOffset.UTC)
            .format(now)
    val tail = ".unidrive-prev-$stamp" + (if (counter <= 1) "" else "-$counter")
    return fitGeneratedStem(plainName, tail, type, strict) + tail
}

// The units `.type` adds to a plainName in the name a user sees.
private fun extensionUnits(type: String?): Int = if (type.isNullOrEmpty()) 0 else type.length + 1

// UD-366: build the conflict-copy plainName for the keep-both collision
// handler.  Format: `$plainName (conflict $date)` for counter=1, then
// `$plainName (conflict $date) (N)` for N ≥ 2.  [date] is a yyyy-MM-dd
// string passed by the caller so it can be pinned in tests. archive#1091: the
// plainName is cut (on a code-point boundary) so that plainName + suffix + `.` + [type]
// stays within 255 UTF-16 units; [type] is the file's extension, appended after.
internal fun conflictName(
    plainName: String,
    date: String,
    counter: Int = 1,
    type: String? = null,
    strict: Boolean = false,
): String {
    val tail = " (conflict $date)" + (if (counter <= 1) "" else " ($counter)")
    return fitGeneratedStem(plainName, tail, type, strict) + tail
}

// The stem of a generated name, cut for the whole name (stem, [tail], `.type`) to fit 255 UTF-16 units, and with
// `internxt_strict_names` ([strict]) 255 UTF-8 bytes too.
private fun fitGeneratedStem(
    plainName: String,
    tail: String,
    type: String?,
    strict: Boolean,
): String {
    val stem = org.krost.unidrive.io.NameLimit.fitStem(plainName, tail.length + extensionUnits(type))
    return if (strict) InternxtNameRules.fitStem(stem, tail + if (type.isNullOrEmpty()) "" else ".$type") else stem
}

/**
 * Look-back window applied to the persisted delta cursor before each
 * `/files` and `/folders` query, so items that mutated in the seam
 * between the prior gather's `max(updatedAt)` snapshot and the cursor
 * write are caught on the next pass.
 *
 * 2 minutes matches drive-desktop's `TWO_MINUTES_IN_MILLISECONDS`
 * (`src/apps/main/remote-sync/helpers/get-checkpoint.ts:19`). The
 * earlier 6-hour window was a defensive over-correction from when
 * cursor promotion was unreliable; with promote-always now in place
 * and `sort=updatedAt` on the delta path advancing the effective
 * cursor per page, 2 minutes is sufficient and turns "every restart
 * re-walks 6 h of activity" into "re-walks the last 120 s." On a hot
 * account this is the difference between a sub-second incremental
 * scan and a multi-minute one.
 */
internal fun rewindCursor(cursor: String): String {
    val instant = Instant.parse(cursor)
    return instant.minus(REWIND_CURSOR_SECONDS, ChronoUnit.SECONDS).toString()
}

internal const val REWIND_CURSOR_SECONDS: Long = 120

// Live rows whose full name is exactly [name]. Empty when nothing live matches. More than one
// entry is ambiguous: callers must not pick.
internal fun liveFilesForName(
    files: List<InternxtFile>,
    name: String,
): List<InternxtFile> = files.filter { file -> file.isLive() && fileMatchesFullName(file, name) }

// archive#294 name logic shared by getMetadata, the 409 handlers and findFileByName:
// exact full-name (base + type recomposition) match.
internal fun fileMatchesFullName(
    file: InternxtFile,
    name: String,
): Boolean = recomposedFullName(file) == name

// The full name a stored plainName + type pair spells: the type appended unless the base
// already ends in it. Case-preserving — the exact matcher must stay exact; the 409
// near-miss folds case on this same recomposition and nothing else.
internal fun recomposedFullName(file: InternxtFile): String {
    val baseName = InternxtProvider.sanitizeName(file.plainName ?: file.name ?: "")
    val cleanType = file.type?.let { InternxtProvider.sanitizeName(it) }
    return if (!cleanType.isNullOrEmpty() && !baseName.endsWith(".$cleanType")) "$baseName.$cleanType" else baseName
}

/** The outcome of the UD-366 candidate pick after a 409 on POST /files. */
internal sealed interface CreateCollisionPick {
    /** One candidate the content-identity ladder must judge (an exact live match, or the single case-form near-miss). */
    class Candidate(val file: InternxtFile) : CreateCollisionPick

    /** The name is held, but no live candidate can be proven or guessed: keep both (conflict copy). */
    object KeepBoth : CreateCollisionPick

    /** The refusal does not come from a name this listing can see: rethrow the original 409. */
    object Refuse : CreateCollisionPick
}

/**
 * Pick the candidate for the create-collision ladder from the parent listing: the one live file with the exact
 * full name; with none, the one live file that spells the same full name in another case form (the case folds on
 * the recomposed full name only — a different extension is a different name and stays a stranger); with none of
 * those, a non-live holder of the exact name means keep-both; anything else means the listing cannot explain the
 * refusal. More than one exact live match keeps both, as before.
 */
internal fun pickCreateCollisionCandidate(
    files: List<InternxtFile>,
    children: List<InternxtFolder>,
    wantedName: String,
    remotePath: String,
    log: org.slf4j.Logger,
): CreateCollisionPick {
    if (children.any { folder -> InternxtProvider.sanitizeName(folder.plainName ?: folder.name ?: "") == wantedName }) {
        // Path maps to a folder. Undefined for a file create; nothing here can prove content
        // identity with a folder, so the original refusal stands.
        log.warn(
            "UD-366: 409 on POST /files for {}: the parent listing ({} files) holds a folder of that name; rethrowing",
            remotePath, files.size,
        )
        return CreateCollisionPick.Refuse
    }
    val namedFiles = liveFilesForName(files, wantedName)
    if (namedFiles.size == 1) return CreateCollisionPick.Candidate(namedFiles.single())
    if (namedFiles.size > 1) return CreateCollisionPick.KeepBoth
    val nearMisses =
        files.filter { candidate ->
            candidate.isLive() && recomposedFullName(candidate).equals(wantedName, ignoreCase = true)
        }
    if (nearMisses.size == 1) {
        log.warn(
            "UD-366: 409 on POST /files for {}: no live file of that exact full name, but one live file spells the same full name in another case form; running the content-identity check on it",
            remotePath,
        )
        return CreateCollisionPick.Candidate(nearMisses.single())
    }
    val hiddenHolders = files.count { candidate -> !candidate.isLive() && fileMatchesFullName(candidate, wantedName) }
    if (hiddenHolders > 0) {
        log.warn(
            "UD-366: 409 on POST /files for {}: the name is held by {} non-live (trashed) item(s) the live filter drops; keeping both — the local file lands as a conflict copy",
            remotePath, hiddenHolders,
        )
        return CreateCollisionPick.KeepBoth
    }
    log.warn(
        "UD-366: 409 on POST /files for {}: the parent listing ({} files) holds no live exact match, no single case-form near-miss and no non-live holder; rethrowing — the refusal does not come from a name this listing can see",
        remotePath, files.size,
    )
    return CreateCollisionPick.Refuse
}
