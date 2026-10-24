package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ContentProof
import org.krost.unidrive.internxt.model.BridgeFileInfo
import org.krost.unidrive.internxt.model.BridgeShard
import org.krost.unidrive.internxt.model.InternxtFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * gkrost/unidrive#1219: the proof that a local file is the stored file by the ciphertext hash the network recorded, with injected lookups
 * (no HTTP, no account). The expected ciphertext and its hashes are made here with `javax.crypto` directly, not with the code under test,
 * so a wrong cipher mode, a wrong IV slice or a wrong hash shape in the production code cannot agree with itself.
 */
class InternxtLocalContentProofTest {
    private val bucket = "bucket-1"
    private val fileId = "bridge-1"
    private val fileKey = ByteArray(32) { (it * 7 + 3).toByte() }
    private val index = ByteArray(32) { (it * 5 + 1).toByte() }
    private val iv = index.copyOfRange(0, 16)
    private val crypto = InternxtCrypto()

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** What an independent AES-256-CTR encryption of [plain] gives, hashed the way the network records it. */
    private fun recorded(
        plain: ByteArray,
        shape: String,
    ): String {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, "AES"), IvParameterSpec(iv))
        val sha = sha256(cipher.doFinal(plain))
        return if (shape == "ripemd") hex(Ripemd160.digest(sha)) else hex(sha)
    }

    private class Calls {
        var meta = 0
        var bridge = 0
        var keys = 0
    }

    private fun proof(
        plain: ByteArray,
        shape: String = "ripemd",
        calls: Calls = Calls(),
        meta: InternxtFile = InternxtFile(uuid = "u1", fileId = fileId, bucket = bucket, size = plain.size.toString(), encryptVersion = "03-aes"),
        info: BridgeFileInfo =
            BridgeFileInfo(
                bucket = bucket,
                id = fileId,
                index = hex(index),
                shards = listOf(BridgeShard(index = 0, hash = recorded(plain, shape), url = "https://example.invalid/s")),
            ),
    ) = InternxtLocalContentProof(
        getFileMeta = {
            calls.meta++
            meta
        },
        getBridgeFileInfo = { _, _ ->
            calls.bridge++
            info
        },
        fileKeyFor = { _, _ ->
            calls.keys++
            fileKey
        },
        crypto = crypto,
    )

    private fun localFile(bytes: ByteArray): Path = Files.createTempFile("internxt-proof", ".bin").also { Files.write(it, bytes) }

    private val plain = "the plaintext of a file of the account, long enough to be more than one block of sixteen bytes".toByteArray()

    @Test
    fun `the same bytes are proven by the 40-hex hash every official client records`() =
        runTest {
            assertEquals(ContentProof(true, "internxt-shard-hash"), proof(plain, "ripemd").prove("u1", localFile(plain)))
        }

    @Test
    fun `the same bytes are proven by the 64-hex hash this engine recorded before it adopted the official shape`() =
        runTest {
            assertEquals(ContentProof(true, "internxt-shard-hash"), proof(plain, "sha256").prove("u1", localFile(plain)))
        }

    @Test
    fun `one changed byte of the same size is proven different`() =
        runTest {
            val other = plain.copyOf().also { it[10] = (it[10] + 1).toByte() }

            assertEquals(ContentProof(false, "internxt-shard-hash"), proof(plain).prove("u1", localFile(other)))
        }

    @Test
    fun `a file of several chunks and a size that is no multiple of a block is streamed and proven`() =
        runTest {
            val big = ByteArray(3 * 256 * 1024 + 12_345) { (it * 31 + it / 7).toByte() }

            assertEquals(ContentProof(true, "internxt-shard-hash"), proof(big).prove("u1", localFile(big)))
        }

    @Test
    fun `another size than the drive declares is different at once and asks neither the bridge nor the key`() =
        runTest {
            val calls = Calls()

            val answer = proof(plain, calls = calls).prove("u1", localFile(plain + 1.toByte()))

            assertEquals(ContentProof(false, "size"), answer)
            assertEquals(0, calls.bridge)
            assertEquals(0, calls.keys)
        }

    @Test
    fun `an object stored in several shards is not judged`() =
        runTest {
            val two = BridgeShard(0, recorded(plain, "ripemd"), "https://example.invalid/a")
            val info = BridgeFileInfo(bucket, id = fileId, index = hex(index), shards = listOf(two, two.copy(index = 1)))

            assertNull(proof(plain, info = info).prove("u1", localFile(plain)))
        }

    @Test
    fun `a hash in no known shape is not judged`() =
        runTest {
            for (odd in listOf("", "zz", "abc".repeat(20), "a".repeat(41), "A".repeat(63) + "é")) {
                val info = BridgeFileInfo(bucket, id = fileId, index = hex(index), shards = listOf(BridgeShard(0, odd, "u")))

                assertNull(proof(plain, info = info).prove("u1", localFile(plain)), "hash of length ${odd.length}")
            }
        }

    @Test
    fun `a malformed or short index is not judged`() =
        runTest {
            for (badIndex in listOf("not hex at all", "abcd", "")) {
                val info = BridgeFileInfo(bucket, id = fileId, index = badIndex, shards = listOf(BridgeShard(0, recorded(plain, "ripemd"), "u")))

                assertNull(proof(plain, info = info).prove("u1", localFile(plain)), "index '$badIndex'")
            }
        }

    @Test
    fun `a bridge record of another object is not judged`() =
        runTest {
            val info = BridgeFileInfo(bucket, id = "someone-else", index = hex(index), shards = listOf(BridgeShard(0, recorded(plain, "ripemd"), "u")))

            assertNull(proof(plain, info = info).prove("u1", localFile(plain)))
        }

    @Test
    fun `an encryption version this client cannot read is not judged`() =
        runTest {
            val meta = InternxtFile(uuid = "u1", fileId = fileId, bucket = bucket, size = plain.size.toString(), encryptVersion = "99-other")

            assertNull(proof(plain, meta = meta).prove("u1", localFile(plain)))
        }

    @Test
    fun `an empty drive entry has no stored object and the sizes decide`() =
        runTest {
            val empty = InternxtFile(uuid = "u1", fileId = null, bucket = null, size = "0")
            val calls = Calls()

            assertEquals(ContentProof(true, "empty"), proof(ByteArray(0), meta = empty, calls = calls).prove("u1", localFile(ByteArray(0))))
            assertEquals(ContentProof(false, "empty"), proof(ByteArray(0), meta = empty, calls = calls).prove("u1", localFile(plain)))
            assertEquals(0, calls.bridge)
        }

    @Test
    fun `a drive entry that declares bytes and has no object is not judged`() =
        runTest {
            val broken = InternxtFile(uuid = "u1", fileId = null, bucket = null, size = "10")

            assertNull(proof(plain, meta = broken).prove("u1", localFile(plain)))
        }

    @Test
    fun `a declared size of zero with an object is the old size-unknown shape and is decided by the hash`() =
        runTest {
            val unknownSize = InternxtFile(uuid = "u1", fileId = fileId, bucket = bucket, size = "0")

            assertEquals(ContentProof(true, "internxt-shard-hash"), proof(plain, meta = unknownSize).prove("u1", localFile(plain)))
            assertEquals(ContentProof(false, "internxt-shard-hash"), proof(plain, meta = unknownSize).prove("u1", localFile(plain + 1.toByte())))
        }

    @Test
    fun `an unreadable declared size is not judged`() =
        runTest {
            for (size in listOf(null, "", "x", "-5")) {
                val meta = InternxtFile(uuid = "u1", fileId = fileId, bucket = bucket, size = size)

                assertNull(proof(plain, meta = meta).prove("u1", localFile(plain)), "size '$size'")
            }
        }

    @Test
    fun `a failing lookup is not turned into an answer`() =
        runTest {
            val failing =
                InternxtLocalContentProof(
                    getFileMeta = { throw java.io.IOException("network down") },
                    getBridgeFileInfo = { _, _ -> error("not asked") },
                    fileKeyFor = { _, _ -> error("not asked") },
                    crypto = crypto,
                )

            assertFailsWith<java.io.IOException> { failing.prove("u1", localFile(plain)) }
        }
}
