package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.krost.unidrive.sync.ProfileMode
import org.krost.unidrive.sync.StateDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The daemon writes each quota snapshot to state.db for `quota --json` and the next start. The local scanner's
 * manual batch can stay open for a whole pass, and a refresh that waited for it kept the provider open and the
 * next refresh blocked the whole time.
 */
class DaemonQuotaPersistTest {
    private lateinit var tempDir: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("daemon-quota-persist-test")
        db = StateDatabase(tempDir.resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        runCatching { tempDir.toFile().deleteRecursively() }
    }

    private fun runtime() =
        DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "quota_persist_test",
            lockFile = tempDir.resolve(".lock"),
            dbPath = tempDir.resolve("state.db"),
            syncRoot = tempDir,
            socketPath = tempDir.resolve("daemon.sock"),
            providerFactory = { error("no provider is opened here") },
        )

    private fun snapshot(used: Long) =
        DaemonRuntime.QuotaSnapshot(usedBytes = used, totalBytes = 10, remainingBytes = 10 - used, fetchedAtMs = 1_000, stale = false)

    @Test
    fun `a quota refresh does not wait for an open manual batch, and the next refresh writes what it could not`() {
        val runtime = runtime()
        db.beginBatch() // the local scanner's batch, open for a whole pass
        try {
            val returned =
                CompletableFuture
                    .supplyAsync { runBlocking { withTimeoutOrNull(2_000) { runtime.persistQuotaSnapshot(db, snapshot(1)) } } }
                    .get(10, TimeUnit.SECONDS)
            assertNotNull(returned, "the refresh returns at once and frees the provider and the in-flight flag")
            assertNull(db.getSyncState("quota_used"), "nothing is written into the open batch")
        } finally {
            db.rollbackBatch()
        }

        runtime.retryQuotaPersist(db)

        assertEquals("1", db.getSyncState("quota_used"))
        assertEquals("10", db.getSyncState("quota_total"))
        assertEquals("9", db.getSyncState("quota_remaining"))
    }

    @Test
    fun `a newer snapshot replaces values that could not be written yet`() {
        val runtime = runtime()
        db.beginBatch()
        try {
            CompletableFuture.runAsync { runtime.persistQuotaSnapshot(db, snapshot(1)) }.get(10, TimeUnit.SECONDS)
        } finally {
            db.rollbackBatch()
        }

        runtime.persistQuotaSnapshot(db, snapshot(4))
        assertEquals("4", db.getSyncState("quota_used"), "the newer snapshot replaced the pending one and was written")
        assertEquals("6", db.getSyncState("quota_remaining"))
        db.setSyncState("quota_used", "7") // written by someone else after the newer snapshot landed
        runtime.retryQuotaPersist(db)

        assertEquals("7", db.getSyncState("quota_used"), "nothing older is written over it later")
    }
}
