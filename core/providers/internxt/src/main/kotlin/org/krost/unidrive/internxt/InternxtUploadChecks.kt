package org.krost.unidrive.internxt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

// The upload's own checks and the answers it reads specially, kept out of InternxtProvider (size ratchet, #534).

// Live finding 2026-11-18: the bridge answers an upload start (single-part and multipart) with 420
// {"message":"Max space used"} when the account's storage is full. Only that sentence is mapped; another 420 keeps
// its own type.
internal const val MAX_SPACE_STATUS = 420

// #747 finding 5, live 2026-11-18: the answer to a replace naming the file's current object.
internal fun isSameFileIdRefusal(e: InternxtApiException): Boolean =
    e.statusCode == 400 && e.message.orEmpty().contains("New file ID is the same as the old file ID")

internal fun isMaxSpaceUsed(e: InternxtApiException): Boolean =
    e.statusCode == MAX_SPACE_STATUS && e.message.orEmpty().contains("Max space used", ignoreCase = true)

private const val ENCRYPT_BUFFER_BYTES = 256 * 1024

/** The free space an upload leaves on the staging volume beyond its staged copy (#441). */
internal const val STAGING_SPACE_MARGIN_BYTES: Long = 256L * 1024 * 1024

/**
 * The encrypt pass of an upload (#747 finding 10): [source] through [cipher] into [out], the ciphertext also into
 * [digest]. Reads in [ENCRYPT_BUFFER_BYTES] chunks, as the download path and the local content proof do; a
 * CipherInputStream hands out at most 512 bytes per read whatever the caller's buffer.
 */
internal fun encryptToStaging(
    source: java.io.InputStream,
    cipher: javax.crypto.Cipher,
    out: java.io.OutputStream,
    digest: java.security.MessageDigest,
) {
    val buf = ByteArray(ENCRYPT_BUFFER_BYTES)
    val enc = ByteArray(cipher.getOutputSize(buf.size))
    while (true) {
        val n = source.read(buf)
        if (n < 0) break
        if (n == 0) continue
        val m = cipher.update(buf, 0, n, enc, 0)
        out.write(enc, 0, m)
        digest.update(enc, 0, m)
    }
    val tail = cipher.doFinal()
    out.write(tail)
    digest.update(tail)
}

// Live finding E3 (2026-11-18): the file create's answer when its parent folder is gone (trashed after the walk, or
// reached through a stale cached uuid).
internal fun isParentFolderNotFound(e: InternxtApiException): Boolean =
    e.statusCode == 404 && e.message.orEmpty().contains("Folder not found")

// The parent of [remotePath] is gone (see [isParentFolderNotFound]): its cached uuid is dropped, so the caller's remake of
// the parent and the next upload walk the listing again, and the failure is the typed parent-gone refusal.
internal fun parentGoneAfterCreate(
    e: InternxtApiException,
    remotePath: String,
    folderCache: FolderUuidCache,
    rootUuid: String,
): org.krost.unidrive.ParentGoneException {
    val parentSegments = InternxtProvider.pathSegments(remotePath).dropLast(1)
    folderCache.invalidatePath(rootUuid, parentSegments)
    return org.krost.unidrive.ParentGoneException(
        "parent folder of $remotePath is gone in the cloud: ${e.message}",
        parent = "/" + parentSegments.joinToString("/"),
        cause = e,
        requestId = e.requestId,
    )
}

// gkrost/unidrive-cli#793: the answers to a file create that leave its outcome unknown (the write ladder does not repeat them).
internal val INDETERMINATE_CREATE_STATUSES = setOf(500, 502, 504)

// gkrost/unidrive-cli#441 finding 10: the encrypt pass writes a full copy of the file next to the profile, usually on the
// system drive. Before it starts, the volume must hold that copy plus [STAGING_SPACE_MARGIN_BYTES] (a stale partial copy
// of an earlier attempt is overwritten, so its bytes count as free); otherwise the upload fails typed, before anything is
// written, instead of filling the volume (state DB and logs included) and failing with a generic IOException on every
// pass. A volume that cannot say how much is free does not block the upload.
@Suppress("TooGenericExceptionCaught")
internal suspend fun ensureStagingSpace(
    tempFile: Path,
    fileSize: Long,
    remotePath: String,
    usableSpaceOf: (Path) -> Long,
    log: org.slf4j.Logger,
) {
    val required = fileSize + STAGING_SPACE_MARGIN_BYTES
    val usable =
        withContext(Dispatchers.IO) {
            try {
                Files.createDirectories(tempFile.parent)
                val stale = if (Files.exists(tempFile)) Files.size(tempFile) else 0L
                usableSpaceOf(tempFile.parent) + stale
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("upload of {}: the free space of the staging volume cannot be read ({}); not checked", remotePath, e.message)
                null
            }
        } ?: return
    if (usable >= required) return
    throw org.krost.unidrive.InsufficientLocalSpaceException(
        "not enough local space to stage the upload of $remotePath: $required bytes needed (the file plus a " +
            "${STAGING_SPACE_MARGIN_BYTES / (1024 * 1024)} MiB margin), $usable free on the staging volume",
        requiredBytes = required,
        usableBytes = usable,
    )
}

/**
 * Whether two write tokens ([InternxtProvider.internxtVersionToken], "uuid|modificationTime|size") name the same version of
 * a file (live finding F1, 2026-11-18). The uuid and the size must be equal as written; the modification time is compared
 * to the whole second when both sides read as an instant, because the server spells one instant with and without
 * milliseconds depending on the answer. A time that does not read is compared as written.
 */
internal fun sameWriteToken(
    a: String,
    b: String,
): Boolean {
    val pa = a.split('|')
    val pb = b.split('|')
    if (pa.size != 3 || pb.size != 3) return a == b
    if (pa[0] != pb[0] || pa[2] != pb[2]) return false
    val ta = org.krost.unidrive.internxt.model.tryParseInternxtInstant(pa[1])
    val tb = org.krost.unidrive.internxt.model.tryParseInternxtInstant(pb[1])
    if (ta == null || tb == null) return pa[1] == pb[1]
    return ta.epochSecond == tb.epochSecond
}
