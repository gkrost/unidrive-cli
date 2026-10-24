package org.krost.unidrive.hydration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Write-handle ids the daemon issues: an `open_write` or `create` without `handle_id` gets an id that is unique for the
 * daemon run, in the reply. No client can name such an id itself, so a close from one client never releases the handle
 * of another. A client that sends its own id is served as before.
 */
class HydrationIssuedHandleIdTest {
    // The prefix of a daemon-issued id, spelled out: it is wire contract.
    private val issuedPrefix = "dh-"

    private fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun HydrationTestEnv.seedFile(path: String) {
        stateDb.insertUnhydratedEntry(path, remoteSize = 5)
        syncEngine.seedCacheContent(path, "the bytes")
    }

    private fun HydrationTestEnv.openWrite(path: String) =
        """{"verb":"hydration.open_write","path":"$path","cache_path":${quoted(syncEngine.resolveCachePath(path).toString())}}"""

    private fun HydrationTestEnv.openSets(): ConcurrentMap<String, Map<String, String>> {
        @Suppress("UNCHECKED_CAST")
        return HydrationImpl::class.java
            .getDeclaredField("openSets")
            .apply { isAccessible = true }
            .get(hydration) as ConcurrentMap<String, Map<String, String>>
    }

    private fun HydrationTestEnv.heldIds(): Map<String, Set<String>> =
        openSets().mapValues { it.value.keys.toSet() }

    private fun HydrationTestEnv.openPaths(): Set<String> =
        openSets().values.flatMap { it.values }.toSet()

