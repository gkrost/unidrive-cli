package org.krost.unidrive.sync

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
}
