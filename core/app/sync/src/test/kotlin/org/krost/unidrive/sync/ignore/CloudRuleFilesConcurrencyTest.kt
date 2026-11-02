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
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * #212: a probe holds no lock across the download, and never waits for a transfer permit while it holds one. Before,
 * `probe` was `@Synchronized` and downloaded inside the monitor: a probe that waited for a permit there blocked every
 * other probe, including those of uploads holding the permits it waited for, until the download's timeout.
 */
class CloudRuleFilesConcurrencyTest {
    private val dir: Path = Files.createTempDirectory("ud-cloud-rules-concurrency")
    private val bodies = ConcurrentHashMap<String, String>()
    private val downloads = AtomicInteger()

    // How long a probe that cannot get on waits for its download (permit included) before it gives up.
    private val timeoutMs = 4_000L

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun put(
        path: String,
        text: String,
    ) {
        bodies[path] = text
    }

    private fun files(
        transfers: RuleFileTransfers,
        beforeDownload: (String) -> Unit = {},
    ) = CloudRuleFiles(
        dir = dir.resolve("rules"),
        lookup = { path -> bodies[path]?.let { CloudRuleFile("id-$path", path, it.length.toLong(), "v1") } },
        download = { _, remotePath, destination ->
            beforeDownload(remotePath)
            downloads.incrementAndGet()
            Files.writeString(destination, bodies.getValue(remotePath))
        },
        transfers = { transfers },
        timeoutMs = timeoutMs,
    )

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

    @Test
    fun anUploadHoldingThePermitIsNotStalledByAProbeWaitingForIt() {
        put("/a/.unidrive.ignore", "*.o\n")
        put("/b/.unidrive.ignore", "*.tmp\n")
        val transfers = OnePermit()
        val files = files(transfers)
        val uploadHolds = CountDownLatch(1)
        val uploadProbe = AtomicReference<RuleFileProbe>()
        val uploadTookMs = AtomicReference<Long>()
        val ipcProbe = AtomicReference<RuleFileProbe>()

        // The upload: it holds the only permit, and asks for its folder's rules once the other probe waits for it.
        val upload =
            thread(name = "upload") {
                runBlocking {
                    transfers.permits.withPermit {
                        withContext(transfers.holdsPermit.asContextElement(true)) {
                            uploadHolds.countDown()
                            transfers.waitingForPermit.await(timeoutMs, TimeUnit.MILLISECONDS)
                            val start = System.nanoTime()
                            uploadProbe.set(files.probe("/b/.unidrive.ignore"))
                            uploadTookMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
                        }
                    }
                }
            }
        assertTrue(uploadHolds.await(timeoutMs, TimeUnit.MILLISECONDS))
        // An IPC verb without a permit: its rule file must be downloaded, so it waits for the upload's permit.
        val ipc = thread(name = "ipc") { ipcProbe.set(files.probe("/a/.unidrive.ignore")) }

        upload.join(3 * timeoutMs)
        ipc.join(3 * timeoutMs)

        assertIs<RuleFileProbe.Present>(uploadProbe.get(), "the upload's own rule file is downloaded under its permit")
        assertTrue(uploadTookMs.get() < timeoutMs / 2, "the upload waited ${uploadTookMs.get()} ms behind the other probe")
        assertIs<RuleFileProbe.Present>(ipcProbe.get(), "the waiting probe gets the permit once the upload ends")
        assertEquals(2, downloads.get())
    }

    @Test
    fun aCopyAlreadyDownloadedIsAnsweredWhileAnotherRuleFileDownloads() {
        put("/a/.unidrive.ignore", "*.o\n")
        put("/slow/.unidrive.ignore", "*.tmp\n")
        val release = CountDownLatch(1)
        val slowStarted = CountDownLatch(1)
        val files =
            files(RuleFileTransfers.DIRECT) { path ->
                if (path == "/slow/.unidrive.ignore") {
                    slowStarted.countDown()
                    release.await(timeoutMs, TimeUnit.MILLISECONDS)
                }
            }
        assertIs<RuleFileProbe.Present>(files.probe("/a/.unidrive.ignore"))
        val slow = thread(name = "slow") { files.probe("/slow/.unidrive.ignore") }
        try {
            assertTrue(slowStarted.await(timeoutMs, TimeUnit.MILLISECONDS))
            val start = System.nanoTime()
            assertIs<RuleFileProbe.Present>(files.probe("/a/.unidrive.ignore"))
            val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue(tookMs < timeoutMs / 2, "the kept copy waited $tookMs ms behind another file's download")
        } finally {
            release.countDown()
            slow.join(3 * timeoutMs)
        }
    }

    @Test
    fun probesOfTheSameRuleFileAtOnceDownloadItOnce() {
        put("/a/.unidrive.ignore", "*.o\n")
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val files =
            files(RuleFileTransfers.DIRECT) {
                started.countDown()
                release.await(timeoutMs, TimeUnit.MILLISECONDS)
            }
        val results = ConcurrentHashMap<Int, RuleFileProbe>()
        val probes = (0 until 4).map { i -> thread(name = "probe-$i") { results[i] = files.probe("/a/.unidrive.ignore") } }
        assertTrue(started.await(timeoutMs, TimeUnit.MILLISECONDS))
        // Give the other probes the time to reach the download that runs.
        Thread.sleep(200)
        release.countDown()
        probes.forEach { it.join(3 * timeoutMs) }

        assertEquals(4, results.size)
        results.values.forEach { assertIs<RuleFileProbe.Present>(it) }
        assertEquals(1, downloads.get(), "the probes that came during the download use its copy")
    }
}
