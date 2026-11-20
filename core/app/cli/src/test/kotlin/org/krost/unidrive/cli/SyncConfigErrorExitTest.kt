package org.krost.unidrive.cli

import org.krost.unidrive.testing.TestScratch
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #749 finding 5: a configuration error in `sync` exits from inside provider creation. The shutdown hook must not
 * be registered by then, or it announces "Shutting down..." and waits out the whole deadline for a run that never began.
 */
class SyncConfigErrorExitTest {
    @Test
    fun `sync with a provider configuration error exits at once without a shutdown stall`() {
        val config = TestScratch.directory("sync-config-error")
        try {
            Files.writeString(
                config.resolve("config.toml"),
                "[providers.broken]\ntype = \"s3\"\nmode = \"mirror\"\nsync_root = '${config.resolve("root").toString().replace('\\', '/')}'\n",
            )
            Files.createDirectories(config.resolve("root"))
            val started = System.nanoTime()
            val process =
                ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-jar",
                    System.getProperty("unidrive.runtime.test.jar"),
                    "-c",
                    config.toString(),
                    "-p",
                    "broken",
                    "sync",
                ).redirectErrorStream(true).start()
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "sync must terminate")
            val output = process.inputStream.bufferedReader().readText()
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(1, process.exitValue(), output)
            assertFalse(output.contains("Shutting down"), output)
            assertFalse(output.contains("did not end within"), output)
            assertTrue(tookMs < 9_000, "the exit must not wait for the 10 s shutdown deadline, took $tookMs ms: $output")
        } finally {
            config.toFile().deleteRecursively()
        }
    }
}
