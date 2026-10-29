package org.krost.unidrive.sync

import org.junit.Assume.assumeTrue
import java.nio.file.Files
import kotlin.system.measureTimeMillis
import kotlin.test.*

class NotifyProgressReporterTest {
    private val noOpExecutor: (List<String>) -> Unit = {}

    @Test
    fun `onSyncComplete with zero changes does not crash`() {
        val reporter = NotifyProgressReporter("test", commandExecutor = noOpExecutor)
        reporter.onSyncComplete(0, 0, 0, 100)
    }

    @Test
    fun `onSyncComplete with changes does not crash even without notify-send`() {
        val reporter = NotifyProgressReporter("test", commandExecutor = noOpExecutor)
        reporter.onSyncComplete(5, 3, 1, 2000)
    }

    @Test
    fun `onWarning does not crash even without notify-send`() {
        val reporter = NotifyProgressReporter("test", commandExecutor = noOpExecutor)
        reporter.onWarning("Test warning message")
    }

    // archive#1060: the text of a provider exception can quote a pre-authenticated URL.
    @Test
    fun `onWarning notifies without the query of a URL`() {
        val commands = mutableListOf<List<String>>()
        val reporter = NotifyProgressReporter("test", commandExecutor = { commands.add(it) })

        reporter.onWarning("Failed: /a.txt - Request timeout has expired [url=https://x/y?tempauth=SECRET, request_timeout=600000 ms]")

        val body = commands.single().last()
        assertFalse("SECRET" in body, body)
        assertTrue("[url=https://x/y?<redacted>, request_timeout=600000 ms]" in body, body)
    }

    // #221: a notifier that outlives the executor's budget is destroyed, not left behind. The destroy's SIGTERM is
    // observable as the child's own trap writing the marker; without the destroy the child keeps running and the
    // marker never appears. The vault passphrase the executor must strip is not unit-testable (a child can only
    // see the environment it inherits), so it rests on the executor using childProcessBuilder.
    @Test
    fun `the default executor destroys a notifier that outlives its budget`() {
        assumeTrue("the trap-and-destroy oracle needs a POSIX shell", !System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true))
        val marker = Files.createTempDirectory("unidrive-notify-destroy-").resolve("destroyed.marker")
        val elapsed = measureTimeMillis {
            DEFAULT_NOTIFY_EXECUTOR(listOf("sh", "-c", "trap 'touch ${marker.toAbsolutePath()}' TERM; sleep 30 & wait"))
        }
        assertTrue(elapsed < 20_000, "the executor waited for the 30 s child instead of its 5 s budget: ${elapsed} ms")
        val deadline = System.currentTimeMillis() + 5_000
        var destroyed = false
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(marker)) { destroyed = true; break }
            Thread.sleep(50)
        }
        assertTrue(destroyed, "the child was not destroyed (no SIGTERM reached its trap) after ${elapsed} ms")
        Files.deleteIfExists(marker.parent)
    }
}
