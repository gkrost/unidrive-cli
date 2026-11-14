package org.krost.unidrive.xtra

import org.krost.unidrive.Capability
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.ContentProof
import org.krost.unidrive.DownloadReceipt
import org.krost.unidrive.ProviderException
import org.krost.unidrive.RemoteContentRefusedDownloadException
import org.krost.unidrive.io.OwnerOnly
import org.krost.unidrive.io.stagingSiblingName
import org.krost.unidrive.io.withLocalIoRetry
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import java.util.UUID
import javax.crypto.AEADBadTagException

/**
 * The content half of the X-tra layer (client-side encryption of a profile's file content, config key
 * `xtra_encryption`): a [CloudProvider] decorator that encrypts every file on its way to the inner provider and
 * decrypts it on the way back, so the inner provider — and through it the cloud — sees only opaque ciphertext
 * with a self-describing [XtraHeader]. Paths, names and the tree stay as they are; the sizes a listing carries
 * are the plaintext's, answered by [XtraPlaintextListings], which the CLI wires below this decorator.
 *
 * The download receipts answer about the plaintext that lands in the destination: the byte count, the staged
 * stats and the digest the engines compare against the placed copy (the streamed-hash guard), and the target
 * mtime the decryption of a fresh file would otherwise lose. The inner provider's identity fields ride along
 * unchanged — they name the ciphertext object the row stands for, and the listings name it too.
 *
 * The hash answers cross this boundary as "cannot tell", deliberately: the provider's content hash names the
 * ciphertext, while the bytes the engines see are the plaintext, so [hashAlgorithm] is null (the engines skip
 * their content-hash verification; the AEAD tag of the seal is the integrity check, and a tampered stream fails
 * its decrypt), [contentMatchesLocal] yields no proof, and [reportsContentIds] is false (the row's content id
 * would name bytes the client does not hold).
 *
 * [keys] is lazy on purpose: the wrap happens for every provider of an opted-in profile, including commands that
 * never touch content (auth, status, quota, listings), and only those that upload or download may demand the
 * passphrase. A file without the XTRA header fails its download instead of passing plaintext through silently: a
 * download that answers what the caller believes encrypted, but is not, is the one outcome this layer must never
 * produce.
 *
 * Both directions stage through a temp file, so a transfer peaks at twice the file's size; streaming without the
 * temp file needs the encrypted size upfront and stays out (see the design note in the module docs).
 */
