package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where the handler's request reader is a JSON parser's, not the hand-written scanner it replaced (gkrost/unidrive-cli#223 item 1).
 * In production a line reaches the handler only after `IpcServer.parseVerb` has parsed it with kotlinx as one JSON object with exactly
 * one `verb`, so most of these lines never get this far; they are here because [HydrationIpcHandler.handle] is public. The scanner
 * kept the first of two members of one name, read `truex` as true, took NBSP for whitespace and stopped reading at the member it
 * wanted, so it answered lines no parser accepts. The request fields a client really sends are in [HydrationIpcHandlerWireTest].
 */
class HydrationIpcHandlerLenientParseTest {
    private suspend fun mkdirOf(line: String): Pair<String, List<String>> {
        val r = ScriptedHydration()
        val reply = HydrationIpcHandler(r).handle("c1", line)
        return reply to r.calls.filter { it.verb == "mkdir" }.map { it.args.single() as String }
    }

    private val ok = """{"ok":true}"""
    private val missingVerb = """{"ok":false,"error":"missing_verb"}"""

    // The client builds its requests from a dictionary, so it never sends a member twice. kotlinx keeps the last, and so does
    // IpcServer.parseVerb (which refuses a repeated verb so that both see the same one).
    @Test
    fun `a member named twice keeps the last`() = runTest {
        assertEquals(ok to listOf("/second"), mkdirOf("""{"verb":"hydration.mkdir","path":"/first","path":"/second"}"""))
    }

    // kotlinx takes a bare word it does not know for a literal and keeps its text, so `truex` parses; as a flag it is not `true`
    // (the scanner read it by its first four characters). IpcServer.parseVerb accepts the same line.
    @Test
    fun `a flag spelled truex is not true`() = runTest {
        val r = ScriptedHydration()
        HydrationIpcHandler(r).handle("c1", """{"verb":"hydration.unlink","path":"/a","permanent":truex}""")
        assertEquals(listOf(HydrationCall("unlink", listOf("/a", false))), r.calls)
        val r2 = ScriptedHydration()
        HydrationIpcHandler(r2).handle("c1", """{"verb":"hydration.unlink","path":"/a","permanent":True}""")
        assertEquals(listOf(HydrationCall("unlink", listOf("/a", false))), r2.calls)
    }

    // JSON's whitespace is space, tab, CR and LF.
    @Test
    fun `no-break space between tokens is no request`() = runTest {
        assertEquals(missingVerb to emptyList(), mkdirOf(" {\"verb\":\"hydration.mkdir\",\"path\":\"/a\"}"))
        assertEquals(missingVerb to emptyList(), mkdirOf("{\"verb\":\"hydration.mkdir\", \"path\":\"/a\"}"))
    }

    // JSON forbids a raw control character below U+0020 in a string, but kotlinx reads it, and IpcServer.parseVerb is kotlinx: the
    // line is one the daemon has always accepted, with the character kept.
    @Test
    fun `a raw control character in a string is kept`() = runTest {
        val r = ScriptedHydration()
        HydrationIpcHandler(r).handle("c1", "{\"verb\":\"hydration.close_handle\",\"handle_id\":\"a\tb\u0001c\"}")
        assertEquals(listOf(HydrationCall("close_handle", listOf("c1", "a\tb\u0001c"))), r.calls)
    }

    // The whole line is read, not only as far as the wanted member.
    @Test
    fun `a line that is not JSON all the way is no request`() = runTest {
        assertEquals(missingVerb to emptyList(), mkdirOf("""{"verb":"hydration.mkdir","path":"/a"} trailing"""))
        assertEquals(missingVerb to emptyList(), mkdirOf("""{"verb":"hydration.mkdir","path":"/a","bad":"\x"}"""))
        assertEquals(missingVerb to emptyList(), mkdirOf("""{"verb":"hydration.mkdir","path":"/a",}"""))
        assertEquals(missingVerb to emptyList(), mkdirOf("""{"verb":"hydration.mkdir","path":"/a""""))
        assertEquals(missingVerb to emptyList(), mkdirOf("""{"verb":"hydration.mkdir"""))
    }

    // A bare word is a literal to kotlinx, as it was a value to step over for the scanner.
    @Test
    fun `a bare word as a value of a member that is not read is stepped over`() = runTest {
        assertEquals(ok to listOf("/a"), mkdirOf("""{"extra":nonsense,"verb":"hydration.mkdir","path":"/a"}"""))
    }

    // The parser recurses once per level; a request is never nested more than a few levels (IpcServer refuses 64 before a handler
    // sees the line, and the handler holds the same limit for a direct caller).
    @Test
    fun `a line nested without end is no request`() = runTest {
        assertEquals(missingVerb to emptyList(), mkdirOf("[".repeat(100_000)))
        assertEquals(missingVerb to emptyList(), mkdirOf("""{"verb":"hydration.mkdir","path":"/a","n":""" + "[".repeat(100_000) + "}"))
        val deepButAllowed = "[".repeat(60) + "]".repeat(60)
        assertEquals(ok to listOf("/a"), mkdirOf("""{"verb":"hydration.mkdir","path":"/a","n":$deepButAllowed}"""))
        // brackets in a string do not count
        assertEquals(ok to listOf("/a["), mkdirOf("""{"verb":"hydration.mkdir","path":"/a${"["}","n":"${"[".repeat(200)}"}"""))
    }

    // A bad escape in the field itself: no request (it was a missing field).
    @Test
    fun `a bad escape in a field is no request`() = runTest {
        assertEquals(missingVerb to emptyList(), mkdirOf("""{"verb":"hydration.mkdir","path":"/a\x"}"""))
        assertEquals(missingVerb to emptyList(), mkdirOf("""{"verb":"hydration.mkdir","path":"/a\u00zz"}"""))
    }
}
