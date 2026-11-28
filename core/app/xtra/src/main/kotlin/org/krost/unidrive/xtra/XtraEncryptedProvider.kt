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
import java.security.DigestInputStream
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
 * would name bytes the client does not hold). The answer of an upload does name the plaintext's SHA-256 ([CloudItem.sentSha256]),
 * the digest the engines record as sent; the AEAD binds only the header, so a download of that version is compared with it.
 *
 * The key material is the [strategy]'s, lazy on purpose: the wrap happens for every provider of an opted-in
 * profile, including commands that never touch content (auth, status, quota, listings), and only those that
 * upload or download may demand the passphrase — the strategy's key store alone, so an algorithm whose format
 * carries no key through the header (age-v1) never touches the RSA pair. A file without the XTRA header fails
 * its download instead of passing plaintext through silently, and refuses before any key demand: a download
 * that answers what the caller believes encrypted, but is not, is the one outcome this layer must never produce.
 * A file whose header names another algorithm than [strategy]'s is refused the same way, before any key demand, with
 * [XtraAlgorithmMismatchException] — a retryable failure, not a quarantining content refusal, so the file comes back
 * on its own once the profile names its algorithm again (see that class for the engines' side). The first refusal of
 * an object downloads it (the header decides); [XtraAlgorithmMismatchMemory] then answers every later download of the
 * same object version with the same exception and transfers nothing (one metadata read tells the version).
 *
 * Both directions stage through a temp file, so a transfer peaks at twice the file's size; streaming without the
 * temp file needs the encrypted size upfront and stays out (see the design note in the module docs).
 */
class XtraEncryptedProvider(
    private val inner: CloudProvider,
    private val strategy: XtraEncryptionStrategy,
) : CloudProvider by inner {
    // One daemon run's refusals of other-algorithm files: the first refusal downloads, the rest fail fast.
    private val mismatchMemory = XtraAlgorithmMismatchMemory(strategy.algorithmId)
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
        val fileKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(strategy.ivLength).also { SecureRandom().nextBytes(it) }
        val wrappedKey = strategy.wrapFileKey(fileKey)
        val header = XtraHeader.serialize(strategy.algorithmId, wrappedKey, iv)
        // What this upload replaces may carry a remembered mismatch refusal of this run: the new bytes answer it.
        mismatchMemory.forget(existingRemoteId, remotePath)

        val tmp = Files.createTempFile("xtra-", ".tmp")
        try {
            // gkrost/unidrive-cli#1018 item 16: the encrypt pass is the one read of the plaintext, so it is hashed here. An inner provider
            // that hashes what its upload reads (Internxt) hashes the ciphertext, and the engines take the answer's digest as what was
            // sent: the plaintext's, which a later download of this version is compared with.
            val plaintext = MessageDigest.getInstance("SHA-256")
            FileOutputStream(tmp.toFile()).use { out ->
                out.write(header)
                DigestInputStream(FileInputStream(localPath.toFile()), plaintext).use { input ->
                    strategy.encrypt(input, out, fileKey, iv, header)
                }
            }
            val answer = inner.upload(tmp, remotePath, existingRemoteId, ifMatchETag, onProgress).plaintextSized(strategy)
            return answer.copy(sentSha256 = HexFormat.of().formatHex(plaintext.digest()))
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long = mismatchMemory.rememberingRefusal(null, remotePath, { inner.getMetadata(remotePath) }) {
        val tmp = Files.createTempFile("xtra-", ".tmp")
        try {
            inner.download(remotePath, tmp)
            decrypt(strategy, tmp, destination)
            Files.size(destination)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    override suspend fun downloadById(
        remoteId: String,
        remotePath: String,
        destination: Path,
    ): Long = mismatchMemory.rememberingRefusal(remoteId, remotePath, { inner.getMetadata(remotePath) }) {
        val tmp = Files.createTempFile("xtra-", ".tmp")
        try {
            inner.downloadById(remoteId, remotePath, tmp)
            decrypt(strategy, tmp, destination)
            Files.size(destination)
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
    ): DownloadReceipt = mismatchMemory.rememberingRefusal(null, remotePath, { inner.getMetadata(remotePath) }) {
        val tmp = Files.createTempFile("xtra-", ".tmp")
        try {
            val receipt = inner.downloadWithHash(remotePath, tmp, targetMtimeMillis)
            decryptToDestination(strategy, tmp, destination, receipt, targetMtimeMillis)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    override suspend fun downloadByIdWithHash(
        remoteId: String,
        remotePath: String,
        destination: Path,
        targetMtimeMillis: Long?,
    ): DownloadReceipt = mismatchMemory.rememberingRefusal(remoteId, remotePath, { inner.getMetadata(remotePath) }) {
        val tmp = Files.createTempFile("xtra-", ".tmp")
        try {
            val receipt = inner.downloadByIdWithHash(remoteId, remotePath, tmp, targetMtimeMillis)
            decryptToDestination(strategy, tmp, destination, receipt, targetMtimeMillis)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}

// The helpers of [XtraEncryptedProvider], file-level so the class stays under detekt's function count. They take
// the class's private strategy as a parameter.

private suspend fun decryptToDestination(
    strategy: XtraEncryptionStrategy,
    encryptedFile: Path,
    destination: Path,
    receipt: DownloadReceipt,
    targetMtimeMillis: Long?,
): DownloadReceipt {
    decrypt(strategy, encryptedFile, destination)
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
    encryptedFile: Path,
    destination: Path,
) {
    FileInputStream(encryptedFile.toFile()).use { input ->
        val header = validHeaderFor(strategy, XtraHeader.read(input), encryptedFile.toString())
        decryptStaged(strategy, input, unwrapOrRefuse(strategy, header, encryptedFile), header, destination, encryptedFile)
    }
}

/**
 * The header checks every read path shares — the whole-file download here, [AgeV1Strategy.rangedReader] for
 * ranged reads — so the same stored file is answered the same way whichever path reads it. Run before the
 * configured strategy demands a key (its unwrap or identity load is what asks for the passphrase) or reads a body
 * it did not write. Damaged header bytes are refused like a failed seal: another attempt reads the same bytes. Two
 * bytes cannot be: the magic (a file without it is not this layer's) and the algorithm id (a changed one is
 * indistinguishable from a file of the other algorithm, which must come back after a switch back).
 */
internal fun validHeaderFor(
    strategy: XtraEncryptionStrategy,
    result: XtraHeader.ReadResult,
    name: String,
): XtraHeader.ReadResult.Valid =
    when (result) {
        is XtraHeader.ReadResult.NotEncrypted -> throw ProviderException("File is not XTRA-encrypted: $name")
        is XtraHeader.ReadResult.Corrupted ->
            throw RemoteContentRefusedDownloadException("XTRA header corrupted: ${result.reason}: $name", "header corrupted")
        is XtraHeader.ReadResult.Valid -> result.also {
            refuseOtherAlgorithm(strategy, it, name)
            refuseMalformed(strategy, it, name)
        }
    }

// Length prefixes the strategy's layout does not have are a damaged header, refused like a failed seal.
private fun refuseMalformed(
    strategy: XtraEncryptionStrategy,
    header: XtraHeader.ReadResult.Valid,
    name: String,
) {
    if (header.wrappedKey.size == strategy.wrappedKeyLength && header.iv.size == strategy.ivLength) return
    throw RemoteContentRefusedDownloadException(
        "XTRA header corrupted: wrapped key ${header.wrappedKey.size} / IV ${header.iv.size} bytes, the algorithm " +
            "writes ${strategy.wrappedKeyLength} / ${strategy.ivLength}: $name",
        "header corrupted",
    )
}

// A slot the strategy cannot unwrap (XtraFileKeyRefusedException) is refused like a failed seal; a key store that
// does not load (wrong passphrase, missing file) throws something else and stays this installation's error. Every
// strategy loads its key store here, before [decryptStaged]: a key file's failed GCM tag is an AEADBadTagException too,
// which the decrypt's catch would refuse as content (#1037).
private fun unwrapOrRefuse(
    strategy: XtraEncryptionStrategy,
    header: XtraHeader.ReadResult.Valid,
    encryptedFile: Path,
): ByteArray =
    try {
        strategy.unwrapFileKey(header.wrappedKey)
    } catch (e: XtraFileKeyRefusedException) {
        throw RemoteContentRefusedDownloadException("X-tra file key does not unwrap: $encryptedFile", "authentication failed", e)
    }

// The header names the algorithm that must decrypt the file; any other is refused. Not a content refusal: see
// XtraAlgorithmMismatchException for why the engines must retry it rather than quarantine it.
private fun refuseOtherAlgorithm(
    strategy: XtraEncryptionStrategy,
    header: XtraHeader.ReadResult.Valid,
    name: String,
) {
    if (header.algorithmId == strategy.algorithmId) return
    throw mismatchRefusal(header.algorithmId, strategy.algorithmId, name)
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
    header: XtraHeader.ReadResult.Valid,
    destination: Path,
    encryptedFile: Path,
) {
    val absolute = destination.toAbsolutePath()
    Files.createDirectories(absolute.parent)
    val staging = absolute.resolveSibling(stagingSiblingName(absolute.fileName.toString(), ".${UUID.randomUUID()}.unidrive-tmp"))
    try {
        createOwnerOnlyStagingFile(staging)
        FileOutputStream(staging.toFile()).use { out ->
            strategy.decrypt(input, out, fileKey, header.iv, header.bytes)
            out.fd.sync()
        }
        takePreviousPermissions(absolute, staging)
        withLocalIoRetry { Files.move(staging, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
    } catch (e: AEADBadTagException) {
        // The seal does not authenticate: truncated, spliced or tampered ciphertext, or a header changed after the
        // write (the strategies that can bind it as associated data do). Another attempt reads the same
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
