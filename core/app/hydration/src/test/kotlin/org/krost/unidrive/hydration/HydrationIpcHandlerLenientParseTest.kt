package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the handler's own request scanner accepts that a JSON parser does not (gkrost/unidrive-cli#223 item 1). In production a
 * line reaches the handler only after `IpcServer.parseVerb` has parsed it with kotlinx as one JSON object with exactly one
 * `verb`, so most of these lines never get this far; they are here because [HydrationIpcHandler.handle] is public and the scanner
 * was never tested on them. The request-field cases that a client really sends are in [HydrationIpcHandlerWireTest].
 */
class HydrationIpcHandlerLenientParseTest {
    private suspend fun mkdirOf(line: String): Pair<String, List<String>> {
        val r = ScriptedHydration()
        val reply = HydrationIpcHandler(r).handle("c1", line)
        return reply to r.calls.filter { it.verb == "mkdir" }.map { it.args.single() as String }
    }

    private val ok = """{"ok":true}"""
    private val missingVerb = """{"ok":false,"error":"missing_verb"}"""

    // Duplicate member names. The client builds its requests from a dictionary, so it never sends one; the scanner keeps the first,
    // kotlinx (and so IpcServer.parseVerb, which refuses a repeated verb for that reason) the last.
    @Test
    fun `a member named twice keeps the first`() = runTest {
        assertEquals(ok to listOf("/first"), mkdirOf("""{"verb":"hydration.mkdir","path":"/first","path":"/second"}"""))
    }

    // A bare literal read by its start only: `truex` is true.
    @Test
    fun `a flag spelled truex is true`() = runTest {
        val r = ScriptedHydration()
        HydrationIpcHandler(r).handle("c1", """{"verb":"hydration.unlink","path":"/a","permanent":truex}""")
        assertEquals(listOf(HydrationCall("unlink", listOf("/a", true))), r.calls)
    }

    // Whitespace is Char.isWhitespace(): NBSP and the other Unicode spaces count, JSON's four do not include them.
    @Test
    fun `no-break space between tokens is whitespace`() = runTest {
        assertEquals(ok to listOf("/a"), mkdirOf(" { \"verb\" : \"hydration.mkdir\" , \"path\":\"/a\"}"))
    }

    // Raw control characters in a string: JSON forbids them below U+0020, the scanner copies them.
    @Test
    fun `a raw control character in a string is kept`() = runTest {
        val r = ScriptedHydration()
        HydrationIpcHandler(r).handle("c1", "{\"verb\":\"hydration.close_handle\",\"handle_id\":\"a\tb\u0001c\"}")
        assertEquals(listOf(HydrationCall("close_handle", listOf("c1", "a\tb\u0001c"))), r.calls)
    }

    // The scanner stops at the member it wants; what follows it is never read.
    @Test
    fun `text after the member that is read is not read`() = runTest {
        assertEquals(ok to listOf("/a"), mkdirOf("""{"verb":"hydration.mkdir","path":"/a"} trailing"""))
        assertEquals(ok to listOf("/a"), mkdirOf("""{"verb":"hydration.mkdir","path":"/a","bad":"\x"}"""))
        assertEquals(ok to listOf("/a"), mkdirOf("""{"verb":"hydration.mkdir","path":"/a",}"""))
        assertEquals(ok to listOf("/a"), mkdirOf("""{"verb":"hydration.mkdir","path":"/a""""))
    }

    // A member the scanner has to step over is read only as far as its end is found.
    @Test
    fun `a value that is not JSON is stepped over`() = runTest {
        assertEquals(ok to listOf("/a"), mkdirOf("""{"extra":nonsense,"verb":"hydration.mkdir","path":"/a"}"""))
    }

    // A bad escape in the field itself is a missing field, today and with a parser.
    @Test
    fun `a bad escape in a field is a missing field`() = runTest {
        assertEquals("""{"ok":false,"error":"missing_path"}""" to emptyList(), mkdirOf("""{"verb":"hydration.mkdir","path":"/a\x"}"""))
        assertEquals("""{"ok":false,"error":"missing_path"}""" to emptyList(), mkdirOf("""{"verb":"hydration.mkdir","path":"/a\u00zz"}"""))
    }

    @Test
    fun `a line without a closing quote has no verb`() = runTest {
        assertEquals(missingVerb to emptyList(), mkdirOf("""{"verb":"hydration.mkdir"""))
    }
}
