package org.krost.unidrive.cli

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * gkrost/unidrive-cli#213: the shutdown hook of a `sync` run waits for the pass, closes state.db after it, and releases
 * the profile lock last; a pass that does not end in time holds it up no longer than the deadline.
 *
 * Each test plays the main thread of `sync` on a thread of its own: a runBlocking whose coroutine is attached to the
 * [SyncShutdown], a transfer launched on Dispatchers.IO as the engine's Pass 2 does, and [SyncShutdown.release] on the
 * way out. The test thread plays the JVM's shutdown hook.
 */
class SyncShutdownTest {
    private val events = CopyOnWriteArrayList<String>()

    private fun record(event: String) {
        events += event
    }

    /** Runs [body] as the run of `sync` on a main thread of its own; [SyncShutdown.release] is its way out, whatever the end. */
    private fun startRun(
        shutdown: SyncShutdown,
        body: suspend kotlinx.coroutines.CoroutineScope.() -> Unit,
    ): Thread =
        Thread({
            try {
                runBlocking {
                    shutdown.attach(coroutineContext.job)
                    body()
                }
            } catch (_: CancellationException) {
                // The shutdown cancelled the run; SyncCommand returns normally then.
            } finally {
                shutdown.release()
            }
        }, "sync-main").apply {
            isDaemon = true
            start()
        }

    private fun registerAllSteps(shutdown: SyncShutdown) {
        // Registered out of order on purpose: the order of release is the Step order, not the order of registration.
        for (step in listOf(
            SyncShutdown.Step.DATABASE,
            SyncShutdown.Step.WATCHER,
            SyncShutdown.Step.SUBSCRIPTIONS,
            SyncShutdown.Step.IPC_SERVER,
            SyncShutdown.Step.PROVIDER,
            SyncShutdown.Step.RENEWALS,
        )) {
            shutdown.closing(step) { record("close $step") }
        }
        shutdown.unlockingLast { record("unlock") }
    }

    @Test
    fun `a pass still writing when the shutdown starts ends before state_db is closed`() {
        val shutdown = SyncShutdown(deadlineMs = 10_000, announce = {})
        shutdown.closing(SyncShutdown.Step.DATABASE) { record("db.close") }
        shutdown.unlockingLast { record("unlock") }
        val transferring = CountDownLatch(1)
        val main =
            startRun(shutdown) {
                launch(Dispatchers.IO) {
                    try {
                        transferring.countDown()
                        awaitCancellation()
                    } finally {
                        // What a cancelled download does before it ends (restoreToPlaceholder): it writes its row.
                        withContext(NonCancellable) {
                            delay(300)
                            record("pass.write")
                        }
                    }
                }
            }
        assertTrue(transferring.await(10, TimeUnit.SECONDS), "the transfer must have started")

        val inTime = shutdown.shutdownAndWait()

        assertTrue(inTime, "the pass ended well within the deadline")
        assertEquals(listOf("pass.write", "db.close", "unlock"), events, "state.db closes only after the pass wrote its last row")
        main.join(10_000)
        assertFalse(main.isAlive, "the run's main thread must end")
    }

    @Test
    fun `the profile lock is released after state_db is closed and never before, whichever thread releases`() {
        val shutdown = SyncShutdown(deadlineMs = 10_000, announce = {})
        val dbClosing = CountDownLatch(1)
        shutdown.closing(SyncShutdown.Step.DATABASE) {
            record("db.close.begin")
            dbClosing.countDown()
            Thread.sleep(300)
            record("db.close.end")
            error("a close that throws must not skip the unlock")
        }
        shutdown.unlockingLast { record("unlock") }
        val main = startRun(shutdown) { awaitCancellation() }

        // The hook and the main thread's own way out both release: the second must wait for the first, not unlock early.
        val hook = Thread({ shutdown.shutdownAndWait() }, "hook").apply { start() }
        assertTrue(dbClosing.await(10, TimeUnit.SECONDS), "state.db must begin to close")
        shutdown.release()
        val returnedAfter = events.toList()
        hook.join(10_000)
        main.join(10_000)

        assertEquals(listOf("db.close.begin", "db.close.end", "unlock"), events, "one unlock, after the database is closed")
        assertEquals(events.toList(), returnedAfter, "a second release returns only once the first has unlocked")
        assertFalse(hook.isAlive || main.isAlive, "the hook and the run must both end")
    }

    @Test
    fun `a pass that overruns the deadline does not hold the shutdown and the rest still closes and unlocks`() {
        val overrun = AtomicLong(-1)
        val shutdown = SyncShutdown(deadlineMs = 300, announce = {}, reportOverrun = { overrun.set(it) })
        registerAllSteps(shutdown)
        val stuck = CountDownLatch(1)
        val transferring = CountDownLatch(1)
        val main =
            startRun(shutdown) {
                // A pass in blocking code that never reaches a suspension point: cancellation cannot end it.
                launch(Dispatchers.IO) {
                    transferring.countDown()
                    stuck.await()
                }
            }
        assertTrue(transferring.await(10, TimeUnit.SECONDS), "the transfer must have started")

        val started = System.nanoTime()
        val inTime = shutdown.shutdownAndWait()
        val tookMs = (System.nanoTime() - started) / 1_000_000

        try {
            assertFalse(inTime, "the hook must report that the pass overran the deadline")
            assertTrue(tookMs < 5_000, "the hook must return soon after the 300 ms deadline, took $tookMs ms")
            assertEquals(300, overrun.get(), "the overrun is reported with the deadline")
            assertEquals(
                SyncShutdown.Step.entries.map { "close $it" } + "unlock",
                events,
                "every step still closes, and the profile is released last",
            )
        } finally {
            stuck.countDown()
            main.join(10_000)
        }
    }

