package org.krost.unidrive.xtra

import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
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

    // ── Imports: the same file, the same guarantee ─────────────────────────────────────────────────────────────────

    /** Runs [import] on [threads] threads released together; the indexes of the ones that did not throw. */
    private fun importRace(
        threads: Int,
        import: (Int) -> Unit,
    ): List<Int> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures =
                (0 until threads).map { i ->
                    pool.submit<Int?> {
                        start.await()
                        if (runCatching { import(i) }.isSuccess) i else null
                    }
                }
            start.countDown()
            return futures.mapNotNull { it.get(2, TimeUnit.MINUTES) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun symmetricKeyFiles(count: Int): List<ByteArray> {
        val scratch = TestScratch.directory("xtra-race-key-files")
        return (0 until count).map { i ->
            val path = scratch.resolve("k$i.key")
            XtraSymmetricKeyStore(path).generate(pass.toCharArray())
            Files.readAllBytes(path)
        }
    }

    /** What an RSA import accepts is only a length floor; distinct blobs stand in for distinct key files. */
    private fun rsaKeyFiles(count: Int): List<ByteArray> = (0 until count).map { ByteArray(64).also { b -> SecureRandom().nextBytes(b) } }

    /** Every key file that still exists beside [path]: the live one and each retired `.bak-` copy, as text for a set. */
    private fun keptKeys(path: Path): Set<String> {
        val retired = Files.list(path.parent).use { s -> s.filter { it.fileName.toString().startsWith("${path.fileName}.bak-") }.toList() }
        return (listOf(path) + retired).map { java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(it)) }.toSet()
    }

    private fun text(bytes: ByteArray) = java.util.Base64.getEncoder().encodeToString(bytes)

    @Test
    fun `concurrent symmetric imports into an empty installation leave exactly one key, the winner's`() {
        val keys = symmetricKeyFiles(8)
        val path: Path = TestScratch.directory("xtra-race-import-sym").resolve("xtra-sym.key")

        val winners = importRace(keys.size) { XtraSymmetricKeyStore(path).importKey(keys[it]) }

        assertEquals(1, winners.size, "exactly one import may succeed; the others must refuse")
        assertContentEquals(keys[winners.single()], Files.readAllBytes(path), "the key file on disk is the winner's")
    }

    @Test
    fun `concurrent RSA imports into an empty installation leave exactly one key, the winner's`() {
        val keys = rsaKeyFiles(8)
        val path: Path = TestScratch.directory("xtra-race-import-rsa").resolve("xtra.key")

        val winners = importRace(keys.size) { XtraKeyManager(path).importKey(keys[it]) }

        assertEquals(1, winners.size, "exactly one import may succeed; the others must refuse")
        assertContentEquals(keys[winners.single()], Files.readAllBytes(path), "the key file on disk is the winner's")
    }

    @Test
    fun `concurrent forced symmetric imports lose no key, each retired one stays beside the live one`() {
        val keys = symmetricKeyFiles(9) // 0 is installed, 1..8 are imported over it
        val path: Path = TestScratch.directory("xtra-race-force-sym").resolve("xtra-sym.key")
        Files.write(path, keys[0])

        val done = importRace(8) { XtraSymmetricKeyStore(path).importKey(keys[it + 1], force = true) }

        assertEquals(8, done.size, "a forced import is serialized, not refused")
        assertEquals(keys.map(::text).toSet(), keptKeys(path), "every key is the live one or a retired copy; none is gone")
    }

    @Test
    fun `concurrent forced RSA imports lose no key, each retired one stays beside the live one`() {
        val keys = rsaKeyFiles(9)
        val path: Path = TestScratch.directory("xtra-race-force-rsa").resolve("xtra.key")
        Files.write(path, keys[0])

        val done = importRace(8) { XtraKeyManager(path).importKey(keys[it + 1], force = true) }

        assertEquals(8, done.size, "a forced import is serialized, not refused")
        assertEquals(keys.map(::text).toSet(), keptKeys(path), "every key is the live one or a retired copy; none is gone")
    }
}
