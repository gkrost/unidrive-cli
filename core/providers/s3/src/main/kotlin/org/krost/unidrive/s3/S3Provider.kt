package org.krost.unidrive.s3

import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.Capability
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CaseSensitivity
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.ContentProof
import org.krost.unidrive.CredentialProof
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.PermanentDownloadFailureException
import org.krost.unidrive.PageGuard
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.RemoteConflictException
import org.krost.unidrive.ScanContext
import org.krost.unidrive.ScanProgress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

/**
 * A bucket on Amazon S3 or an S3-compatible service.
 *
 * - **Keys and paths:** the path `/a/b.txt` is the key `a/b.txt`, and the key is the item's id. Folders are key
 *   prefixes. A folder made here gets a marker object (`a/`); one implied by keys below it is reported all the same.
 * - **Changes:** S3 has no change feed. [delta] lists the whole bucket ([deltaIsFullListing]), so the engine finds
 *   deletions by absence. Through the scan context, a listing cut off half-way resumes after the last key it kept.
 * - **Uploads:** above [S3Config.multipartThreshold], multipart, with parts streamed from the file and the open upload
 *   kept in [MultipartStore], so a second attempt sends only the parts S3 does not hold yet. `x-amz-meta-mtime` carries
 *   the file's mtime as rclone writes it.
 * - **Conditional writes:** the engine's write token is the ETag it saw. Where the vendor honours `If-Match`
 *   (AWS), the write carries it and a 412 is a conflict. Elsewhere a HEAD compares first, which leaves a short race.
 *   A create carries `If-None-Match: *`, so a key taken since the plan is a conflict too; a vendor that refuses the
 *   header (501, 400) gets a HEAD that checks the key is free; with the profile key `s3_create_from_listing = true` that HEAD
 *   is left out and the listing that made the engine plan a create is trusted instead (#573: one request fewer per object, a
 *   longer window for another writer's object to be replaced; off by default).
 * - **Copies** (moves) above 5 GiB go through UploadPartCopy on every vendor.
 */