    @Test
    fun `the shutdown stops the run, waits for the pass, closes in order and unlocks last`() {
        val shutdown = SyncShutdown(deadlineMs = 10_000, announce = { record("announce") })
        registerAllSteps(shutdown)
        val transferring = CountDownLatch(1)
        val main =
            startRun(shutdown) {
                launch(Dispatchers.IO) {
                    try {
                        transferring.countDown()
                        awaitCancellation()
                    } catch (e: CancellationException) {
                        record("pass.cancelled stopRequested=${shutdown.stopRequested}")
                        throw e
                    } finally {
                        withContext(NonCancellable) {
                            delay(100)
                            record("pass.ended")
                        }
                    }
                }
            }
        assertTrue(transferring.await(10, TimeUnit.SECONDS), "the transfer must have started")

        assertTrue(shutdown.shutdownAndWait())
        main.join(10_000)

        assertEquals(
            listOf("announce", "pass.cancelled stopRequested=true", "pass.ended") +
                listOf("IPC_SERVER", "WATCHER", "RENEWALS", "PROVIDER", "SUBSCRIPTIONS", "DATABASE").map { "close $it" } +
                "unlock",
            events,
        )
    }

    @Test
    fun `a shutdown that begins before the run's coroutine exists cancels it once it is attached`() {
        val shutdown = SyncShutdown(deadlineMs = 10_000, announce = {})
        registerAllSteps(shutdown)
        val inTime = java.util.concurrent.atomic.AtomicBoolean(false)
        val hook = Thread({ inTime.set(shutdown.shutdownAndWait()) }, "hook").apply { isDaemon = true; start() }
        val waitUntil = System.nanoTime() + 5_000_000_000
        while (!shutdown.stopRequested && System.nanoTime() < waitUntil) Thread.sleep(10)
        assertTrue(shutdown.stopRequested, "the hook must ask the run to stop before it waits")

        // The run was still setting up when the signal came; its first suspension point ends it.
        val main = startRun(shutdown) { awaitCancellation() }
        hook.join(15_000)
        main.join(10_000)

        assertFalse(hook.isAlive, "the hook must end")
        assertTrue(inTime.get(), "the hook must not wait out the deadline for a run it could cancel")
        assertFalse(main.isAlive, "the run must end")
        assertEquals(SyncShutdown.Step.entries.map { "close $it" } + "unlock", events)
    }

    @Test
    fun `a run that ended on its own is not announced or released again when the JVM exits`() {
        val shutdown = SyncShutdown(deadlineMs = 10_000, announce = { record("announce") })
        registerAllSteps(shutdown)
        val main = startRun(shutdown) { record("pass.ended") }
        main.join(10_000)

        val started = System.nanoTime()
        assertTrue(shutdown.shutdownAndWait())
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(tookMs < 1_000, "nothing is left to wait for, took $tookMs ms")
        assertEquals(listOf("pass.ended") + SyncShutdown.Step.entries.map { "close $it" } + "unlock", events)
    }

    // The sequence above only holds if it is the run's only hook: the JVM starts hooks at once and in no order, so a second
    // one (the lock hook, the watch hook of main) would race it. The run of `sync` cannot be driven in-process here without
    // touching the live log folder, so this pins the wiring in the source (the pattern of SyncCommandNoHydrationWiringTest;
    // the test task runs in the module directory).
    @Test
    fun `sync registers the shutdown sequence as its only shutdown hook`() {
        val source = java.io.File("src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt").readText()
        val hooks = Regex("""addShutdownHook\(""").findAll(source).count()

        assertEquals(1, hooks, "SyncCommand must register exactly one shutdown hook")
        assertTrue(
            source.contains("""addShutdownHook(Thread({ shutdown.shutdownAndWait() }"""),
            "the one hook must be the SyncShutdown sequence",
        )
        assertTrue(source.contains("shutdown.attach(coroutineContext.job)"), "the run's coroutine must be handed to the sequence")
    }

    // An authentication error at sign-in leaves the run's coroutine and goes straight to System.exit
    // (Main.handleAuthError): the run is over, but nothing has released it yet.
    @Test
    fun `a run whose coroutine ended without a release is released by the hook without an announcement`() {
        val shutdown = SyncShutdown(deadlineMs = 10_000, announce = { record("announce") })
        registerAllSteps(shutdown)
        runBlocking {
            shutdown.attach(coroutineContext.job)
            record("pass.ended")
        }

        val started = System.nanoTime()
        assertTrue(shutdown.shutdownAndWait())
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(tookMs < 1_000, "nothing is left to wait for, took $tookMs ms")
        assertEquals(listOf("pass.ended") + SyncShutdown.Step.entries.map { "close $it" } + "unlock", events)
    }
}
