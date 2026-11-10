package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#567, the durability claim: a row whose call has returned is on the disk after a kill, and a row whose
 * call has not returned may be missing. A child JVM writes rows through [StateGroupCommit], prints `ACK <path>` for each call
 * that returned, and parks inside the transaction of the last group; this test ends it with `destroyForcibly` (the process
 * gets no chance to flush or roll back, as under `kill -9`) and then reads the file back with a fresh database object.
 */
class StateGroupCommitKillTest {
    @Test
    fun `after a kill every acknowledged row is on the disk and the rows in the stalled transaction are not`() {
        val dir = Files.createTempDirectory("unidrive-group-commit-kill")
        val dbFile = dir.resolve("state.db")
        val exe = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val java = Path.of(System.getProperty("java.home"), "bin", exe)
        val child =
            ProcessBuilder(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                "org.krost.unidrive.sync.StateGroupCommitKillChildKt",
                dbFile.toString(),
            ).redirectErrorStream(true).start()
        val acked = mutableListOf<String>()
        val lines = LinkedBlockingQueue<String>()
        val reader =
            Thread {
                child.inputStream.bufferedReader().forEachLine { lines.add(it) }
            }.also {
                it.isDaemon = true
                it.start()
            }
        var stalled = false
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        val seen = mutableListOf<String>()
        while (!stalled && System.nanoTime() < deadline) {
            val line = lines.poll(200, TimeUnit.MILLISECONDS) ?: continue
            seen += line
            if (line.startsWith("ACK ")) acked += line.removePrefix("ACK ") else if (line == "STALLED") stalled = true
        }
        assertTrue(stalled, "the child did not reach its stalled commit; output: $seen")
        Thread.sleep(500) // let the other stalled rows queue behind the one in the transaction
        while (true) {
            val line = lines.poll() ?: break
            if (line.startsWith("ACK ")) acked += line.removePrefix("ACK ")
        }
        child.destroyForcibly().waitFor(30, TimeUnit.SECONDS)
        reader.join(5_000)

        assertTrue(acked.size >= 13, "5 sequential and 8 concurrent calls returned before the stall: $acked")
        assertTrue(acked.none { it.startsWith("/stall-") }, "a stalled call never returns: $acked")
        val db = StateDatabase(dbFile)
        db.initialize() // the first open after a killed writer may need the retries of openWritable
        try {
            val alive = db.getAllEntries().map { it.path }.toSet()
            for (path in acked) {
                assertTrue(path in alive, "$path was acknowledged to its caller and must have survived the kill; alive: $alive")
            }
            assertEquals(emptySet(), alive.filter { it.startsWith("/stall-") }.toSet(), "rows of an uncommitted transaction are gone")
        } finally {
            db.close()
            dir.toFile().deleteRecursively()
        }
    }
}
