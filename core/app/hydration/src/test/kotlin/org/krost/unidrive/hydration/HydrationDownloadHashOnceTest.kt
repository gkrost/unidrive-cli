package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.DownloadReceipt
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * gkrost/unidrive-cli#392: a hydration reads the downloaded file from the local disk zero extra times when the provider
 * hashed the plaintext while it wrote it (Internxt, as it decrypts). The provider here reports a marker hash no read of the
 * file could produce, so a row carrying the real SHA-256 shows that the engine read the cache copy a second time to hash it.
 * A receipt without a hash, or one whose staged size or mtime is not the cache copy's, still has the copy read and hashed.
 */
class HydrationDownloadHashOnceTest {
    private class Env(
        val provider: MemProvider,
        val db: StateDatabase,
        val engine: SyncEngine,
    )

    private val content = ByteArray(4096) { (it % 251).toByte() }

    private fun freshEnv(): Env {
        val provider = MemProvider()
        val db = StateDatabase(Files.createTempDirectory("ud-392-hash-db").resolve("state.db"))
        db.initialize()
        val engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = Files.createTempDirectory("ud-392-hash-root"),
                cacheRoot = Files.createTempDirectory("ud-392-hash-cache"),
                cacheKey = "ud-392-hash-isolated-profile",
            )
        provider.seed("/a.bin", content)
        kotlinx.coroutines.runBlocking { MountEngine.over(engine).enumerateRemoteIntoState(true) }
        return Env(provider, db, engine)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // The receipt a provider that hashes while it writes returns for [staged], with [marker] as the hash it reports.
    private fun streamed(
        staged: Path,
        sizeDelta: Long = 0,
        mtimeDelta: Long? = 0,
    ): DownloadReceipt =
        DownloadReceipt(
            bytesWritten = Files.size(staged),
            plaintextSha256 = MARKER,
            stagedSize = Files.size(staged) + sizeDelta,
            stagedMtimeMillis = mtimeDelta?.let { Files.getLastModifiedTime(staged).toMillis() + it },
        )

    private suspend fun Env.hydratedHash(scope: kotlinx.coroutines.test.TestScope): String? {
        assertIs<HydrateResult.Ok>(HydrationImpl(engine, db, recoveryUploadScope = scope).hydrate("/a.bin"))
        assertEquals(1, provider.downloads.count { it == "/a.bin" }, "one download: ${provider.downloads}")
        return db.getEntry("/a.bin")?.localHash
    }

    @Test
    fun `the hash the provider took while writing becomes the row's local hash and the copy is not read again`() =
        runTest {
            val env = freshEnv()
            env.provider.receiptFor = { _, staged -> streamed(staged) }

            assertEquals(MARKER, env.hydratedHash(this), "the engine took the provider's hash; it did not read the cache copy")
        }

    @Test
    fun `a receipt with the staged size but no mtime has the copy hashed`() =
        runTest {
            val env = freshEnv()
            env.provider.receiptFor = { _, staged -> streamed(staged, mtimeDelta = null) }

            assertEquals(sha256(content), env.hydratedHash(this))
        }

    @Test
    fun `a same-size write to the staged copy under a receipt with no mtime has the modified copy hashed`() =
        runTest {
            val env = freshEnv()
            val modified = content.reversedArray() // the same length, other bytes
            env.provider.receiptFor = { _, staged ->
                val receipt = streamed(staged, mtimeDelta = null)
                Files.write(staged, modified) // written after the provider reported its hash, before the engine reads the copy
                receipt
            }

            assertEquals(sha256(modified), env.hydratedHash(this))
        }

    @Test
    fun `a receipt without a hash still has the copy hashed`() =
        runTest {
            val env = freshEnv()

            assertEquals(sha256(content), env.hydratedHash(this))
        }

    @Test
    fun `a receipt whose staged size is not the copy's has the copy hashed`() =
        runTest {
            val env = freshEnv()
            env.provider.receiptFor = { _, staged -> streamed(staged, sizeDelta = 1) }

            assertEquals(sha256(content), env.hydratedHash(this))
        }

    @Test
    fun `a receipt without a staged size has the copy hashed`() =
        runTest {
            val env = freshEnv()
            env.provider.receiptFor = { _, staged -> streamed(staged).copy(stagedSize = null) }

            assertEquals(sha256(content), env.hydratedHash(this))
        }

    @Test
    fun `a receipt whose staged mtime is not the copy's has the copy hashed`() =
        runTest {
            val env = freshEnv()
            env.provider.receiptFor = { _, staged -> streamed(staged, mtimeDelta = 60_000) }

            assertEquals(sha256(content), env.hydratedHash(this))
        }

    private companion object {
        // Not a SHA-256 of any bytes: 64 characters, but not hex.
        val MARKER = "z".repeat(64)
    }
}