class S3Provider(
    val config: S3Config,
    engine: HttpClientEngine? = null,
    clock: () -> Instant = Instant::now,
    uploadWatchdog: org.krost.unidrive.http.UploadWatchdogLimits = org.krost.unidrive.http.UploadWatchdogLimits.DEFAULT,
) : CloudProvider {
    override val id = "s3"
    override val displayName = "S3 / S3-compatible"

    private val log = org.slf4j.LoggerFactory.getLogger(S3Provider::class.java)
    private val api = S3Api(config, engine, clock, uploadWatchdog = uploadWatchdog)
    private val uploads = MultipartStore(config.tokenPath.resolve("s3-uploads"))

    // How many parts of one multipart upload are in flight at once. The daemon's --upload-concurrency sets it; the
    // shared request budget still caps the requests of every transfer together.
    @Volatile
    private var partConcurrency: Int = DEFAULT_PART_CONCURRENCY

    override fun multipartUploadConcurrency(): Int = partConcurrency

    override fun overrideUploadConcurrency(concurrency: Int): Boolean {
        require(concurrency in 1..S3Api.MAX_CONCURRENCY) { "upload concurrency $concurrency is out of range 1..${S3Api.MAX_CONCURRENCY}" }
        partConcurrency = concurrency
        return true
    }

    override var isAuthenticated: Boolean = false

    override val canAuthenticate: Boolean
        get() = config.bucket.isNotBlank() && config.accessKey.isNotBlank() && config.secretKey.isNotBlank()

    override val deltaIsFullListing: Boolean get() = true

    // S3 object keys are case-sensitive: `a.txt` and `A.txt` are two objects.
    override val caseSensitivity: CaseSensitivity get() = CaseSensitivity.SENSITIVE

    override fun capabilities(): Set<Capability> = setOf(Capability.Delta, Capability.Share, Capability.VerifyItem, Capability.PermanentDelete)

    override fun hashAlgorithm(): HashAlgorithm = HashAlgorithm.Md5Hex

    override fun attachRequestMetrics(sink: org.krost.unidrive.http.RequestMetricsSink?) = api.attachRequestMetrics(sink)

    /** The keys are the credential: one listing of a single key proves them against the bucket. */
    override suspend fun authenticate() {
        try {
            api.list("", maxKeys = 1)
            isAuthenticated = true
        } catch (e: AuthenticationException) {
            isAuthenticated = false
            throw e
        } catch (e: S3Exception) {
            if (e.status != 403) throw e
            // Keys that may not even list the bucket are a refused sign-in, whichever 403 the store gives.
            isAuthenticated = false
            throw AuthenticationException(e.message ?: "S3 refused the keys", e, e.requestId)
        }
    }

    /** archive#1240: the keys are the credential, and [authenticate] is already one non-interactive listing: no sign-in can start from it. */
    override suspend fun checkCredentials(): CredentialProof {
        authenticate()
        return CredentialProof()
    }

    // [authenticate] is one listing with the configured keys — there is no stored grant to pick up and nothing
    // interactive it could fall back to, so the reload decision is that same proof.
    override suspend fun reloadCredentials() {
        authenticate()
    }

    override fun close() = api.close()

    // ── reading ──

    override suspend fun listChildren(path: String): List<CloudItem> {
        val prefix = prefixOf(path)
        val out = mutableListOf<CloudItem>()
        val pages = pageGuard("The S3 listing of '$path'")
        val skipped = DotSegmentSkips()
        var token: String? = null
        do {
            val page = api.list(prefix, delimiter = "/", token = token)
            page.objects.filter { it.key != prefix && skipped.admits(it.key) }.forEach { out += fileItem(it) }
            page.prefixes.filter(skipped::admits).filter { holdsListableKey(it, skipped) }.forEach { out += folderItem(pathOf(it)) }
            pages.follow(page.nextToken)
            token = page.nextToken
        } while (token != null)
        skipped.summarise(log)
        return out
    }

    /**
     * Whether [prefix] has a key a listing shows: a valid key or its own folder marker, below it at any depth. A folder whose
     * whole subtree is dot-segment keys is not listed (owner decision 2026-11-26). Usually the first page settles it.
     */
    private suspend fun holdsListableKey(
        prefix: String,
        skipped: DotSegmentSkips,
    ): Boolean {
        val pages = pageGuard("The S3 listing under '$prefix'")
        var token: String? = null
        do {
            val page = api.list(prefix, token = token, maxKeys = FOLDER_PROBE_KEYS)
            if (page.objects.any { skipped.admits(it.key) }) return true
            pages.follow(page.nextToken)
            token = page.nextToken
        } while (token != null)
        return false
    }

    override suspend fun getMetadata(path: String): CloudItem {
        val key = keyOf(path)
        if (key.isEmpty()) return folderItem("/")
        api.head(key)?.let { return fileItem(it) }
        if (api.list("$key/", maxKeys = 1).let { it.objects.isNotEmpty() || it.prefixes.isNotEmpty() }) return folderItem(pathOf(key))
        throw S3Exception("S3 has nothing at '$path'", 404, "NoSuchKey")
    }

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long {
        val key = keyOf(remotePath)
        // One GET, no HEAD before it (#569). What the HEAD supplied now comes from the GET itself: the declared size from its
        // Content-Length (it sizes the body timeout and is what the byte count is checked against) and "gone" from its 404.
        return try {
            api.get(key, destination)
        } catch (e: S3Exception) {
            if (e.status == 404) throw PermanentDownloadFailureException("S3 has no object at '$remotePath' any more", e, e.requestId)
            throw e
        }
    }

    override suspend fun verifyItemExists(remoteId: String): CapabilityResult<Boolean> {
        val exists =
            if (remoteId.endsWith("/")) {
                api.list(remoteId, maxKeys = 1).let { it.objects.isNotEmpty() || it.prefixes.isNotEmpty() }
            } else {
                api.head(remoteId) != null
            }
        return CapabilityResult.Success(exists)
    }

    // A multipart or encrypted object's ETag cannot prove plaintext bytes. A digest written with the upload can,
    // provided the object is still the same revision after reading the local file. Older objects remain unknown.
    override suspend fun contentMatchesLocal(remoteId: String, remotePath: String, local: Path): ContentProof? {
        val key = keyOf(remotePath)
        if (key.isEmpty() || remoteId != key) return null
        val before = api.head(key) ?: return null
        val expected = before.proofSha256?.takeIf { it.matches(SHA256_HEX) } ?: return null
        val etag = before.etag ?: return null
        val localBefore = localStats(local) ?: return null
        if (localBefore.second != before.size) return ContentProof(false, SHA256_PROOF)
        val actual = sha256Of(local)
        if (localStats(local) != localBefore) return null
        val after = api.head(key) ?: return null
        if (after.etag != etag || after.lastModified != before.lastModified) return null
        if (after.size != before.size || after.proofSha256 != expected) return null
        return ContentProof(actual.equals(expected, ignoreCase = true), SHA256_PROOF)
    }

    /**
     * The whole bucket, folders included (from markers and from the keys below them). With a scan context each page is
     * persisted with its last key as the marker, and a resumed listing starts after it, from the items staged before.
     */
    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
        scanContext: ScanContext?,
    ): DeltaPage {
        val files = LinkedHashMap<String, CloudItem>()
        val folders = sortedSetOf<String>()
        fun admit(item: CloudItem) {
            if (item.isFolder) folders += item.path else files[item.path] = item
            var parent = item.path.substringBeforeLast('/', "")
            while (parent.isNotEmpty() && folders.add(parent)) parent = parent.substringBeforeLast('/', "")
        }
        val skipped = DotSegmentSkips()
        val resumeAfter = scanContext?.resumeMarker
        // A staged item's path is a placeholder; its id is the key, which is the path.
        scanContext?.resumedItems?.filter { skipped.admits(it.id) }?.forEach { admit(if (it.isFolder) folderItem(pathOf(it.id)) else it.copy(path = pathOf(it.id), name = nameOf(it.id))) }
        var token: String? = null
        val pages = pageGuard("The S3 listing of the bucket")
        do {
            val page = api.list("", startAfter = resumeAfter, token = token)
            val pageItems = page.objects.filter { skipped.admits(it.key) }.map { if (it.key.endsWith("/")) folderItem(pathOf(it.key)) else fileItem(it) }
            pageItems.forEach(::admit)
            val last = page.objects.lastOrNull()?.key
            if (scanContext != null && last != null && !scanContext.readOnly) scanContext.persistPage(pageItems, last)
            val seen = files.size + folders.size
            onPageProgress?.invoke(seen)
            scanContext?.onProgress?.invoke(ScanProgress(seen, listing = ScanProgress.LISTING_ACCOUNT))
            pages.follow(page.nextToken)
            token = page.nextToken
        } while (token != null)
        skipped.summarise(log)
        val items = folders.map(::folderItem) + files.values
        return DeltaPage(items, cursor = FULL_LISTING, hasMore = false)
    }

    override suspend fun quota(): QuotaInfo = QuotaInfo(0, 0, 0)

    /**
     * gkrost/unidrive-cli#508: the largest object a multipart upload to this vendor can build, its part limit times the 5 GiB
     * part ceiling, the same bound [partSizeFor] refuses beyond. On AWS that is 10,000 parts, 48.8 TiB ("Amazon S3 multipart
     * upload limits": https://docs.aws.amazon.com/AmazonS3/latest/userguide/qfacts.html, "Maximum object size 48.8 TiB");
     * on Scaleway 1,000 parts. An S3-compatible endpoint may enforce less.
     */
    override fun maxFileSizeBytes(): Long = config.vendor.maxParts.toLong() * S3Config.MAX_PART_SIZE

    // ── writing ──

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        ifMatchETag: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        val key = keyOf(remotePath)
        if (key.isEmpty()) throw ProviderException("Refusing to upload to the bucket root")
        val size = Files.size(localPath)
        val mtime = Files.getLastModifiedTime(localPath).toInstant()
        val sourceStats = mtime.toEpochMilli() to size
        val singlePart = size <= config.multipartThreshold
        // One read of the file gives the SHA-256 proof and, for a single-part PUT, the MD5 that goes in Content-MD5 (#570): the
        // proof pass was there already, so the header costs the MD5 arithmetic and no further read.
        // A multipart upload takes each part's MD5 from the same pass (partSizeFor is fixed by the size), so the part reads only feed the bodies.
        val digests = digestsOf(localPath, md5 = singlePart, partSize = if (singlePart) 0L else partSizeFor(size))
        val digest = digests.sha256
        val contentMd5 = digests.md5
        if (localStats(localPath) != sourceStats) throw ProviderException("'$remotePath' changed while its upload proof was prepared")
        val meta = mapOf("mtime" to S3Api.epochText(mtime), "unidrive-sha256-v1" to digest)
        if (ifMatchETag != null && !config.vendor.conditionalWrites) requireUnchanged(key, ifMatchETag)
        val ifMatch = ifMatchETag?.takeIf { config.vendor.conditionalWrites }
        // gkrost/unidrive-cli#57: a create (nothing to replace, no token) must not replace an object that appeared after the plan.
        val create = existingRemoteId == null && ifMatchETag == null
        var put: S3PutResult? = null
        if (singlePart) {
            put =
                if (create) {
                    createGuarded(key) { api.put(key, localPath, meta, ifNoneMatch = it, contentMd5 = contentMd5) }
                } else {
                    api.put(key, localPath, meta, ifMatch, contentMd5 = contentMd5)
                }
        } else {
            uploadInParts(localPath, key, sourceStats, meta, digests.partMd5s, onProgress) { uploadId, parts ->
                if (create) {
                    createGuarded(key) { api.completeMultipart(key, uploadId, parts, ifNoneMatch = it) }
                } else {
                    api.completeMultipart(key, uploadId, parts, ifMatch)
                }
            }
        }
        // A single-part body is verified by S3 itself: it carries the Content-MD5 of the bytes the digest pass read and S3 stores
        // nothing for others (BadDigest), so a second read to hash it again proves nothing more (#748 finding 7). The parts of a
        // multipart upload are each checked against an MD5 taken in the digest pass; the re-hash still proves the whole file did not change.
        if (localStats(localPath) != sourceStats || (!singlePart && sha256Of(localPath) != digest)) {
            throw ProviderException("'$remotePath' changed while it was uploaded")
        }
        onProgress?.invoke(size, size)
        // Profile key `s3_no_head` (#574): a single-part upload's item comes from the PUT's answer and the local file, no HEAD.
        if (config.noHead && put != null) return putItem(key, size, mtime, put)
        // The HEAD stays (#574): the engine records this item's `modified` and compares it with the listing's LastModified in whole
        // seconds (#82 D), and no PUT or CompleteMultipartUpload answer carries that time (the `Date` header is the answer's time,
        // not the object's); the HEAD also tells the encryption kind that decides whether the ETag is an MD5 (`fileItem`).
        return fileItem(api.head(key) ?: throw ProviderException("S3 does not show '$key' right after its upload"))
    }

    /**
     * The item for an object just PUT, without a HEAD (`s3_no_head`). `modified` is the answer's `Date` in whole seconds, or null
     * when the answer has none ("unknown": the next listing supplies it); size and mtime are the local file's.
     */
    private fun putItem(
        key: String,
        size: Long,
        mtime: Instant,
        put: S3PutResult,
    ): CloudItem =
        fileItem(
            S3Object(
                key = key,
                etag = put.etag,
                size = size,
                lastModified = put.serverDate,
                mtime = mtime,
                sseKmsOrCustomer = put.sseKmsOrCustomer,
                encryptionKnown = put.encryptionKnown,
            ),
        )

    // A test seam: told of every read of a source file this provider makes itself (hash passes), not the transport's body read.
    internal var sourceReadObserver: ((Path) -> Unit)? = null

    private fun localStats(file: Path): Pair<Long, Long>? =
        try {
            Files.getLastModifiedTime(file).toMillis() to Files.size(file)
        } catch (_: java.io.IOException) {
            null
        }

    private suspend fun sha256Of(file: Path): String = digestsOf(file, md5 = false).sha256

    private class Digests(
        val sha256: String,
        val md5: ByteArray?,
        val partMd5s: List<ByteArray>,
    )

    /** The SHA-256 of [file] as hex and, when [md5] is set, its MD5; with a [partSize] above 0, also the MD5 of each part. One read. */
    private suspend fun digestsOf(
        file: Path,
        md5: Boolean,
        partSize: Long = 0L,
    ): Digests =
        withContext(Dispatchers.IO) {
            sourceReadObserver?.invoke(file)
            val sha = MessageDigest.getInstance("SHA-256")
            val md = if (md5) MessageDigest.getInstance("MD5") else null
            val partMd5s = mutableListOf<ByteArray>()
            val partMd = if (partSize > 0) MessageDigest.getInstance("MD5") else null
            var inPart = 0L
            Files.newInputStream(file).use { input ->
                val bytes = ByteArray(64 * 1024)
                while (true) {
                    // A cancelled upload must not keep hashing a multi-gigabyte file to the end (this pass runs twice per upload).
                    ensureActive()
                    val count = input.read(bytes)
                    if (count < 0) break
                    sha.update(bytes, 0, count)
                    md?.update(bytes, 0, count)
                    var at = 0
                    while (partMd != null && at < count) {
                        val take = minOf((count - at).toLong(), partSize - inPart).toInt()
                        partMd.update(bytes, at, take)
                        at += take
                        inPart += take
                        if (inPart == partSize) {
                            partMd5s.add(partMd.digest())
                            inPart = 0
                        }
                    }
                }
            }
            if (partMd != null && inPart > 0) partMd5s.add(partMd.digest())
            Digests(HexFormat.of().formatHex(sha.digest()), md?.digest(), partMd5s)
        }

    // The racy form of a conditional write, for vendors without If-Match: the object must still carry the ETag the engine saw.
    private suspend fun requireUnchanged(
        key: String,
        etag: String,
    ) {
        val current = api.head(key)
        if (current?.etag != etag) throw RemoteConflictException("'$key' changed on S3 since it was last synced")
    }

    // gkrost/unidrive-cli#57: set once the vendor refused `If-None-Match` and a HEAD (or, opted in, the listing) took its place.
    @Volatile
    private var createGuardRefused = false

    /**
     * [write] as a create that a taken [key] refuses: with `If-None-Match: *` (AWS, MinIO, R2 answer 412, a conflict). A vendor
     * that does not implement the header answers 501, or a 400 whose code names the header; then the key must be free by a HEAD
     * ([requireAbsent]), the write goes without the header, and once that worked the header is not sent again. The HEAD is the
     * racy form a guarded modify without If-Match has too: it narrows the window in which another writer's object is replaced to
     * the time between that HEAD and the PUT, and it costs one request per object.
     *
     * That HEAD is the default, and it is what keeps a create from replacing an object that is already there, whoever calls: the
     * provider's upload receives no proof that the key was absent in a listing (the sync's plan knows, a mount's direct upload
     * does not). Profile key `s3_create_from_listing = true` ([S3Config.createFromListing], gkrost/unidrive-cli#573) drops the
     * HEAD for a vendor that refused the header: the listing that made the caller plan a create is then trusted, so a create may
     * replace an object that appeared on S3 after that listing, or one that was there all along when the caller had no listing.
     * A vendor that honours the header keeps its atomic guard either way. Any other 400 (`RequestTimeout`, `IncompleteBody`, …)
     * is a real failure, and a vendor that supports conditional writes never loses its guard to a refused create.
     */
    private suspend fun <T> createGuarded(
        key: String,
        write: suspend (ifNoneMatch: Boolean) -> T,
    ): T {
        if (!createGuardRefused) {
            try {
                return write(true)
            } catch (e: S3Exception) {
                if (!createGuardRefusal(e)) throw e
                log.info(
                    "S3 refused If-None-Match on '{}' ({} {}); {}",
                    key,
                    e.status,
                    e.code,
                    if (config.createFromListing) "a create is backed by the sync's listing from now on (s3_create_from_listing)" else "checking that the key is free with a HEAD instead",
                )
            }
            if (!config.createFromListing) requireAbsent(key)
            return write(false).also { createGuardRefused = true }
        }
        if (!config.createFromListing) requireAbsent(key)
        return write(false)
    }

    // The refusal may retire the guard only when it names the header (or the missing feature) and the vendor does not
    // declare conditional writes; anything else leaves the guard standing.
    private fun createGuardRefusal(e: S3Exception): Boolean {
        if (config.vendor.conditionalWrites) return false
        if (e.status == 501) return true
        if (e.status != 400) return false
        val code = e.code.lowercase().replace("-", "").replace("_", "")
        return "ifnonematch" in code || code == "notimplemented"
    }

    private suspend fun requireAbsent(key: String) {
        if (api.head(key) != null) throw RemoteConflictException("'$key' is on S3 already")
    }

    private suspend fun uploadInParts(
        localPath: Path,
        key: String,
        sourceStats: Pair<Long, Long>, // the file's (mtime in ms, size) as the upload read them
        meta: Map<String, String>,
        partMd5s: List<ByteArray>,
        onProgress: ((Long, Long) -> Unit)?,
        complete: suspend (uploadId: String, parts: List<Pair<Int, String>>) -> Unit,
    ) {
        val (mtimeMillis, size) = sourceStats
        val partSize = partSizeFor(size)
        val (uploadId, held) = openUpload(localPath, key, size, mtimeMillis, partSize, meta)
        val partCount = ((size + partSize - 1) / partSize).toInt()
        val etags = arrayOfNulls<String>(partCount)
        // Up to [partConcurrency] parts at once, each read from disk as its body (the MD5s came from the digest pass). A failed part
        // cancels the others; the upload stays open and recorded, so the next attempt resumes with what S3 holds.
        val permits = kotlinx.coroutines.sync.Semaphore(partConcurrency)
        val progress = Any()
        var sent = 0L
        kotlinx.coroutines.coroutineScope {
            for (index in 0 until partCount) {
                launch {
                    permits.withPermit {
                        val number = index + 1
                        val offset = index * partSize
                        val length = minOf(partSize, size - offset)
                        val md5 = partMd5s[index]
                        val md5Hex = HexFormat.of().formatHex(md5)
                        etags[index] = held[number]?.takeIf { it.size == length && it.etag == md5Hex }?.etag
                            ?: api.uploadPart(key, uploadId, number, localPath, offset, length, md5)
                        // One report at a time and always growing, whichever part lands first.
                        synchronized(progress) {
                            sent += length
                            onProgress?.invoke(sent, size)
                        }
                    }
                }
            }
        }
        try {
            complete(uploadId, etags.mapIndexed { index, etag -> index + 1 to checkNotNull(etag) })
        } catch (e: RemoteConflictException) {
            // Refused for good (the key changed): nothing re-attempts this upload, so its parts would stay billed. Abort it.
            abortQuietly(key, uploadId)
            uploads.remove(key)
            throw e
        } catch (e: S3Exception) {
            if (e.status in 400..499 && e.status != 408 && e.status != 429) {
                abortQuietly(key, uploadId)
                uploads.remove(key)
            }
            throw e
        }
        uploads.remove(key)
    }

    // The upload to send parts to: the one this profile left open for the same unchanged file, or a new one.
    private suspend fun openUpload(
        localPath: Path,
        key: String,
        size: Long,
        mtimeMillis: Long,
        partSize: Long,
        meta: Map<String, String>,
    ): Pair<String, Map<Int, S3Part>> {
        val proofSha256 = meta.getValue("unidrive-sha256-v1")
        abortStaleUploads(key)
        val pending = uploads.find(key)
        if (pending != null) {
            val same = pending.localPath == localPath.toString() && pending.size == size && pending.mtimeMillis == mtimeMillis &&
                pending.partSize == partSize && pending.proofSha256 == proofSha256
            val held = if (same) api.listParts(key, pending.uploadId) else null
            if (held != null) {
                uploads.save(pending) // a resumed upload is not stale: renew its record's age
                log.info("Resuming the multipart upload of '{}' with {} part(s) already on S3", key, held.size)
                return pending.uploadId to held.associateBy { it.number }
            }
            abortQuietly(key, pending.uploadId)
            uploads.remove(key)
        }
        val uploadId = api.createMultipart(key, meta)
        uploads.save(MultipartStore.Pending(key, uploadId, localPath.toString(), size, mtimeMillis, partSize, proofSha256))
        return uploadId to emptyMap()
    }

    // The smallest part size (a whole MiB, at least the configured minimum) that fits [size] into the vendor's part limit.
    internal fun partSizeFor(size: Long): Long {
        val needed = (size + config.vendor.maxParts - 1) / config.vendor.maxParts
        val mib = 1024L * 1024
        val partSize = maxOf(config.minPartSize, (needed + mib - 1) / mib * mib)
        if (partSize > S3Config.MAX_PART_SIZE) throw ProviderException("${size / mib} MiB is more than ${config.vendor.name} can take in one object")
        return partSize
    }

    // #748 finding 5: an upload whose file is never uploaded again (deleted, renamed, the profile moved on) keeps its parts billed
    // until a lifecycle rule ends it, and nothing else ever reads its record. A record not written (or resumed) for
    // [STALE_UPLOAD_DAYS] days is aborted, once per provider instance, when a multipart upload opens; [except] is the key about to open.
    // Every other upload that opens meanwhile waits for the sweep, so it never resumes (or re-records) an upload the sweep then
    // aborts or forgets. A record is dropped only when its abort went through: it is the one handle to the parts S3 still bills.
    private val staleSweep = kotlinx.coroutines.sync.Mutex()

    @Volatile
    private var staleSwept = false

    private suspend fun abortStaleUploads(except: String) {
        if (staleSwept) return
        staleSweep.withLock {
            if (staleSwept) return
            uploads.olderThan(java.time.Instant.now().minus(java.time.Duration.ofDays(STALE_UPLOAD_DAYS))).filter { it.key != except }.forEach {
                log.info("Aborting the multipart upload of '{}' left open for more than {} days", it.key, STALE_UPLOAD_DAYS)
                if (abortQuietly(it.key, it.uploadId)) uploads.remove(it.key)
            }
            staleSwept = true
        }
    }

    /** A delete of a file that was never uploaded also drops the multipart upload it may have left open. */
    override suspend fun discardStagedUpload(localPath: String) {
        uploads.all().filter { it.localPath == localPath }.forEach {
            abortQuietly(it.key, it.uploadId)
            uploads.remove(it.key)
        }
    }

    @Suppress("TooGenericExceptionCaught") // best effort: S3 drops an abandoned upload by lifecycle rule or never bills more than its parts
    private suspend fun abortQuietly(
        key: String,
        uploadId: String,
    ): Boolean =
        try {
            api.abortMultipart(key, uploadId) // an upload already gone (404) counts as aborted
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not abort the multipart upload of '{}' ({}); its parts stay until a lifecycle rule removes them", key, e.javaClass.simpleName)
            false
        }

    override suspend fun delete(
        remotePath: String,
        ifMatchETag: String?,
    ) {
        val key = keyOf(remotePath)
        if (key.isEmpty()) throw ProviderException("Refusing to delete the whole bucket")
        val current = api.head(key)
        when {
            current != null -> {
                if (ifMatchETag != null && current.etag != ifMatchETag) {
                    throw RemoteConflictException("'$key' changed on S3 since it was last synced")
                }
                api.delete(key)
            }
            // gkrost/unidrive-cli#447: a guarded delete is a file delete, and the file is gone already, which is what a
            // delete wants (as on Dropbox and OneDrive). Keys under a same-named prefix are another tree: never touch them.
            ifMatchETag != null -> return
            // No object: a folder (every key below it goes, as a folder delete does on the other providers), or nothing.
            else -> deleteTree("$key/")
        }
    }

    // gkrost/unidrive-cli#447: the engines delete a synced item by its id, which says what it is: a folder id ends in '/'
    // (folderItem), a file id is the object's key (fileItem). A file id never takes the folder path, so a file that is gone
    // already is done instead of turning into a delete of the keys under a same-named prefix. The id decides, not
    // [remotePath] (archive#402: the id is the item the engine tracks); S3 has no rename, so the two name the same key.
    override suspend fun deleteById(
        remoteId: String,
        remotePath: String,
    ) {
        val key = remoteId.trimEnd('/')
        if (key.isEmpty()) throw ProviderException("Refusing to delete the whole bucket")
        if (remoteId.endsWith('/')) {
            deleteTree("$key/")
        } else {
            api.delete(key)
        }
    }

    // archive#1178: S3 has no trash; delete already deletes the object (or the tree below a folder key) for good.
    override suspend fun deletePermanently(remotePath: String) {
        delete(remotePath, null)
    }

    // The default goes by the path (delete without a guard), which would take the folder path for a gone file: go by the id.
    override suspend fun deletePermanentlyById(
        remoteId: String,
        remotePath: String,
    ) = deleteById(remoteId, remotePath)

    private suspend fun deleteTree(prefix: String) = deleteKeys(allKeys(prefix), prefix)

    // Every key of [keys] (all under [prefix]) or an exception: a delete that leaves some behind is not a success.
    private suspend fun deleteKeys(
        keys: List<String>,
        prefix: String,
    ) {
        var failed = 0
        for (batch in keys.chunked(S3Api.MAX_BATCH_DELETE)) {
            failed +=
                try {
                    api.deleteMany(batch).size
                } catch (e: S3Exception) {
                    // A vendor without DeleteObjects: one call per key.
                    log.info("Batch delete refused ({}); deleting {} key(s) one by one", e.code, batch.size)
                    batch.forEach { api.delete(it) }
                    0
                }
        }
        if (failed > 0) throw ProviderException("S3 could not delete $failed of ${keys.size} object(s) under '$prefix'")
    }

    private suspend fun allKeys(prefix: String): List<String> = allObjects(prefix).map { it.key }

    /** Every object under [prefix] as the listing reports it: key, size and ETag, no HEAD per key. */
    private suspend fun allObjects(prefix: String): List<S3Object> {
        val objects = mutableListOf<S3Object>()
        val pages = pageGuard("The S3 listing under '$prefix'")
        val skipped = DotSegmentSkips()
        var token: String? = null
        do {
            val page = api.list(prefix, token = token)
            objects += page.objects.filter { skipped.admits(it.key) }
            pages.follow(page.nextToken)
            token = page.nextToken
        } while (token != null)
        skipped.summarise(log)
        return objects
    }

    /** Every key under [prefix] with its ETag: what a move's destination already holds (#176). */
    private suspend fun keysWithEtags(prefix: String): Map<String, String?> {
        val keys = linkedMapOf<String, String?>()
        val pages = pageGuard("The S3 listing under '$prefix'")
        var token: String? = null
        do {
            val page = api.list(prefix, token = token)
            page.objects.forEach { keys[it.key] = it.etag }
            pages.follow(page.nextToken)
            token = page.nextToken
        } while (token != null)
        return keys
    }

    override suspend fun createFolder(path: String): CloudItem {
        val key = keyOf(path)
        if (key.isEmpty()) return folderItem("/")
        api.put("$key/", null)
        return folderItem(pathOf(key))
    }

    /**
     * Copy, then delete the source: S3 has no rename. A folder moves key by key. No key is copied over bytes it
     * would replace (#176): [refuseTakenFileDestination] and [refuseTakenFolderDestination] run before the first
     * copy and refuse a taken key with [RemoteConflictException], as every other provider refuses a taken
     * destination.
     */
    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem {
        val from = keyOf(fromPath)
        val to = keyOf(toPath)
        val source = api.head(from)
        if (source != null) {
            refuseTakenFileDestination(source, to)
            copyObject(source, to)
            api.delete(from)
            return fileItem(api.head(to) ?: throw ProviderException("S3 does not show '$to' right after the copy"))
        }
        // The listing carries what the guard and a single copy need (key, size, ETag): no HEAD per key. Only an object
        // above the single-copy limit is HEADed, for the mtime its multipart copy writes again.
        val listed = allObjects("$from/")
        if (listed.isEmpty()) throw S3Exception("S3 has nothing at '$fromPath'", 404, "NoSuchKey")
        val keys = listed.map { it.key }
        val sources = listed.map { it.key to it }
        refuseTakenFolderDestination(sources, from, to)
        copyAll(sources.map { (key, obj) -> obj to "$to/" + key.removePrefix("$from/") })
        // The source goes the way a folder delete goes: a key the store refuses to delete, or a vendor without
        // DeleteObjects, must not leave the source standing beside its copy under a success (archive#1063).
        deleteKeys(keys, "$from/")
        return folderItem(pathOf(to))
    }

    // #176: a move refuses a key it would write when the key holds other bytes — Dropbox answers path/conflict,
    // a Graph rename refuses nameAlreadyExists, and this one copied right over them. The check is per key the move
    // writes, never over the keys it does not: a folder move beside foreign keys merges, as it always has, and a
    // key holding exactly the source's bytes — the ETag a CopyObject leaves behind — is an interrupted attempt's
    // leftover the copy may go over again. That leftover is what makes per-key the only safe scope: on the retry of
    // a partly failed folder move the deleted source keys are gone, so a leftover there is indistinguishable from a
    // foreign key, and refusing those would break the convergence archive#1063 pins. ETags that cannot be compared
    // hold other bytes, the safe side.

    private suspend fun refuseTakenFileDestination(
        source: S3Object,
        to: String,
    ) {
        val there = api.head(to)
        if (there != null && !sameBytes(source.etag, there.etag)) {
            throw RemoteConflictException("'$to' is on S3 already; the move would replace its bytes")
        }
    }

    // One listing of the destination decides every target key in a single pass and carries the ETags the leftover
    // comparison needs; one HEAD per key would be a call per source key for the same answer.
    private suspend fun refuseTakenFolderDestination(
        sources: List<Pair<String, S3Object>>,
        from: String,
        to: String,
    ) {
        val there = keysWithEtags("$to/")
        for ((key, obj) in sources) {
            val target = "$to/" + key.removePrefix("$from/")
            // containsKey, not a null-safe get: an object without an ETag is present, and its bytes are
            // unknown — the same side the file-move guard's sameBytes lands on, never a silent overwrite.
            if (!there.containsKey(target)) continue
            if (!sameBytes(obj.etag, there[target])) {
                throw RemoteConflictException("'$target' is on S3 already; the move would replace its bytes")
            }
        }
    }

    // Equal only when both ETags are known: an unknown one cannot prove a leftover and holds other bytes, the safe side.
    private fun sameBytes(
        left: String?,
        right: String?,
    ): Boolean = left != null && right != null && left == right

    /**
     * The copies of a folder move, [MOVE_COPY_CONCURRENCY] at a time (the shared budget still paces every request). A
     * source key gone since the listing is skipped, as a HEAD that found nothing used to drop it. The first other
     * failure cancels the copies still running and is thrown; the source is untouched then, as before.
     */
    private suspend fun copyAll(copies: List<Pair<S3Object, String>>) {
        val permits = kotlinx.coroutines.sync.Semaphore(MOVE_COPY_CONCURRENCY)
        kotlinx.coroutines.coroutineScope {
            for ((listed, target) in copies) {
                launch {
                    permits.withPermit {
                        val source = if (listed.size > S3Config.MAX_SINGLE_COPY) api.head(listed.key) ?: return@withPermit else listed
                        try {
                            copyObject(source, target)
                        } catch (e: S3Exception) {
                            if (e.status != 404 || e.code != "NoSuchKey") throw e
                            log.info("'{}' is gone since the listing; the move copies the rest", listed.key)
                        }
                    }
                }
            }
        }
    }

    // One CopyObject up to 5 GiB; above, a multipart upload whose parts are copied server-side (every vendor, not only AWS).
    @Suppress("TooGenericExceptionCaught") // the half-made copy is aborted, then the failure is rethrown unchanged
    private suspend fun copyObject(
        source: S3Object,
        to: String,
    ) {
        if (source.size <= S3Config.MAX_SINGLE_COPY) {
            api.copy(source.key, to)
            return
        }
        val partSize = maxOf(partSizeFor(source.size), COPY_PART_SIZE)
        val meta = source.mtime?.let { mapOf("mtime" to S3Api.epochText(it)) } ?: emptyMap()
        val uploadId = api.createMultipart(to, meta)
        try {
            val parts = mutableListOf<Pair<Int, String>>()
            var offset = 0L
            var number = 1
            while (offset < source.size) {
                val end = minOf(offset + partSize, source.size) - 1
                parts += number to api.uploadPartCopy(to, uploadId, number, source.key, offset..end)
                offset = end + 1
                number++
            }
            api.completeMultipart(to, uploadId, parts)
        } catch (e: Exception) {
            abortQuietly(to, uploadId)
            throw e
        }
    }

    // ── sharing ──

    /** A presigned GET. S3 caps it at seven days and cannot put a password on it. */
    override suspend fun share(
        path: String,
        expiryHours: Int,
        password: String?,
    ): CapabilityResult<String> {
        if (password != null) throw ProviderException("An S3 link cannot carry a password")
        val seconds = if (expiryHours <= 0) MAX_PRESIGN_SECONDS else minOf(expiryHours * 3600L, MAX_PRESIGN_SECONDS)
        return CapabilityResult.Success(api.presign(keyOf(path), seconds))
    }

    // ── mapping ──

    private fun fileItem(obj: S3Object): CloudItem =
        CloudItem(
            id = obj.key,
            name = nameOf(obj.key),
            path = pathOf(obj.key),
            size = obj.size,
            isFolder = false,
            // The listing's time: a listing carries no user metadata, and the engine compares the time it recorded.
            modified = obj.lastModified,
            created = null,
            // Listings do not say whether the object uses KMS or SSE-C; their ETags are opaque write tokens.
            // A HEAD can identify a plain single-part MD5, which can additionally verify the content.
            hash = obj.etag?.takeIf { obj.encryptionKnown && !obj.sseKmsOrCustomer && it.matches(MD5_ETAG) },
            mimeType = null,
            version = obj.etag,
        )

    private fun folderItem(path: String): CloudItem {
        val key = keyOf(path)
        return CloudItem(
            id = if (key.isEmpty()) "" else "$key/",
            name = nameOf(key),
            path = if (key.isEmpty()) "/" else "/$key",
            size = 0,
            isFolder = true,
            modified = null,
            created = null,
            hash = null,
            mimeType = null,
        )
    }

    companion object {
        private const val FULL_LISTING = "full-listing"
        private const val FOLDER_PROBE_KEYS = 100
        private const val STALE_UPLOAD_DAYS = 14L

        // Parts of one upload in flight at once unless the daemon says otherwise; each streams from disk, none is buffered.
        const val DEFAULT_PART_CONCURRENCY = 4
        private const val MAX_PRESIGN_SECONDS = 7L * 24 * 3600
        private const val COPY_PART_SIZE = 512L * 1024 * 1024
        private const val MOVE_COPY_CONCURRENCY = 8
        private val MD5_ETAG = Regex("[0-9a-fA-F]{32}")
        private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")
        private const val SHA256_PROOF = "s3_sha256_metadata"

        // Exactly one leading slash comes off: an object whose key starts with one (another tool's `/a.txt`, listed
        // at `//a.txt`) must be addressed at that same key, not at the object without it (#82 E). Trailing slashes
        // are folder addressing and never part of a file key.
        fun keyOf(path: String): String = path.removePrefix("/").trimEnd('/')

        private fun prefixOf(path: String): String = keyOf(path).let { if (it.isEmpty()) "" else "$it/" }

        fun pathOf(key: String): String = "/" + key.trimEnd('/')

        private fun nameOf(key: String): String = key.trimEnd('/').substringAfterLast('/')
    }
}

