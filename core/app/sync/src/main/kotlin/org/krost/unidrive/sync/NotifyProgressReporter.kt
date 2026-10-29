package org.krost.unidrive.sync

import org.krost.unidrive.io.childProcessBuilder
import org.krost.unidrive.http.UrlRedaction
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * The production executor of a desktop notification's command (#221): the notifier is a third-party program, so it never
 * inherits [org.krost.unidrive.io.VAULT_PASSPHRASE_ENV] ([childProcessBuilder], #83 E), its output is discarded (a chatty
 * child must not block on a full pipe) and a child that outlives the budget is destroyed, not left behind.
 */
internal val DEFAULT_NOTIFY_EXECUTOR: (List<String>) -> Unit = { cmd ->
    val process = childProcessBuilder(cmd)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .start()
    if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroy()
}

/**
 * ProgressReporter that sends desktop notifications via notify-send.
 * Only fires on: sync complete (with changes), warnings, and conflicts.
 * Silent when sync has zero changes (avoids notification spam).
 */
class NotifyProgressReporter(
    private val profileName: String,
    private val commandExecutor: (List<String>) -> Unit = DEFAULT_NOTIFY_EXECUTOR,
) : ProgressReporter {
    private val log = LoggerFactory.getLogger(NotifyProgressReporter::class.java)

    override fun onScanProgress(
        phase: String,
        count: Int,
    ) {}

    override fun onActionCount(
        total: Int,
        preFilterTotal: Int,
        filterReason: String?,
    ) {}

    override fun onActionProgress(
        index: Int,
        total: Int,
        action: String,
        path: String,
    ) {}

    override fun onTransferProgress(
        path: String,
        bytesTransferred: Long,
        totalBytes: Long,
    ) {}

    override fun onSyncComplete(
        downloaded: Int,
        uploaded: Int,
        conflicts: Int,
        durationMs: Long,
        actionCounts: Map<String, Int>,
        failed: Int,
    ) {
        val noTransfersOrConflicts = downloaded == 0 && uploaded == 0 && conflicts == 0
        if (noTransfersOrConflicts && failed == 0) return
        val parts = mutableListOf<String>()
        if (downloaded > 0) parts.add("↓$downloaded")
        if (uploaded > 0) parts.add("↑$uploaded")
        if (conflicts > 0) parts.add("⚠$conflicts conflicts")
        val body = parts.joinToString(", ")
        val urgency = if (conflicts > 0) "critical" else "normal"
        notify("Sync complete ($profileName)", body, urgency)
    }

    override fun onWarning(message: String) {
        notify("Sync warning ($profileName)", UrlRedaction.redact(message), "critical")
    }

    private fun notify(
        summary: String,
        body: String,
        urgency: String,
    ) {
        try {
            val cmd =
                listOf(
                    "notify-send",
                    "--app-name=UniDrive",
                    "--urgency=$urgency",
                    summary,
                    body,
                )
            commandExecutor(cmd)
        } catch (e: Exception) {
            log.debug("notify-send failed: {}", e.message)
        }
    }

    companion object {
        /** Returns true if notify-send is available on the system. */
        fun isAvailable(): Boolean =
            try {
                val p = childProcessBuilder(listOf("which", "notify-send")).start()
                p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
            } catch (_: Exception) {
                false
            }
    }
}
