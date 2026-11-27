package org.krost.unidrive.xtra

import org.krost.unidrive.testing.TestScratch
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Review finding on the symmetric key chain: two initializations racing for one key file must not let the second
 * replace the first. The key file is the only key of every file it sealed, so exactly one generate may win, the others
 * refuse, and the file on disk is the winner's key (what the winner wraps, a fresh load unwraps).
 */
class XtraKeyFileRaceTest {
    private val pass = "race-pass-12345"

    private fun <K> race(
        threads: Int,
        generate: () -> K,
        wrap: (K, ByteArray) -> ByteArray,
    ): List<Pair<ByteArray, ByteArray>> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures =
                (1..threads).map {
                    pool.submit<Pair<ByteArray, ByteArray>?> {
                        start.await()
                        val store = runCatching { generate() }.getOrNull() ?: return@submit null
                        val fileKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
                        fileKey to wrap(store, fileKey)
                    }
                }
            start.countDown()
            return futures.mapNotNull { it.get(2, TimeUnit.MINUTES) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `concurrent symmetric generates leave exactly one key, the winner's`() {
        val path: Path = TestScratch.directory("xtra-race-sym").resolve("xtra-sym.key")
        val winners =
            race(8, { XtraSymmetricKeyStore(path).also { it.generate(pass.toCharArray()) } }) { store, key -> store.wrapKey(key) }

        assertEquals(1, winners.size, "exactly one generate may succeed; the others must refuse")
        val onDisk = XtraSymmetricKeyStore(path).also { it.load(pass.toCharArray()) }
        val (fileKey, wrapped) = winners.single()
        assertContentEquals(fileKey, onDisk.unwrapKey(wrapped), "the key file on disk is the winner's key")
    }

    @Test
    fun `concurrent RSA generates leave exactly one key, the winner's`() {
        val path: Path = TestScratch.directory("xtra-race-rsa").resolve("xtra.key")
        val winners =
            race(4, { XtraKeyManager(path).also { it.generate(pass.toCharArray()) } }) { manager, key -> manager.wrapKey(key) }

        assertEquals(1, winners.size, "exactly one generate may succeed; the others must refuse")
        val onDisk = XtraKeyManager(path).also { it.load(pass.toCharArray()) }
        val (fileKey, wrapped) = winners.single()
        assertContentEquals(fileKey, onDisk.unwrapKey(wrapped), "the key file on disk is the winner's key")
    }
}
