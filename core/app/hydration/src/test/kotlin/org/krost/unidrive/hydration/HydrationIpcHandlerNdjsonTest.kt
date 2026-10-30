package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every line the hydration handler writes is one NDJSON line whatever a name or a provider's message carries:
 * NEL (U+0085), LINE SEPARATOR (U+2028) and PARAGRAPH SEPARATOR (U+2029) are escaped, like the rest of the socket protocol
 * (gkrost/unidrive-cli#222 item 1). That the escaped text reads back is pinned where the escaper lives (`NdjsonTest` in
 * `:app:core`); this pins that every string of the handler goes through it.
 */
class HydrationIpcHandlerNdjsonTest {
    private val awkward = "a\u0085b c d\ne\rf\tg\"h\\i\u0001j\u007fk"

    private fun assertOneCleanLine(line: String) {
        val bad = line.filter { it < ' ' || it == '\u0085' || it == ' ' || it == ' ' }
        assertTrue(bad.isEmpty(), "raw line-breaking/control characters ${bad.map { it.code }} in: $line")
        assertTrue(line.contains("a\\u0085b\\u2028c\\u2029d"), "the separators are written as escapes: $line")
    }

    @Test
    fun `event lines carry no raw line ender`() {
        val events = listOf(
            HydrationEvent.Hydrating(awkward),
            HydrationEvent.Hydrated(awkward, 3),
            HydrationEvent.Dehydrated(awkward),
            HydrationEvent.Skipped(awkward),
            HydrationEvent.Queued(awkward),
            HydrationEvent.Failed(awkward, HydrationError.Generic(awkward), retryScheduled = true),
            HydrationEvent.Completed(awkward, awkward, HydrationEvent.Completed.Direction.UPLOAD, ok = false, error = HydrationError.Generic(awkward)),
            HydrationEvent.Uploading(awkward, awkward, 1, 2),
            HydrationEvent.Paused(awkward, 1, auto = false),
            HydrationEvent.Resumed(awkward),
            HydrationEvent.ViewInvalidated(listOf(awkward), moved = listOf(HydrationEvent.ViewInvalidated.Moved(awkward, awkward))),
        )
        for (event in events) {
            assertOneCleanLine(serialiseHydrationEvent(event))
        }
    }

    @Test
    fun `a listing of names with line enders is one line`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/a\u0085b c d", remoteSize = 1)

        val reply = HydrationIpcHandler(env.hydration).handle("c1", """{"verb":"hydration.list","prefix":"/"}""")

        assertOneCleanLine(reply)
    }
}
