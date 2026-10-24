package org.krost.unidrive.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.ViewKind
import org.krost.unidrive.ViewList
import org.krost.unidrive.ViewRef
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The provider's references of a view, kept in memory: single flight, a TTL, stale while revalidate, a bound on the
 * wait for the first fill, and a failure that keeps what was there. All in virtual time, against a scripted provider.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewCacheTest {
    private val a = ViewRef("a", false)
    private val b = ViewRef("b", false)
    private val c = ViewRef("c", false)

    private val provider = FakeViewProvider()
    private val changed = CopyOnWriteArrayList<ViewKind>()

    private fun TestScope.cache(
        ttlMs: Long = 60_000,
        boundMs: Long = 5_000,
        limit: Int = 10,
    ) = ViewCache(
        scope = backgroundScope,
        fetch = provider::listView,
        limitOf = { limit },
        nowMs = { currentTime },
        ttlMs = ttlMs,
        waitBoundMs = boundMs,
        onChanged = { changed += it },
    )

    private val recents = ViewKind.RECENTS

    @Test
    fun `the first question fetches once, with the limit of the view, and answers the list`() =
        runTest {
            provider.serve(recents, a, b)
            val cache = cache(limit = 7)

            val view = cache.get(recents)

            assertEquals(listOf(a, b), view.refs)
            assertEquals(ViewState.OK, view.state)
            assertEquals(currentTime, view.asOfMs)
            assertEquals(listOf(recents to 7), provider.viewCalls.toList())
            assertEquals(listOf(recents), changed.toList(), "a list that was not there before is a change")
        }

    @Test
    fun `within the TTL the list is answered without another request`() =
        runTest {
            provider.serve(recents, a)
            val cache = cache()
            cache.get(recents)

            repeat(100) {
                advanceTimeBy(500)
                assertEquals(listOf(a), cache.get(recents).refs)
            }

            assertEquals(1, provider.viewCalls.size, "100 questions in 50 s, one request")
        }

    @Test
    fun `nothing is requested while nobody asks`() =
        runTest {
            provider.serve(recents, a)
            val cache = cache()
            cache.get(recents)

            advanceTimeBy(3_600_000)
            runCurrent()

            assertEquals(1, provider.viewCalls.size, "an hour of silence costs nothing")
            assertEquals(listOf(a), cache.peek(recents).refs)
        }

    @Test
    fun `a clock set back does not hold the next refresh back`() =
        runTest {
            provider.serve(recents, a)
            var now = 1_000_000L
            val cache = ViewCache(backgroundScope, provider::listView, { 10 }, { now }, onChanged = { changed += it })
            cache.get(recents)

            now = 5_000L
            provider.serve(recents, b)
            assertEquals(listOf(a), cache.get(recents).refs, "answered from the list before, and the refresh is started")
            runCurrent()

            assertEquals(2, provider.viewCalls.size, "the attempt lay in the future: it counts as old")
            assertEquals(listOf(b), cache.peek(recents).refs)
        }

    @Test
    fun `peek never starts a request`() =
        runTest {
            provider.serve(recents, a)
            val cache = cache()

            val view = cache.peek(recents)
            runCurrent()

            assertTrue(view.refs.isEmpty())
            assertEquals(ViewState.UNAVAILABLE, view.state)
            assertNull(view.asOfMs)
            assertEquals(0, provider.viewCalls.size)
        }

    @Test
    fun `a list older than the TTL is answered at once while one request refreshes it behind`() =
        runTest {
            provider.serve(recents, a)
            val cache = cache()
            cache.get(recents)
            advanceTimeBy(61_000)
            provider.serve(recents, b, a)
            provider.latencyMs = 3_000

            val before = currentTime
            val old = cache.get(recents)

            assertEquals(listOf(a), old.refs, "the list that was there")
            assertEquals(before, currentTime, "and no waiting for the network")
            repeat(20) { cache.get(recents) }
            runCurrent()
            assertEquals(2, provider.viewCalls.size, "20 questions during the refresh share it")

            advanceTimeBy(3_000)
            runCurrent()

            val now = cache.peek(recents)
            assertEquals(listOf(b, a), now.refs)
            assertEquals(ViewState.OK, now.state)
            assertEquals(listOf(recents, recents), changed.toList(), "the first fill and the change, once each")
            assertEquals(now.generation, old.generation + 1)
        }

    @Test
    fun `a refresh that finds the same list changes nothing`() =
        runTest {
            provider.serve(recents, a, b)
            val cache = cache()
            val first = cache.get(recents)
            advanceTimeBy(61_000)

            cache.get(recents)
            runCurrent()

            val after = cache.peek(recents)
            assertEquals(2, provider.viewCalls.size)
            assertEquals(first.generation, after.generation)
            assertEquals(1, changed.size, "no event for a refresh that changed nothing")
            assertEquals(currentTime, after.asOfMs, "but the list is as of now")
        }

    @Test
    fun `questions that arrive together before the first list share one request`() =
        runTest {
            provider.serve(recents, a)
            provider.latencyMs = 1_000
            val cache = cache()

            val answers = (1..8).map { async { cache.get(recents) } }.awaitAll()

            assertEquals(1, provider.viewCalls.size)
            assertTrue(answers.all { it.refs == listOf(a) && it.state == ViewState.OK })
            assertEquals(1_000, currentTime)
        }

    @Test
    fun `the views are independent of each other`() =
        runTest {
            provider.serve(recents, a)
            provider.serve(ViewKind.FAVORITES, b)
            provider.latencyMs = 1_000
            val cache = cache()

            val both = listOf(async { cache.get(recents) }, async { cache.get(ViewKind.FAVORITES) }).awaitAll()

            assertEquals(listOf(listOf(a), listOf(b)), both.map { it.refs })
            assertEquals(setOf(recents, ViewKind.FAVORITES), provider.viewCalls.map { it.first }.toSet())
            assertEquals(2, provider.viewCalls.size)
            assertEquals(1_000, currentTime, "in parallel")
        }

    @Test
    fun `a first fill that takes longer than the bound answers an empty unavailable view and goes on`() =
        runTest {
            provider.serve(recents, a, b)
            provider.latencyMs = 8_000
            val cache = cache()

            val first = cache.get(recents)

            assertEquals(5_000, currentTime, "not a millisecond longer than the bound")
            assertTrue(first.refs.isEmpty())
            assertEquals(ViewState.UNAVAILABLE, first.state)
            assertEquals(emptyList(), changed.toList())

            advanceTimeBy(3_000)
            runCurrent()

            assertEquals(listOf(recents), changed.toList(), "the fill landed and said so")
            val later = cache.get(recents)
            assertEquals(listOf(a, b), later.refs)
            assertEquals(ViewState.OK, later.state)
            assertEquals(1, provider.viewCalls.size, "the same request, not a new one")
        }

    @Test
    fun `a caller that gives up waiting does not abort the fill`() =
        runTest {
            provider.serve(recents, a)
            provider.latencyMs = 3_000
            val cache = cache()

            val caller = launch { cache.get(recents) }
            runCurrent()
            caller.cancel()
            advanceTimeBy(3_000)
            runCurrent()

            assertEquals(listOf(a), cache.peek(recents).refs)
            assertEquals(1, provider.viewCalls.size)
        }

    @Test
    fun `a refresh that fails keeps the list before it and says stale, and the next try is a TTL away`() =
        runTest {
            provider.serve(recents, a)
            val cache = cache()
            val first = cache.get(recents)
            advanceTimeBy(61_000)
            provider.failure = IOException("connection reset")

            assertEquals(listOf(a), cache.get(recents).refs)
            runCurrent()

            val stale = cache.peek(recents)
            assertEquals(listOf(a), stale.refs, "the previous list is served")
            assertEquals(ViewState.STALE, stale.state)
            assertEquals(first.asOfMs, stale.asOfMs, "and it says how old it is")
            assertEquals(first.generation, stale.generation)
            assertEquals(1, changed.size)

            repeat(50) {
                advanceTimeBy(1_000)
                cache.get(recents)
            }
            runCurrent()
            assertEquals(2, provider.viewCalls.size, "a failing server is asked once per TTL, not once per question")

            advanceTimeBy(20_000)
            provider.failure = null
            provider.serve(recents, c)
            cache.get(recents)
            runCurrent()
            val recovered = cache.peek(recents)
            assertEquals(listOf(c), recovered.refs)
            assertEquals(ViewState.OK, recovered.state)
        }

    @Test
    fun `a view that never succeeded is unavailable and empty, and answers at once after the first failure`() =
        runTest {
            provider.failure = IOException("no route")
            val cache = cache()

            val first = cache.get(recents)
            val second = cache.get(recents)

            assertTrue(first.refs.isEmpty() && second.refs.isEmpty())
            assertEquals(ViewState.UNAVAILABLE, first.state)
            assertNull(first.asOfMs)
            assertEquals(1, provider.viewCalls.size, "within the TTL the failure stands")
            assertEquals(emptyList(), changed.toList())
        }

    @Test
    fun `a provider without the view makes it unavailable, not an error`() =
        runTest {
            val cache = cache()

            val view = cache.get(recents)

            assertEquals(ViewState.UNAVAILABLE, view.state)
            assertTrue(view.refs.isEmpty())
            repeat(10) { cache.get(recents) }
            assertEquals(1, provider.viewCalls.size)

            advanceTimeBy(61_000)
            cache.get(recents)
            runCurrent()
            assertEquals(2, provider.viewCalls.size, "asked again once per TTL")
        }

    @Test
    fun `a view that was there and is not any more is emptied and the change is reported`() =
        runTest {
            provider.serve(recents, a, b)
            val cache = cache()
            cache.get(recents)
            advanceTimeBy(61_000)
            provider.answers.remove(recents)

            cache.get(recents)
            runCurrent()

            val gone = cache.peek(recents)
            assertTrue(gone.refs.isEmpty())
            assertEquals(ViewState.UNAVAILABLE, gone.state)
            assertEquals(listOf(recents, recents), changed.toList())
        }

    @Test
    fun `a list the provider answers empty is a list, not an unavailable view`() =
        runTest {
            provider.answers[recents] = CapabilityResult.Success(emptyList())
            val cache = cache()

            val view = cache.get(recents)

            assertEquals(ViewState.OK, view.state)
            assertTrue(view.refs.isEmpty())
            assertEquals(currentTime, view.asOfMs)
            assertEquals(emptyList(), changed.toList(), "an empty view that stays empty is no change")
        }

    @Test
    fun `a list longer than the limit is cut to it and the snapshot says it was cut`() =
        runTest {
            provider.serve(recents, a, b, c)
            val cache = cache(limit = 2)

            val view = cache.get(recents)

            assertEquals(listOf(a, b), view.refs)
            assertTrue(view.truncated)
            assertTrue(cache.peek(recents).truncated)
        }

    @Test
    fun `a cut the provider reports is kept until a whole list lands`() =
        runTest {
            provider.answers[recents] = CapabilityResult.Success(ViewList(listOf(a, b), truncated = true))
            val cache = cache(ttlMs = 1_000)

            assertTrue(cache.get(recents).truncated, "the provider cut it")
            advanceTimeBy(2_000)
            provider.failure = IOException("connection reset")
            cache.get(recents)
            runCurrent()
            assertTrue(cache.peek(recents).truncated, "a failed refresh serves the cut list before it")
            provider.failure = null
            provider.serve(recents, a, b)
            advanceTimeBy(2_000)
            cache.get(recents)
            runCurrent()

            assertFalse(cache.peek(recents).truncated, "a whole list")
            assertEquals(ViewState.OK, cache.peek(recents).state)
        }

    @Test
    fun `a whole list, an unavailable view and no list yet are not cut`() =
        runTest {
            provider.serve(recents, a, b)
            val cache = cache(limit = 2)

            assertFalse(cache.peek(recents).truncated, "nothing fetched yet")
            assertFalse(cache.get(recents).truncated, "exactly the limit is not a cut")
            assertFalse(cache.get(ViewKind.FAVORITES).truncated, "unavailable")
        }

    // ---- pages: a front-end's own list asks for more than the folder shows ----------------------------------------

    private val many = (1..120).map { ViewRef("r%03d".format(it), false) }

    private fun TestScope.pagedCache(ttlMs: Long = 60_000) =
        ViewCache(
            scope = backgroundScope,
            fetch = provider::listView,
            limitOf = { 50 },
            pageCapOf = { 1_000 },
            nowMs = { currentTime },
            ttlMs = ttlMs,
            onChanged = { changed += it },
        )

    @Test
    fun `pages of fifty over 120 references end after the third, one request per distinct size`() =
        runTest {
            provider.honoursLimit = true
            provider.serve(recents, *many.toTypedArray())
            val cache = pagedCache()

            val first = cache.page(recents, offset = 0, limit = 50)
            val second = cache.page(recents, offset = 50, limit = 50)
            val third = cache.page(recents, offset = 100, limit = 50)

            assertEquals(many.subList(0, 50), first.refs)
            assertFalse(first.end, "the folder's fifty are full: there may be more")
            assertEquals(many.subList(50, 100), second.refs)
            assertFalse(second.end)
            assertEquals(many.subList(100, 120), third.refs)
            assertTrue(third.end, "the provider had fewer than asked for: the list ends here")
            assertEquals(ViewState.OK, third.state)
            assertEquals(listOf(recents to 50, recents to 100, recents to 150), provider.viewCalls.toList())

            // The same pages again within the TTL: answered from memory.
            cache.page(recents, offset = 0, limit = 50)
            cache.page(recents, offset = 50, limit = 50)
            cache.page(recents, offset = 100, limit = 50)
            cache.page(recents, offset = 60, limit = 20)
            assertEquals(3, provider.viewCalls.size, "no request for a size already asked within the TTL")
        }

    @Test
    fun `a page does not change the folder's list and sends no change`() =
        runTest {
            provider.honoursLimit = true
            provider.serve(recents, *many.toTypedArray())
            val cache = pagedCache()
            cache.get(recents)
            changed.clear()

            cache.page(recents, offset = 50, limit = 50)

            assertEquals(many.subList(0, 50), cache.peek(recents).refs, "the folder keeps its fifty")
            assertEquals(emptyList(), changed.toList(), "a deeper fetch is no change of the folder")
        }

    @Test
    fun `pages asked for together share one request`() =
        runTest {
            provider.honoursLimit = true
            provider.latencyMs = 100
            provider.serve(recents, *many.toTypedArray())
            val cache = pagedCache()

            val pages = (1..10).map { async { cache.page(recents, offset = 50, limit = 50) } }.awaitAll()

            assertTrue(pages.all { it.refs == many.subList(50, 100) })
            assertEquals(listOf(recents to 100), provider.viewCalls.toList(), "one request for ten questions")
        }

    @Test
    fun `odd page sizes are rounded up to whole steps, so they cannot make a request each`() =
        runTest {
            provider.honoursLimit = true
            provider.serve(recents, *many.toTypedArray())
            val cache = pagedCache()

            for (offset in 51..90) cache.page(recents, offset = offset, limit = 7)

            assertEquals(listOf(recents to 100), provider.viewCalls.toList(), "every size up to 100 is one fetch of 100")
        }

    @Test
    fun `a deeper list older than the TTL is answered at once and refreshed behind`() =
        runTest {
            provider.honoursLimit = true
            provider.serve(recents, *many.toTypedArray())
            val cache = pagedCache(ttlMs = 1_000)
            cache.page(recents, offset = 50, limit = 50)
            advanceTimeBy(2_000)
            provider.latencyMs = 10_000

            val stale = cache.page(recents, offset = 50, limit = 50)

            assertEquals(many.subList(50, 100), stale.refs, "answered without waiting")
            runCurrent()
            assertEquals(listOf(recents to 100, recents to 100), provider.viewCalls.toList(), "and refreshed behind")
        }

    @Test
    fun `a failed deeper fetch is not retried within the TTL`() =
        runTest {
            provider.honoursLimit = true
            provider.serve(recents, *many.toTypedArray())
            provider.failure = IOException("connection reset")
            val cache = pagedCache()

            val failed = cache.page(recents, offset = 50, limit = 50)
            cache.page(recents, offset = 50, limit = 50)

            assertEquals(emptyList(), failed.refs)
            assertTrue(failed.end)
            assertEquals(ViewState.UNAVAILABLE, failed.state)
            assertEquals(1, provider.viewCalls.size)
        }

    @Test
    fun `nothing lies beyond the cap and a provider without the view ends at once`() =
        runTest {
            provider.honoursLimit = true
            provider.serve(recents, *many.toTypedArray())
            val cache = pagedCache()

            val beyond = cache.page(recents, offset = 1_000, limit = 50)
            assertTrue(beyond.end)
            assertEquals(emptyList(), beyond.refs)
            assertEquals(0, provider.viewCalls.size, "a page past the cap asks nothing")

            val none = cache.page(ViewKind.FAVORITES, offset = 0, limit = 50)
            assertTrue(none.end)
            assertEquals(ViewState.UNAVAILABLE, none.state)
        }
}
