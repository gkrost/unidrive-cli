package org.krost.unidrive.internxt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.krost.unidrive.UnidriveJson
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

// The rename of [UploadTombstoneStore.write]: atomic where the file system can, a plain replace where it cannot.
private fun moveIntoPlace(
    tmpPath: Path,
    finalPath: Path,
) {
    try {
        Files.move(tmpPath, finalPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        // Some filesystems (e.g. cross-volume tmpfs on certain CI runners)
        // refuse ATOMIC_MOVE; fall back to a plain replace. The non-atomic
        // window is small and the read path handles a malformed file as
        // "no tombstone", so the worst case is a forced re-encrypt.
        Files.move(tmpPath, finalPath, StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * Compensates for Internxt's missing resumable-upload protocol by recording
 * per-upload progress to a JSON sidecar + a co-located encrypted-ciphertext
 * temp file. A daemon kill between encrypt and finishUpload can resume the
 * exact same `indexBytes` / iv / fileKey / ciphertext / hash on the next
 * `upload()` call for the same local path — no re-encrypt, no orphan shard
 * commits.
 *
 * Storage layout: `${tokenPath}/upload-tombstones/<sha256(localPath)>.json`
 * + `${tokenPath}/upload-tombstones/<sha256(localPath)>.enc`. POSIX 0600
 * where the platform supports it (Internxt creds live next door on the
 * same mode). The directory is created lazily on first write.
 *
 * Atomicity: the JSON sidecar is written to a `.tmp` sibling then renamed
 * with `ATOMIC_MOVE`, so a crash mid-write never leaves a half-parsed
 * tombstone visible. The ciphertext temp file is overwritten in place;
 * if a crash truncates it the resume path detects the size mismatch
 * against `encrypted_size` and rotates the tombstone.
 *
 * Staleness is the caller's call. The store hands back what's on disk and
 * exposes [discard] for the caller to drop a tombstone whose preconditions
 * no longer match. The 7-day / 10-minute thresholds live on
 * [InternxtConfig.RESUME_TTL_MS] / [InternxtConfig.URL_TTL_MS].
 */
internal class UploadTombstoneStore(
    private val tombstoneDir: Path,
    // The two seams of [write]'s local retry (archive#1203): the rename into place, and the pause between two tries. Tests pass their own.
    private val move: (Path, Path) -> Unit = ::moveIntoPlace,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {
    /**
     * Read the tombstone at [pathHash] if present. Returns null when the
     * sidecar is absent, malformed, or its schema discriminator doesn't
     * match the current shape (a future schema bump will route the old
     * shape into discard rather than try to parse it).
     */
    fun read(pathHash: String): UploadTombstone? {
        val jsonPath = tombstoneDir.resolve("$pathHash.json")
        if (!Files.exists(jsonPath)) return null
        val raw =
            try {
                Files.readString(jsonPath)
            } catch (_: Exception) {
                return null
            }
        return try {
            val parsed = json.decodeFromString(UploadTombstone.serializer(), raw)
            if (parsed.schema != UploadTombstone.CURRENT_SCHEMA) null else parsed
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Atomic write of [tombstone] to its `.json` sidecar. Caller is
     * responsible for refreshing [UploadTombstone.tombstoneWrittenAtMillis]
     * to `Instant.now().toEpochMilli()` on every transition — the store
     * just persists what's handed in.
     */
    fun write(
        pathHash: String,
        tombstone: UploadTombstone,
    ) {
        ensureDir()
        val finalPath = tombstoneDir.resolve("$pathHash.json")
        val tmpPath = tombstoneDir.resolve("$pathHash.json.tmp")
        val body = json.encodeToString(UploadTombstone.serializer(), tombstone)
        // archive#1203: on Windows the rename over an existing sidecar fails with AccessDeniedException while another handle holds the target
        // without delete sharing (a scanner that just looked at the file, a reader of the same path). That is brief, so try a few times
        // before the caller sees it; the half-written temp file is removed when the write finally fails.
        var attempt = 0
        while (true) {
            try {
                Files.writeString(tmpPath, body)
                move(tmpPath, finalPath)
                break
            } catch (e: java.nio.file.FileSystemException) {
                attempt++
                if (attempt >= WRITE_ATTEMPTS) {
                    runCatching { Files.deleteIfExists(tmpPath) }
                    throw e
                }
                pause(WRITE_PAUSE_MS * attempt)
            }
        }
        tryRestrictPermissions(finalPath)
    }

    /**
     * Drop the JSON sidecar AND any co-located ciphertext file for
     * [pathHash]. Idempotent — missing files are ignored. Called both on
     * upload success (cleanup) and on stale-tombstone detection (discard).
     */
    fun discard(pathHash: String) {
        Files.deleteIfExists(tombstoneDir.resolve("$pathHash.json"))
        Files.deleteIfExists(tombstoneDir.resolve("$pathHash.json.tmp"))
        Files.deleteIfExists(ciphertextPath(pathHash))
    }

    /**
     * Drop every tombstone, ciphertext file and leftover temp file of the store (a sign-out: all of them
     * belong to the account that is leaving). Idempotent; a file that cannot be removed is skipped.
     */
    fun discardAll() {
        if (!Files.isDirectory(tombstoneDir)) return
        val entries =
            try {
                Files.list(tombstoneDir).use { it.toList() }
            } catch (_: Exception) {
                return
            }
        for (path in entries) {
            try {
                Files.deleteIfExists(path)
            } catch (_: Exception) {
                // Best effort: the next sign-in's bucket check keeps a leftover from being resumed anyway.
            }
        }
    }

    /**
     * Deterministic ciphertext temp-file location for [pathHash]. The
     * resume path writes here on stage 3 and the in-flight path reads
     * from here on stages 5-7; success-path cleanup runs through
     * [discard].
     */
    fun ciphertextPath(pathHash: String): Path = tombstoneDir.resolve("$pathHash.enc")

    /**
     * Sweep tombstones whose underlying `local_path` no longer exists OR
     * whose `tombstone_written_at_millis` is older than [maxAgeMs]. Called
     * inline at the top of each `upload()` so an account with a few stale
     * tombstones from old kills doesn't accumulate them indefinitely.
     * Bounded by the directory listing size — typical accounts will see
     * single-digit entries here.
     */
    fun gc(maxAgeMs: Long) {
        if (!Files.isDirectory(tombstoneDir)) return
        val nowMillis = System.currentTimeMillis()
        val entries =
            try {
                Files.list(tombstoneDir).use { stream ->
                    stream.toList()
                }
            } catch (_: Exception) {
                return
            }
        for (path in entries) {
            val name = path.fileName.toString()
            if (!name.endsWith(".json")) continue
            val pathHash = name.removeSuffix(".json")
            val tomb = read(pathHash) ?: run {
                // Unparseable / mismatched schema — kill the pair.
                discard(pathHash)
                continue
            }
            val ageMs = nowMillis - tomb.tombstoneWrittenAtMillis
            val localStillExists =
                try {
                    Files.exists(java.nio.file.Paths.get(tomb.localPath))
                } catch (_: Exception) {
                    false
                }
            if (ageMs > maxAgeMs || !localStillExists) {
                discard(pathHash)
            }
        }
    }

    private fun ensureDir() {
        if (Files.isDirectory(tombstoneDir)) return
        Files.createDirectories(tombstoneDir)
        tryRestrictPermissions(tombstoneDir)
    }

    private fun tryRestrictPermissions(path: Path) {
        // POSIX-only; no-op on Windows where the parent profile dir already
        // carries the appropriate ACLs.
        try {
            val view = Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView::class.java)
            if (view != null) {
                val perms =
                    if (Files.isDirectory(path)) {
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
                    } else {
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
                    }
                view.setPermissions(perms)
            }
        } catch (_: Exception) {
            // Best-effort; the parent profile dir already restricts access.
        }
    }

    companion object {
        private val json: Json = UnidriveJson

        /** archive#1203: how often a write tries the rename into place, and the first pause (the n-th pause is n times this: 20, 40, 80, 160 ms). */
        private const val WRITE_ATTEMPTS = 5
        private const val WRITE_PAUSE_MS = 20L

        /**
         * Head/tail window size for [contentFingerprint]. Files at or below
         * this size are hashed in full, so any byte change trips the check;
         * larger files pin the first and last 64 KiB.
         */
        private const val FINGERPRINT_WINDOW_BYTES: Int = 64 * 1024

        /**
         * `sha256(localPath.absolute().toString())` as lowercase hex.
         * Used as the stable per-upload key so concurrent `upload()` calls
         * for different paths can't collide. Path normalisation is left
         * to the caller — the same path string in always produces the
         * same hash out.
         */
        fun pathHash(localPathString: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(localPathString.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * Cheap content-identity fingerprint (archive#311): SHA-256 over the first
         * and last [FINGERPRINT_WINDOW_BYTES] of the file (the whole file
         * when it is no larger than one window). mtime+size alone do not
         * prove content identity — in-place writers that restore mtime
         * (`rsync --times`, editors that save in place) can swap bytes
         * underneath a resume — so the tombstone pins this fingerprint at
         * cold start and the provider re-derives and compares it before any
         * reuse of the pinned `indexBytes`.
         *
         * Not a general integrity check: a same-size rewrite confined to the
         * middle of a file larger than two windows is invisible to it. That
         * residual is covered separately in the provider — a stage beyond
         * ENCRYPTING whose `.enc` can't be byte-validated always cold-restarts
         * rather than re-encrypting under the pinned keystream.
         */
        fun contentFingerprint(localPath: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileChannel.open(localPath, StandardOpenOption.READ).use { channel ->
                val size = channel.size()
                val window = FINGERPRINT_WINDOW_BYTES.toLong()
                digest.update(readWindow(channel, 0L, minOf(size, window)))
                if (size > window) {
                    digest.update(readWindow(channel, size - window, window))
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun readWindow(
            channel: FileChannel,
            position: Long,
            length: Long,
        ): ByteArray {
            val buffer = ByteBuffer.allocate(length.toInt())
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer, position + buffer.position())
                if (read < 0) break // file shrank between size() and this read
            }
            return buffer.array().copyOf(buffer.position())
        }
    }
}

/**
 * Crash-recoverable record for an in-flight Internxt upload. Persisted as
 * a JSON sidecar by [UploadTombstoneStore]. Every field except
 * [tombstoneWrittenAtMillis] is set once on first write; the
 * write-timestamp refreshes on each [UploadTombstoneStore.write] so the
 * URL-TTL freshness check can fence off a presigned PUT URL handed out
 * more than [InternxtConfig.URL_TTL_MS] ago.
 *
 * Fields are wire-named with snake_case to match the rest of unidrive's
 * sidecar JSON conventions and to keep a future hex-editor / `jq`
 * inspection readable. Secrets policy: stores `index_bytes_hex` (32-byte
 * server-stored salt that derives the IV via the mnemonic but is itself
 * not a key); does NOT store the derived `fileKey`, the mnemonic, or
 * the IV. A leaked tombstone without the mnemonic is not sufficient to
 * decrypt the ciphertext.
 */
@Serializable
internal data class UploadTombstone(
    val schema: Int = CURRENT_SCHEMA,
    val localPath: String,
    val localMtimeMillis: Long,
    val localSize: Long,
    val bucket: String,
    val folderUuid: String,
    val plainName: String,
    val ext: String?,
    val indexBytesHex: String,
    val shardUuid: String? = null,
    val bridgePutUrl: String? = null,
    val encryptedSize: Long? = null,
    val hashHex: String? = null,
    val existingRemoteId: String? = null,
    /**
     * archive#311: `UploadTombstoneStore.contentFingerprint()` of the local file at
     * cold start. The resume path re-derives it and treats a mismatch — or
     * a null (only possible in hand-written tombstones; schema-1 sidecars
     * are rejected at parse time) — as staleness, forcing a cold restart
     * with fresh indexBytes instead of reusing the pinned CTR keystream
     * over unknown plaintext.
     */
    val contentFingerprintHex: String? = null,
    val stage: Stage,
    val startedAtMillis: Long,
    val tombstoneWrittenAtMillis: Long,
    /**
     * archive#578: multipart session state, present only when this upload went out in parts.
     * The staged `.enc` is the source of truth for the ciphertext, so a resume re-PUTs
     * only the parts missing from [MultipartResume.parts] — within the presigned-URL
     * TTL. Past it the session's URLs are dead and the part records are dropped (the
     * `.enc`, hash and index stay pinned), so the next attempt re-PUTs every part from
     * disk rather than re-encrypting anything.
     */
    val multipart: MultipartResume? = null,
    /**
     * archive#1203: the Bridge's file id once `finishUpload` answered, written with [Stage.FINISH_DONE]. A retry or a resume at that stage
     * goes on to the Drive's createFile with it instead of finishing the exhausted session again (which answers 409 and, with no
     * listed entry yet, ends as a refusal). Null in a sidecar from before, which keeps the old finish-again path.
     */
    val bridgeFileId: String? = null,
) {
    enum class Stage {
        ENCRYPTING,
        PUT_PENDING,
        PUT_DONE,
        FINISH_DONE,
    }

    companion object {
        // v2 (archive#311): adds content_fingerprint_hex. Schema-1 tombstones have no
        // content binding, so they are routed to discard (read() returns null
        // → gc sweeps them) rather than trusted for a resume.
        const val CURRENT_SCHEMA: Int = 2
    }
}

/** One completed part of a archive#578 multipart session: its 0-based index and the server's ETag. */
@Serializable
internal data class MultipartPartRecord(
    val index: Int,
    val etag: String,
)

/**
 * archive#578: what a multipart upload persists between attempts — the part plan (chunk size,
 * part count) and the session identity (upload id, session uuid, presigned part URLs),
 * plus the parts that already landed. [bucketEntryId] is pinned the moment the finish
 * call answers, so a crash between finish-OK and the Drive registration resumes without
 * re-finishing (the single-part pipeline laments not having exactly this; multipart can
 * afford it because the part-list writes already rewrite the sidecar).
 */
@Serializable
internal data class MultipartResume(
    val chunkSize: Long,
    val numParts: Int,
    val uploadId: String,
    val sessionUuid: String,
    val urls: List<String>,
    val parts: List<MultipartPartRecord> = emptyList(),
    val bucketEntryId: String? = null,
    // When the bridge minted this session's presigned URLs. Their expiry is absolute, so the
    // age of a session runs from here, never from the last sidecar write (every landed part
    // rewrites the sidecar and would keep calling a dead session fresh). 0 in a sidecar from
    // before this field existed reads as long-expired: the session is dropped and re-put.
    val issuedAtMillis: Long = 0,
)
