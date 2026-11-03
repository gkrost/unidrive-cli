package org.krost.unidrive.sync.ignore

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.krost.unidrive.engine.RuleFileTransfers
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #212: a probe of a cloud rule file waits for its transfer outside the node's monitor. The `CloudRuleFiles`-wide
 * monitor across downloads is gone; what remained is the narrower interleaving one folder up: `chainFor` held the
 * node's monitor across `refresh`, whose probe waits for a permit — so a permit-less probe of a folder serialized a
 * permit-holding upload of the same folder behind its wait.
 */
class IgnoreTreeConcurrencyTest {
    private val dir: Path = Files.createTempDirectory("ud-ignore-tree-concurrency")
    private val bodies = ConcurrentHashMap<String, String>()
    private val downloads = AtomicInteger()

    // How long a probe that cannot get on waits for its download (permit included) before it gives up.
    private val timeoutMs = 4_000L

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    // The transfer budget as RemoteOperationGuard.ruleFileTransfers runs it, with one permit: a caller already holding
    // a permit downloads under it, any other caller waits for one.
    private class OnePermit : RuleFileTransfers {
        val permits = Semaphore(1)
        val holdsPermit = ThreadLocal<Boolean>()
        val waitingForPermit = CountDownLatch(1)

        override val isPaused: Boolean get() = false

        override fun runner(path: String): suspend (block: suspend () -> Unit) -> Unit {
            val nested = holdsPermit.get() == true
            return { block ->
                if (nested) {
                    block()
                } else {
                    waitingForPermit.countDown()
                    permits.withPermit { block() }
                }
            }
        }
    }

    private fun put(
        path: String,
        text: String,
    ) {
        bodies[path] = text
    }

    // The wiring of a mount: the tree reads the cache, and a rule file only the cloud holds is downloaded on purpose.
    private fun tree(transfers: RuleFileTransfers): IgnoreTree {
        val rules =
            CloudRuleFiles(
                dir = dir.resolve("rules"),
                lookup = { path -> bodies[path]?.let { CloudRuleFile("id-$path", path, it.length.toLong(), "v1") } },
                download = { _, remotePath, destination ->
                    downloads.incrementAndGet()
                    Files.writeString(destination, bodies.getValue(remotePath))
                },
                transfers = { transfers },
                timeoutMs = timeoutMs,
            )
        val source = CacheIgnoreSource(dir.resolve("cache"), rules) { path -> bodies.containsKey(path) }
        return IgnoreTree(source)
    }

    @Test
    fun anUploadHoldingThePermitIsNotBlockedByAProbeWaitingForItInTheSameFolder() {
        put("/d/.unidrive.ignore", "*.o\n")
        val transfers = OnePermit()
        val t = tree(transfers)
        val uploadHolds = CountDownLatch(1)
        val uploadDone = CountDownLatch(1)
        val uploadTookMs = AtomicLong()
        val uploadAnswer = AtomicReference<IgnoreExplanation>()
        val ipcAnswer = AtomicReference<IgnoreExplanation>()

        // The upload: it holds the only permit, and once the other probe waits for it, asks for the same folder.
        val upload =
            thread(name = "upload") {
                runBlocking {
                    transfers.permits.withPermit {
                        withContext(transfers.holdsPermit.asContextElement(true)) {
                            uploadHolds.countDown()
                            transfers.waitingForPermit.await(timeoutMs, TimeUnit.MILLISECONDS)
                            val start = System.nanoTime()
                            uploadAnswer.set(t.explain("d/other"))
                            uploadTookMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
                            uploadDone.countDown()
                        }
                    }
                }
            }
        assertTrue(uploadHolds.await(timeoutMs, TimeUnit.MILLISECONDS))

        // An IPC verb without a permit: its probe of the folder's rule file waits for the upload's permit.
        val ipc = thread(name = "ipc") { ipcAnswer.set(t.explain("d/file")) }
        assertTrue(transfers.waitingForPermit.await(timeoutMs, TimeUnit.MILLISECONDS))

        assertTrue(
            uploadDone.await(2 * timeoutMs, TimeUnit.MILLISECONDS),
            "the upload holding the permit did not get through while the other probe waited for it",
        )
        assertTrue(
            uploadTookMs.get() < timeoutMs / 2,
            "the upload waited ${uploadTookMs.get()} ms behind the probe waiting for its permit",
        )

        // The waiting probe gets the permit once the upload ends, and reuses the copy the upload kept.
        ipc.join(3 * timeoutMs)
        upload.join(3 * timeoutMs)
        assertFalse(ipcAnswer.get().ignored, "the folder is not held back once its rule file is read")
        assertEquals(1, downloads.get(), "the waiting probe reuses the copy the upload's download kept")
    }
}