// The paging loops of the listing, the delta and the folder deletes (#82 F); see PageGuard. Every loop follows the
// token of a continuation only (page one is fetched first, without one), so the cap admits 10001 requests, not 10000.
private fun pageGuard(what: String) =
    PageGuard(
        "$what answered a page token it was already asked for; the listing stopped incomplete",
        "$what was still paging after 10000 pages; the listing stopped incomplete",
    )

/**
 * Keys with a `.` or `..` segment cannot be addressed (see [S3Keys]), so a listing leaves them out. Each is named in a WARN,
 * the first [LOGGED_KEYS] of a listing; the rest are only counted, and [summarise] says how many.
 */
private class DotSegmentSkips {
    private var skipped = 0

    fun admits(key: String): Boolean {
        if (!S3Keys.hasDotSegment(key)) return true
        if (++skipped <= LOGGED_KEYS) LOG.warn("Leaving out S3 key '{}': it has a '.' or '..' path segment and cannot be addressed", key)
        return false
    }

    fun summarise(log: org.slf4j.Logger) {
        if (skipped > LOGGED_KEYS) log.warn("Left out {} S3 key(s) with a '.' or '..' path segment; the first {} are named above", skipped, LOGGED_KEYS)
    }

    private companion object {
        const val LOGGED_KEYS = 20
        val LOG: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(S3Provider::class.java)
    }
}
