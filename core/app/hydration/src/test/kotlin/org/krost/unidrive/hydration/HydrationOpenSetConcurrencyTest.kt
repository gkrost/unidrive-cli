package org.krost.unidrive.hydration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HydrationOpenSetConcurrencyTest {

    // dehydrate scans a connection's inner open-set map with `containsValue(path)`
    // while that same connection's IpcServer handlers — each dispatched on
    // Dispatchers.IO — concurrently put/remove handles on the inner map. If the
    // inner map is a plain HashMap, concurrent puts plus a concurrent containsValue
    // scan corrupt the table: a put-driven rehash can throw inside the scan, or make
    // the live victim entry transiently unreachable so the scan misses it. A miss
    // makes dehydrate return Ok and delete the cache of a path open for write — the
    // data-loss bug. A ConcurrentHashMap inner map is safe under exactly this
    // access pattern: the scan never throws and never misses a present entry.
    //
    // Mutations are driven directly on the real `openSets` field (grabbed by
    // reflection) so the loop carries no per-iteration file IO and runs hot enough
    // for puts and scans to truly interleave. dehydrate remains the real method on
    // the real field, so the inner-map type exercised is the one HydrationImpl ships.
    @Test
    fun `dehydrate of an open path stays Busy under concurrent open-set mutation`() =
        runBlocking(Dispatchers.IO) {
            val env = HydrationTestEnv()
            val impl = env.hydration

            env.stateDb.insertHydratedEntry("/victim.txt", localSize = 5)
            env.seedSyncedCacheContent("/victim.txt", "hello")
            // Register the victim through the public API so the inner map is the one
            // production creates; mutate that same instance directly below.
            impl.openWriteBegin("conn1", "/victim.txt", handleId = "h-victim")

            @Suppress("UNCHECKED_CAST")
            val openSets = HydrationImpl::class.java
                .getDeclaredField("openSets")
                .apply { isAccessible = true }
                .get(impl) as ConcurrentMap<String, MutableMap<String, String>>

            val inner = openSets.getValue("conn1")

            val writers = 4
            val opsPerWriter = 300_000
            val window = 4_000 // keep the live set bounded so the test terminates
            val caught = AtomicReference<Throwable?>(null)

            coroutineScope {
                // Several writers put/remove handles on the SINGLE shared inner map
                // from distinct IO threads — exactly what concurrent connection
                // handlers do. Each keeps a bounded rotating window of live keys and
                // periodically bulk-removes its window, so there is a continuous mix
                // of put, remove, and iterator-based removeIf in flight while the
                // dehydrator scans. On a plain HashMap that corrupts the table
                // (ConcurrentModificationException / torn read missing the victim).
                // The victim handle is never touched, so it must stay present.
                val writerJobs = (0 until writers).map { w ->
                    launch {
                        try {
                            for (i in 0 until opsPerWriter) {
                                if (caught.get() != null) return@launch
                                inner["w$w-h$i"] = "/churn.txt"
                                if (i >= window) inner.remove("w$w-h${i - window}")
                                if (i % 20_000 == 0) inner.keys.removeIf { it.startsWith("w$w-") }
                            }
                        } catch (t: Throwable) {
                            caught.compareAndSet(null, t)
                        }
                    }
                }

                // Dehydrator: the real dehydrate, scanning the shared inner map,
                // running until the writers finish or one corrupts the map.
                val dehydrator = launch {
                    try {
                        while (writerJobs.any { it.isActive } && caught.get() == null) {
                            val r = env.hydration.dehydrate("/victim.txt")
                            // h-victim is live the whole time, so Busy is the only
                            // correct answer. A torn read that misses it returns Ok
                            // and deletes the victim's cache file.
                            assertTrue(
                                r is DehydrateResult.Busy,
                                "dehydrate of an open path must stay Busy, got $r",
                            )
                        }
                    } catch (t: Throwable) {
                        caught.compareAndSet(null, t)
                    }
                }

                writerJobs.forEach { it.join() }
                dehydrator.join()
            }

            assertNull(caught.get(), "no exception or torn-miss expected, got: ${caught.get()}")
            assertTrue(
                java.nio.file.Files.exists(env.syncEngine.resolveCachePath("/victim.txt")),
                "cache file of a path open for write must never be dehydrated away",
            )
        }

    // archive#438: a pooled client can close on a connection other than the one that served open_read. The
    // close must find the handle across connections, and one client's close must never release another
    // client's handle that happens to carry the same id.
    @Test
    fun `a close from another connection releases the handle and a repeated or unknown close is ok`() =
        runBlocking(Dispatchers.IO) {
            val env = HydrationTestEnv()
            val impl = env.hydration
            env.stateDb.insertHydratedEntry("/victim.txt", localSize = 5)
            env.seedSyncedCacheContent("/victim.txt", "hello")
            impl.openForRead("conn-a", "wh-1", "/victim.txt")
            assertTrue(impl.dehydrate("/victim.txt") is DehydrateResult.Busy, "an open handle keeps dehydrate busy")
            impl.closeHandle("conn-b", "wh-1")
            assertTrue(impl.dehydrate("/victim.txt") is DehydrateResult.Ok, "the close from conn-b released the handle")
            impl.closeHandle("conn-b", "wh-1")
            impl.closeHandle("conn-a", "wh-unknown")
            assertTrue(impl.dehydrate("/victim.txt") is DehydrateResult.Ok, "a repeated and an unknown close change nothing")
        }

    @Test
    fun `two clients with the same handle id on different connections close independently`() =
        runBlocking(Dispatchers.IO) {
            val env = HydrationTestEnv()
            val impl = env.hydration
            env.stateDb.insertHydratedEntry("/victim.txt", localSize = 5)
            env.seedSyncedCacheContent("/victim.txt", "hello")
            env.stateDb.insertHydratedEntry("/second.txt", localSize = 5)
            env.seedSyncedCacheContent("/second.txt", "hello")
            impl.openForRead("conn-a", "wh-1", "/victim.txt")
            impl.openForRead("conn-b", "wh-1", "/second.txt")
            impl.closeHandle("conn-a", "wh-1")
            assertTrue(impl.dehydrate("/victim.txt") is DehydrateResult.Ok, "conn-a's close released conn-a's handle")
            assertTrue(impl.dehydrate("/second.txt") is DehydrateResult.Busy, "conn-b's handle with the same id is untouched")
            impl.closeHandle("conn-b", "wh-1")
            assertTrue(impl.dehydrate("/second.txt") is DehydrateResult.Ok, "conn-b's own close releases its handle")
        }

    @Test
    fun `a repeated close from a client whose handle is gone never releases another client's handle with that id`() =
        runBlocking(Dispatchers.IO) {
            val env = HydrationTestEnv()
            val impl = env.hydration
            env.stateDb.insertHydratedEntry("/victim.txt", localSize = 5)
            env.seedSyncedCacheContent("/victim.txt", "hello")
            env.stateDb.insertHydratedEntry("/second.txt", localSize = 5)
            env.seedSyncedCacheContent("/second.txt", "hello")
            // Client X on conn-a and client Y on conn-b both name their first handle wh-1.
            impl.openForRead("conn-a", "wh-1", "/victim.txt")
            impl.openForRead("conn-b", "wh-1", "/second.txt")
            impl.closeHandle("conn-b", "wh-1")
            // Y closes again, on another pooled connection: X's wh-1 is now the only one, and stays.
            impl.closeHandle("conn-c", "wh-1")
            assertTrue(impl.dehydrate("/victim.txt") is DehydrateResult.Busy, "X's handle is not released by Y's repeated close")
            impl.onConnectionClosed("conn-a")
            assertTrue(impl.dehydrate("/victim.txt") is DehydrateResult.Ok, "X's connection going away releases it")

            // Once nobody holds wh-1 any more, a pooled close of a fresh wh-1 works again.
            env.stateDb.insertHydratedEntry("/third.txt", localSize = 5)
            env.seedSyncedCacheContent("/third.txt", "hello")
            impl.openForRead("conn-a2", "wh-1", "/third.txt")
            impl.closeHandle("conn-d", "wh-1")
            assertTrue(impl.dehydrate("/third.txt") is DehydrateResult.Ok, "the pooled close found the now-unique handle")
        }

    @Test
    fun `an id shared by two clients is a plain pooled id again once both closed their own handles`() =
        runBlocking(Dispatchers.IO) {
            val env = HydrationTestEnv()
            val impl = env.hydration
            for (path in listOf("/first.txt", "/second.txt", "/third.txt")) {
                env.stateDb.insertHydratedEntry(path, localSize = 5)
                env.seedSyncedCacheContent(path, "hello")
            }
            impl.openForRead("conn-a", "wh-1", "/first.txt")
            impl.openForRead("conn-b", "wh-1", "/second.txt")
            // Each closes its own, on its own connection; no connection drops.
            impl.closeHandle("conn-a", "wh-1")
            assertTrue(impl.dehydrate("/second.txt") is DehydrateResult.Busy, "the other client's handle is untouched")
            impl.closeHandle("conn-b", "wh-1")
            assertTrue(impl.dehydrate("/second.txt") is DehydrateResult.Ok)

            // Nobody holds wh-1 any more, so the id is no longer marked: a pooled close of a fresh wh-1 works.
            impl.openForRead("conn-c", "wh-1", "/third.txt")
            impl.closeHandle("conn-d", "wh-1")
            assertTrue(impl.dehydrate("/third.txt") is DehydrateResult.Ok, "the pooled close found the now-unique handle")
        }
}