class XtraEncryptedProvider(
    private val inner: CloudProvider,
    private val keys: Lazy<XtraKeyManager>,
    private val strategy: XtraEncryptionStrategy,
) : CloudProvider by inner {
    // The provider's content hash is the ciphertext's (the strategy seals with a per-file key and an AEAD tag of
    // its own), so the engines' hash answers below say "cannot tell" for the plaintext side of this boundary.
    override fun hashAlgorithm() = null

    override suspend fun contentMatchesLocal(
        remoteId: String,
        remotePath: String,
        local: Path,
    ): ContentProof? = null

    override val reportsContentIds: Boolean get() = false

    // A share link would hand out the stored object, which is the ciphertext: unreadable to whoever follows it
    // (and a metadata leak of the file it names), so the layer refuses instead of creating one. Links that exist
    // already stay listable and revocable, which the delegation below answers.
    override suspend fun share(
        path: String,
        expiryHours: Int,
        password: String?,
    ): CapabilityResult<String> =
        CapabilityResult.Unsupported(
            Capability.Share,
            "X-tra encrypts this profile's content on the client — a share link would serve ciphertext its readers cannot open",
        )

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        ifMatchETag: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        val km = keys.value
        val fileKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(strategy.ivLength).also { SecureRandom().nextBytes(it) }
        val wrappedKey = km.wrapKey(fileKey)

        val tmp = Files.createTempFile("xtra-", ".tmp")
        try {
            FileOutputStream(tmp.toFile()).use { out ->
                XtraHeader.write(out, strategy.algorithmId, wrappedKey, iv)
                FileInputStream(localPath.toFile()).use { input ->
                    strategy.encrypt(input, out, fileKey, iv)
                }
            }
            return inner.upload(tmp, remotePath, existingRemoteId, ifMatchETag, onProgress).plaintextSized(strategy)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long {
        val tmp = Files.createTempFile("xtra-", ".tmp")
        try {
            inner.download(remotePath, tmp)
            decrypt(strategy, keys, tmp, destination)
            return Files.size(destination)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    override suspend fun downloadById(
        remoteId: String,
        remotePath: String,
        destination: Path,
    ): Long {
        val tmp = Files.createTempFile("xtra-", ".tmp")
        try {
            inner.downloadById(remoteId, remotePath, tmp)
            decrypt(strategy, keys, tmp, destination)
            return Files.size(destination)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /**
     * The hash variants answer about the plaintext that lands in [destination]: the receipt keeps the inner
     * provider's identity fields (they name the ciphertext object the row stands for) and replaces the byte count,
     * the staged stats and the digest with what the decryption wrote — including the target mtime, which the
     * decryption of a fresh file would otherwise lose.
     */
    override suspend fun downloadWithHash(
        remotePath: String,
        destination: Path,
        targetMtimeMillis: Long?,
    ): DownloadReceipt {
        val tmp = Files.createTempFile("xtra-", ".tmp")
        try {
            val receipt = inner.downloadWithHash(remotePath, tmp, targetMtimeMillis)
            return decryptToDestination(strategy, keys, tmp, destination, receipt, targetMtimeMillis)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    override suspend fun downloadByIdWithHash(
        remoteId: String,
        remotePath: String,
        destination: Path,
        targetMtimeMillis: Long?,
    ): DownloadReceipt {
        val tmp = Files.createTempFile("xtra-", ".tmp")
        try {
            val receipt = inner.downloadByIdWithHash(remoteId, remotePath, tmp, targetMtimeMillis)
            return decryptToDestination(strategy, keys, tmp, destination, receipt, targetMtimeMillis)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}

// The helpers of [XtraEncryptedProvider], file-level so the class stays under detekt's function count. They take
// the class's private strategy and lazy keys as parameters.

private suspend fun decryptToDestination(
    strategy: XtraEncryptionStrategy,
    keys: Lazy<XtraKeyManager>,
    encryptedFile: Path,
    destination: Path,
    receipt: DownloadReceipt,
    targetMtimeMillis: Long?,
): DownloadReceipt {
    decrypt(strategy, keys, encryptedFile, destination)
    // The mtime the inner provider applied to its staged ciphertext does not survive the decryption (the
    // plaintext lands in a fresh file), so the wrapper applies the target itself — what the hash variants
    // answer for (the mirror restores the mtime itself only when the receipt says it was not applied).
    if (targetMtimeMillis != null) Files.setLastModifiedTime(destination, FileTime.fromMillis(targetMtimeMillis))
    val size = Files.size(destination)
    // The staged stats the engines compare the placed copy against (the streamed-hash guard, withLocalHash)
    // describe the decrypted file now, not the ciphertext the inner provider staged.
    return receipt.copy(
        bytesWritten = size,
        plaintextSha256 = sha256Of(destination),
        stagedSize = size,
        stagedMtimeMillis = Files.getLastModifiedTime(destination).toMillis(),
        mtimeAppliedBeforePlacement = true,
    )
}

private suspend fun decrypt(
    strategy: XtraEncryptionStrategy,
    keys: Lazy<XtraKeyManager>,
    encryptedFile: Path,
    destination: Path,
) {
    val km = keys.value
    FileInputStream(encryptedFile.toFile()).use { input ->
        when (val result = XtraHeader.read(input)) {
            is XtraHeader.ReadResult.NotEncrypted ->
                throw ProviderException("File is not XTRA-encrypted: $encryptedFile")
            is XtraHeader.ReadResult.Corrupted ->
                throw ProviderException("XTRA header corrupted: ${result.reason}")
            is XtraHeader.ReadResult.Valid ->
                decryptStaged(strategy, input, km.unwrapKey(result.wrappedKey), result.iv, destination, encryptedFile)
        }
    }
}

// The decryption never writes the destination itself: a segment is written once its tag passes, so a stream that fails
// in a later segment would leave the earlier plaintext in place of the file the user had (#722). It goes into a
// sibling staging file (the same directory, so the final move is one atomic step on one volume), reaches the disk, and
// replaces the destination only when the whole stream authenticated. Whatever fails, the staging file goes and the
// destination stays as it was.
//
// The staging file is owner-only from the first byte on (a decrypted file is the plaintext the layer exists to keep
// from the cloud; a fresh file with the umask's defaults can be readable by others for as long as the decrypt runs).
// When the destination already exists, its permissions are put on the staging file before the move, so the replacement
// keeps the permissions the previous file had instead of the staging file's.
@Suppress("TooGenericExceptionCaught") // whatever fails (an Error too), the staging file goes and the failure is rethrown
private suspend fun decryptStaged(
    strategy: XtraEncryptionStrategy,
    input: InputStream,
    fileKey: ByteArray,
    iv: ByteArray,
    destination: Path,
    encryptedFile: Path,
) {
    val absolute = destination.toAbsolutePath()
    Files.createDirectories(absolute.parent)
    val staging = absolute.resolveSibling(stagingSiblingName(absolute.fileName.toString(), ".${UUID.randomUUID()}.unidrive-tmp"))
    try {
        createOwnerOnlyStagingFile(staging)
        FileOutputStream(staging.toFile()).use { out ->
            strategy.decrypt(input, out, fileKey, iv)
            out.fd.sync()
        }
        takePreviousPermissions(absolute, staging)
        withLocalIoRetry { Files.move(staging, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
    } catch (e: AEADBadTagException) {
        // The seal does not authenticate: truncated, spliced or tampered ciphertext. Another attempt reads the same
        // bytes, so it is a permanent refusal (quarantined by the engines, `content_refused` in hydration), neither a
        // retryable failure nor a local edit.
        dropStaging(staging, e)
        throw RemoteContentRefusedDownloadException("X-tra content failed its integrity check: $encryptedFile", "authentication failed", e)
    } catch (e: Throwable) {
        dropStaging(staging, e)
        throw e
    }
}

// The empty staging file, owner-only before anything is written: POSIX file systems create it with rw------- in one
// step (no window with wider modes), everywhere else it is created plain and restricted after (Windows: the inherited
// ACL becomes an owner-only one, as OwnerOnlyAtomicWrite does for the engine's own files). A restriction that a file
// system without modes or ACLs cannot make is not worth failing a download over — the file then keeps the old,
// un-restricted behavior.
private fun createOwnerOnlyStagingFile(staging: Path) {
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
        Files.createFile(staging, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    } else {
        Files.createFile(staging)
        runCatching { OwnerOnly.restrictFile(staging) }
    }
}

// Best effort: the replacement takes the permissions of the file it replaces. When they cannot be read (no POSIX view,
// an unreadable destination), the staging file stays owner-only — narrower, never wider than before. Failure to
// preserve must not fail a decrypt whose bytes are already on disk.
private fun takePreviousPermissions(
    destination: Path,
    staging: Path,
) {
    if (Files.getFileAttributeView(staging, PosixFileAttributeView::class.java) == null) return
    runCatching {
        if (Files.exists(destination)) {
            Files.setPosixFilePermissions(staging, Files.getPosixFilePermissions(destination))
        }
    }
}

// A cleanup that fails must not replace the failure that matters.
private fun dropStaging(
    staging: Path,
    failure: Throwable,
) {
    runCatching { Files.deleteIfExists(staging) }.exceptionOrNull()?.let(failure::addSuppressed)
}

private fun sha256Of(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file.toFile()).use { input ->
        val buf = ByteArray(65536)
        var n: Int
        while (input.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
    }
    return HexFormat.of().formatHex(digest.digest())
}