    private fun issuedId(reply: String): String {
        assertTrue(reply.startsWith("""{"ok":true"""), "reply: $reply")
        return checkNotNull(Regex(""""handle_id":"([^"]*)"""").find(reply)) { "no handle_id in $reply" }.groupValues[1]
    }

    @Test
    fun `open_write without handle_id answers a daemon id and registers the handle under it`() =
        runTest {
            val env = HydrationTestEnv()
            env.syncEngine.setUploadGate(CompletableDeferred())
            env.seedFile("/a.txt")
            env.seedFile("/b.txt")
            val handler = HydrationIpcHandler(env.hydration)

            val first = issuedId(handler.handle("conn-a", env.openWrite("/a.txt")))
            val second = issuedId(handler.handle("conn-a", env.openWrite("/b.txt")))

            assertTrue(first.startsWith(issuedPrefix), "a daemon id: $first")
            assertNotEquals(first, second, "every open_write gets its own id")
            assertEquals(setOf(first, second), env.heldIds()["conn-a"], "the handles are registered under the daemon ids")
        }

    @Test
    fun `a retried open_write without an id reuses the unanswered daemon id and one close releases the path`() =
        runTest {
            // Invariant: an open_write whose reply has not been returned yet is still the only copy of its daemon id.
            // A retry on the same connection for the same path, with no id, is that same open — not a second pin.
            // Closing the id releases the path. The queue is held full by other paths, so this open is registered
            // and waiting to be answered; the client has not been given the id.
            val env = HydrationTestEnv(
                recoveryUploadScope = this,
                uploadQueueDepth = 1,
                maxUploadAttempts = 1,
                uploadRetryDelaysMs = emptyList(),
            )
            val gate = CompletableDeferred<Unit>()
            env.providerForTest.uploadGate = gate
            val handler = HydrationIpcHandler(env.hydration)
            // Four uploads park on the gate and hold every transfer permit. The fifth takes the only waiting
            // slot and keeps it, because its worker cannot get a permit. /a.txt then waits in that queue.
            for (i in 1..5) {
                val path = "/hold-$i.txt"
                env.stateDb.insertCreatedRow(path)
                env.syncEngine.seedCacheContent(path, "held")
                issuedId(handler.handle("conn-hold", env.openWrite(path)))
            }
            env.seedFile("/a.txt")
            val blocked = async { handler.handle("conn-a", env.openWrite("/a.txt")) }
            try {
                while ("/a.txt" !in env.openPaths()) yield()
                assertFalse(blocked.isCompleted, "the first reply has not been returned, so the client has not been given the id")
                val pinned = env.openSets()["conn-a"]!!.filterValues { it == "/a.txt" }.keys
                assertEquals(1, pinned.size, "the waiting open is the only pin for the path")

                val retry = issuedId(handler.handle("conn-a", env.openWrite("/a.txt")))

                assertEquals(pinned, setOf(retry), "the retry returns the waiting id and does not register another")
                handler.handle("conn-a", """{"verb":"hydration.close_handle","handle_id":"$retry"}""")
                assertFalse("/a.txt" in env.openPaths(), "close of that one id drops the path")
                assertEquals(
                    """{"ok":false,"error":"busy"}""",
                    handler.handle("conn-a", """{"verb":"hydration.dehydrate","path":"/a.txt"}""").trim(),
                    "the edit-base mark still protects the cache after the handle closes",
                )
            } finally {
                blocked.cancel()
                blocked.join()
                gate.complete(Unit)
                advanceUntilIdle()
            }
        }

    @Test
    fun `an open_write that already answered its id is not reused by the next one`() =
        runTest {
            // Invariant: reuse is only for the id the client has not been given. Once the reply was returned, another
            // open_write without an id is a new open (a later save), with its own id.
            val env = HydrationTestEnv()
            env.syncEngine.setUploadGate(CompletableDeferred())
            env.seedFile("/a.txt")
            val handler = HydrationIpcHandler(env.hydration)

            val answered = issuedId(handler.handle("conn-a", env.openWrite("/a.txt")))
            val next = issuedId(handler.handle("conn-a", env.openWrite("/a.txt")))

            assertNotEquals(answered, next, "the answered open is not the next one")
            assertEquals(setOf(answered, next), env.heldIds()["conn-a"])
        }

    @Test
    fun `a repeated close from another client's connection never releases a daemon-issued handle`() =
        runTest {
            val env = HydrationTestEnv()
            env.syncEngine.setUploadGate(CompletableDeferred())
            env.seedFile("/x.txt")
            env.seedFile("/y.txt")
            env.seedFile("/old.txt")
            val handler = HydrationIpcHandler(env.hydration)
            // Client X on conn-a and client Y on conn-b both ask the daemon for an id; an old client on conn-e names its own.
            val x = issuedId(handler.handle("conn-a", env.openWrite("/x.txt")))
            val y = issuedId(handler.handle("conn-b", env.openWrite("/y.txt")))
            handler.handle(
                "conn-e",
                """{"verb":"hydration.open_write","handle_id":"wh-1","path":"/old.txt","cache_path":${
                    quoted(env.syncEngine.resolveCachePath("/old.txt").toString())
                }}""",
            )

            // X closes its handle, then closes it again on other pooled connections; the old client closes wh-1 twice.
            for (conn in listOf("conn-a", "conn-c", "conn-b")) handler.handle(conn, """{"verb":"hydration.close_handle","handle_id":"$x"}""")
            for (conn in listOf("conn-e", "conn-b")) handler.handle(conn, """{"verb":"hydration.close_handle","handle_id":"wh-1"}""")

            assertEquals(setOf(y), env.heldIds()["conn-b"], "Y's handle is untouched by every other close")
            assertFalse(env.heldIds().values.any { x in it || "wh-1" in it }, "X's and the old client's handles are released")
            // Y's own close, sent as a pooled request on another connection, releases it: the id names exactly one handle.
            handler.handle("conn-d", """{"verb":"hydration.close_handle","handle_id":"$y"}""")
            assertFalse(env.heldIds().values.any { y in it }, "Y's close released Y's handle")
        }

    @Test
    fun `a client cannot name a handle with the daemon's prefix`() =
        runTest {
            val env = HydrationTestEnv()
            env.syncEngine.setUploadGate(CompletableDeferred())
            env.seedFile("/a.txt")
            val handler = HydrationIpcHandler(env.hydration)
            val issued = issuedId(handler.handle("conn-a", env.openWrite("/a.txt")))
            val cache = quoted(env.syncEngine.resolveCachePath("/a.txt").toString())

            val replies =
                listOf(
                    """{"verb":"hydration.open_write","handle_id":"$issued","path":"/a.txt","cache_path":$cache}""",
                    """{"verb":"hydration.open_read","handle_id":"$issued","path":"/a.txt"}""",
                    """{"verb":"hydration.open_write_begin","handle_id":"$issued","path":"/a.txt"}""",
                    """{"verb":"hydration.create","handle_id":"$issued","path":"/new.txt"}""",
                ).map { handler.handle("conn-b", it).trim() }

            assertEquals(List(replies.size) { """{"ok":false,"error":"invalid_handle_id"}""" }, replies)
            assertEquals(null, env.heldIds()["conn-b"], "nothing was registered on conn-b")
            assertEquals(null, env.stateDb.statusOf("/new.txt"), "create made no row")
        }

    @Test
    fun `create without handle_id answers a daemon id`() =
        runTest {
            val env = HydrationTestEnv()
            val handler = HydrationIpcHandler(env.hydration)

            val id = issuedId(handler.handle("conn-a", """{"verb":"hydration.create","path":"/fresh.txt"}"""))

            assertTrue(id.startsWith(issuedPrefix), "a daemon id: $id")
            assertEquals(setOf(id), env.heldIds()["conn-a"])
        }

    @Test
    fun `an old client that names its own write handle is served as before`() =
        runTest {
            val env = HydrationTestEnv()
            env.syncEngine.setUploadGate(CompletableDeferred())
            env.seedFile("/a.txt")
            val handler = HydrationIpcHandler(env.hydration)
            val cache = env.syncEngine.resolveCachePath("/a.txt").toString()

            val reply =
                handler.handle(
                    "conn-a",
                    """{"verb":"hydration.open_write","handle_id":"wh-1","path":"/a.txt","cache_path":${quoted(cache)}}""",
                )

            assertEquals("""{"ok":true,"cache_path":${quoted(cache)}}""", reply.trim(), "the reply is unchanged")
            assertEquals(setOf("wh-1"), env.heldIds()["conn-a"])
            handler.handle("conn-b", """{"verb":"hydration.close_handle","handle_id":"wh-1"}""")
            assertEquals(emptySet(), env.heldIds()["conn-a"].orEmpty(), "the pooled close of a unique client id still releases it")
        }
}
