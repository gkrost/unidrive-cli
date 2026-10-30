package org.krost.unidrive.hydration

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wire of the hydration IPC handler, pinned as the Windows client sees it: what each verb's request line hands to
 * [Hydration], and the bytes of every reply and event line. Request and reply lines are lines of the client's contract corpus
 * (`tests/Unidrive.TestDaemon/Corpus/ipc-contract/` in gkrost/unidrive-windows) wherever the corpus has one.
 *
 * These tests do not care how the handler reads a request or writes a reply; they exist so that changing that cannot move a byte.
 * Where a request is malformed, or the handler's reader is more lenient than a JSON parser, that is pinned in
 * [HydrationIpcHandlerLenientParseTest], not here.
 */
class HydrationIpcHandlerWireTest {
    private fun handler(h: Hydration) = HydrationIpcHandler(h, handleIdRun = "0123456789ab")

    /** The cache path as the handler writes it: a JSON string with the platform's separators escaped. */
    private fun q(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private val cacheJson = q(Paths.get("/cache/unidrive/hydration/docs/report.txt").toString())

    private suspend fun run(h: ScriptedHydration, line: String, connection: String = "c1"): String = handler(h).handle(connection, line)

    private fun callsOf(line: String): List<HydrationCall> {
        val fake = ScriptedHydration()
        runBlocking { run(fake, line) }
        return fake.calls
    }

    // ── request → what the verb is handed (corpus request lines) ──

    @Test
    fun `every verb hands its fields to Hydration`() = runTest {
        val cacheFile = Paths.get("/cache/unidrive/hydration/docs/report.txt").toString()
        val requests = listOf(
            """{"verb":"hydration.open_read","handle_id":"h-1","path":"/docs/report.txt"}""" to
                HydrationCall("open_read", listOf("c1", "h-1", "/docs/report.txt")),
            """{"verb":"hydration.open_write","handle_id":"h-1","path":"/docs/report.txt","cache_path":"/cache/unidrive/hydration/docs/report.txt"}""" to
                HydrationCall("open_write", listOf("c1", "h-1", "/docs/report.txt", cacheFile, null)),
            """{"verb":"hydration.open_write","handle_id":"h-1","path":"/docs/report.txt","cache_path":"/cache/unidrive/hydration/docs/report.txt","base_etag":"stale-etag"}""" to
                HydrationCall("open_write", listOf("c1", "h-1", "/docs/report.txt", cacheFile, "stale-etag")),
            """{"verb":"hydration.open_write","path":"/docs/report.txt","cache_path":"/cache/unidrive/hydration/docs/report.txt"}""" to
                HydrationCall("open_write", listOf("c1", "dh-0123456789ab-1", "/docs/report.txt", cacheFile, null)),
            """{"verb":"hydration.open_write_begin","path":"/docs/report.txt","handle_id":"wh-1"}""" to
                HydrationCall("open_write_begin", listOf("c1", "/docs/report.txt", "wh-1")),
            """{"verb":"hydration.open_write_begin","path":"/docs/report.txt"}""" to
                HydrationCall("open_write_begin", listOf("c1", "/docs/report.txt", null)),
            """{"verb":"hydration.close_handle","handle_id":"h-1"}""" to HydrationCall("close_handle", listOf("c1", "h-1")),
            """{"verb":"hydration.hydrate","path":"/docs/report.txt"}""" to HydrationCall("hydrate", listOf("/docs/report.txt")),
            """{"verb":"hydration.dehydrate","path":"/docs/report.txt"}""" to HydrationCall("dehydrate", listOf("/docs/report.txt")),
            """{"verb":"hydration.last_synced","path":"/docs/report.txt"}""" to HydrationCall("last_synced", listOf("/docs/report.txt")),
            """{"verb":"hydration.matches_local","path":"/docs/report.txt","local_path":"/cache/report.txt"}""" to
                HydrationCall("matches_local", listOf("/docs/report.txt", Paths.get("/cache/report.txt").toString())),
            """{"verb":"hydration.list","prefix":"/docs"}""" to HydrationCall("list", listOf("/docs")),
            """{"verb":"hydration.mkdir","path":"/docs/newdir"}""" to HydrationCall("mkdir", listOf("/docs/newdir")),
            """{"verb":"hydration.unlink","path":"/docs/report.txt"}""" to HydrationCall("unlink", listOf("/docs/report.txt", false)),
            """{"verb":"hydration.unlink","path":"/docs/report.txt","permanent":true}""" to HydrationCall("unlink", listOf("/docs/report.txt", true)),
            """{"verb":"hydration.rmdir","path":"/docs/sub"}""" to HydrationCall("rmdir", listOf("/docs/sub", false, false)),
            """{"verb":"hydration.rmdir","path":"/docs/sub","recursive":true}""" to HydrationCall("rmdir", listOf("/docs/sub", true, false)),
            """{"verb":"hydration.rmdir","path":"/docs/sub","recursive":true,"permanent":true}""" to HydrationCall("rmdir", listOf("/docs/sub", true, true)),
            """{"verb":"hydration.create","handle_id":"h-2","path":"/docs/new.txt"}""" to HydrationCall("create", listOf("c1", "h-2", "/docs/new.txt")),
            """{"verb":"hydration.create","path":"/docs/new.txt"}""" to HydrationCall("create", listOf("c1", "dh-0123456789ab-1", "/docs/new.txt")),
            """{"verb":"hydration.rename","old_path":"/docs/report.txt","new_path":"/docs/renamed.txt"}""" to
                HydrationCall("rename", listOf("/docs/report.txt", "/docs/renamed.txt", false)),
            """{"verb":"hydration.rename","old_path":"/docs/report.txt","new_path":"/docs/sub","replace":true}""" to
                HydrationCall("rename", listOf("/docs/report.txt", "/docs/sub", true)),
            """{"verb":"hydration.cancel","path":"/docs/open.txt"}""" to HydrationCall("cancel", listOf("/docs/open.txt")),
            """{"verb":"hydration.refuse_name","path":"/docs/caf\u00e9.txt","sibling":"cafe\u0301.txt"}""" to
                HydrationCall("refuse_name", listOf("/docs/caf\u00e9.txt", "cafe\u0301.txt")),
            """{"verb":"hydration.refuse_name","path":"/docs/caf\u00e9.txt"}""" to HydrationCall("refuse_name", listOf("/docs/caf\u00e9.txt", null)),
            """{"verb":"hydration.refuse_name","path":"/docs/caf\u00e9.txt","sibling":""}""" to HydrationCall("refuse_name", listOf("/docs/caf\u00e9.txt", null)),
            """{"verb":"hydration.refuse_name","path":"/refused/caf\u00e9.txt","clear":true}""" to HydrationCall("clear_name_refusal", listOf("/refused/caf\u00e9.txt")),
            """{"verb":"view.page","kind":"recents","limit":2}""" to HydrationCall("view_page", listOf("recents", 0, 2)),
            """{"verb":"view.page","kind":"recents","offset":2,"limit":2}""" to HydrationCall("view_page", listOf("recents", 2, 2)),
            """{"verb":"view.page","kind":"recents"}""" to HydrationCall("view_page", listOf("recents", 0, 50)),
        )
        for ((request, expected) in requests) {
            assertEquals(listOf(expected), callsOf(request), request)
        }
    }

    // ── replies (corpus reply lines) ──

    @Test
    fun `replies of the verbs that answer without a result type`() = runTest {
        val fake = ScriptedHydration()
        assertEquals("""{"ok":true}""", run(fake, """{"verb":"hydration.close_handle","handle_id":"h-1"}"""))
        assertEquals("""{"ok":true}""", run(fake, """{"verb":"hydration.hydrate","path":"/docs/report.txt"}"""))
        assertEquals("""{"ok":true}""", run(fake, """{"verb":"hydration.dehydrate","path":"/docs/report.txt"}"""))
        assertEquals("""{"ok":true}""", run(fake, """{"verb":"hydration.mkdir","path":"/docs/newdir"}"""))
        assertEquals("""{"ok":true}""", run(fake, """{"verb":"hydration.unlink","path":"/docs/report.txt"}"""))
        assertEquals("""{"ok":true}""", run(fake, """{"verb":"hydration.rmdir","path":"/docs/sub"}"""))
        assertEquals("""{"ok":true}""", run(fake, """{"verb":"hydration.rename","old_path":"/a.txt","new_path":"/b.txt"}"""))
        assertEquals("""{"ok":true}""", run(fake, """{"verb":"hydration.subscribe"}"""))
        assertEquals("""{"ok":true,"mtime_ms":1234567890123}""", run(fake, """{"verb":"hydration.last_synced","path":"/docs/report.txt"}"""))
        assertEquals("""{"ok":true,"cancelled":false}""", run(fake, """{"verb":"hydration.cancel","path":"/docs/idle.txt"}"""))
        fake.cancelled = true
        assertEquals("""{"ok":true,"cancelled":true}""", run(fake, """{"verb":"hydration.cancel","path":"/docs/open.txt"}"""))
        fake.cleared = true
        assertEquals("""{"ok":true,"cleared":true}""", run(fake, """{"verb":"hydration.refuse_name","path":"/r.txt","clear":true}"""))
        fake.cleared = false
        assertEquals("""{"ok":true,"cleared":false}""", run(fake, """{"verb":"hydration.refuse_name","path":"/r.txt","clear":true}"""))
        assertEquals("""{"ok":true}""", run(fake, """{"verb":"hydration.refuse_name","path":"/r.txt"}"""))
    }

    @Test
    fun `open replies carry the cache path and only the fields that are set`() = runTest {
        val fake = ScriptedHydration()
        val read = """{"verb":"hydration.open_read","handle_id":"h-1","path":"/docs/report.txt"}"""
        assertEquals("""{"ok":true,"cache_path":$cacheJson}""", run(fake, read))
        fake.open = OpenResult.Ok(fake.cache, contentId = "obj-report|42")
        assertEquals("""{"ok":true,"cache_path":$cacheJson,"content_id":"obj-report|42"}""", run(fake, read))
        fake.open = OpenResult.Ok(fake.cache, excluded = true)
        val write = """{"verb":"hydration.open_write","handle_id":"h-1","path":"/docs/report.txt","cache_path":"/cache/unidrive/hydration/docs/report.txt"}"""
        assertEquals("""{"ok":true,"cache_path":$cacheJson,"excluded":true}""", run(fake, write))
        // the daemon issued the id: it is answered between the cache path and the flags
        fake.open = OpenResult.Ok(fake.cache, excluded = true, contentId = "c")
        val issued = """{"verb":"hydration.open_write","path":"/docs/report.txt","cache_path":"/cache/unidrive/hydration/docs/report.txt"}"""
        assertEquals("""{"ok":true,"cache_path":$cacheJson,"handle_id":"dh-0123456789ab-1","excluded":true,"content_id":"c"}""", run(fake, issued))
    }

    @Test
    fun `create replies`() = runTest {
        val fake = ScriptedHydration()
        val request = """{"verb":"hydration.create","handle_id":"h-2","path":"/docs/new.txt"}"""
        assertEquals("""{"ok":true,"cache_path":$cacheJson,"handle_id":"h-2"}""", run(fake, request))
        fake.create = CreateResult.Ok(fake.cache, "h-2", excluded = true)
        assertEquals("""{"ok":true,"cache_path":$cacheJson,"handle_id":"h-2","excluded":true}""", run(fake, request))
        fake.create = CreateResult.ParentNotFound
        assertEquals("""{"ok":false,"error":"parent_not_found"}""", run(fake, request))
        fake.create = CreateResult.PathExists
        assertEquals("""{"ok":false,"error":"path_exists"}""", run(fake, request))
        fake.create = CreateResult.Failed(HydrationError.Generic("boom"))
        assertEquals("""{"ok":false,"error":"boom"}""", run(fake, request))
    }

    @Test
    fun `matches_local replies`() = runTest {
        val fake = ScriptedHydration()
        val request = """{"verb":"hydration.matches_local","path":"/docs/report.txt","local_path":"/cache/report.txt"}"""
        assertEquals("""{"ok":true,"result":"same","method":"quickxor"}""", run(fake, request))
        fake.matches = MatchesLocalResult.Differs("size")
        assertEquals("""{"ok":true,"result":"differs","method":"size"}""", run(fake, request))
        fake.matches = MatchesLocalResult.Unknown("no_proof")
        assertEquals("""{"ok":true,"result":"unknown","reason":"no_proof"}""", run(fake, request))
        fake.matches = MatchesLocalResult.Failed(HydrationError.Generic("local_file_unreadable"))
        assertEquals("""{"ok":false,"error":"local_file_unreadable"}""", run(fake, request))
    }

    @Test
    fun `refusals before the verb runs`() = runTest {
        val fake = ScriptedHydration()
        assertEquals("""{"ok":false,"error":"missing_verb"}""", run(fake, """{"path":"/a"}"""))
        assertEquals("""{"ok":false,"error":"unknown_verb"}""", run(fake, """{"verb":"hydration.nope"}"""))
        assertEquals("""{"ok":false,"error":"missing_handle_id"}""", run(fake, """{"verb":"hydration.open_read","path":"/a"}"""))
        assertEquals("""{"ok":false,"error":"missing_handle_id"}""", run(fake, """{"verb":"hydration.close_handle"}"""))
        assertEquals("""{"ok":false,"error":"missing_path"}""", run(fake, """{"verb":"hydration.open_read","handle_id":"h"}"""))
        assertEquals("""{"ok":false,"error":"missing_path"}""", run(fake, """{"verb":"hydration.mkdir"}"""))
        assertEquals("""{"ok":false,"error":"missing_path"}""", run(fake, """{"verb":"hydration.refuse_name","sibling":"x"}"""))
        assertEquals("""{"ok":false,"error":"missing_prefix"}""", run(fake, """{"verb":"hydration.list"}"""))
        assertEquals("""{"ok":false,"error":"missing_old_path"}""", run(fake, """{"verb":"hydration.rename","new_path":"/b"}"""))
        assertEquals("""{"ok":false,"error":"missing_new_path"}""", run(fake, """{"verb":"hydration.rename","old_path":"/a"}"""))
        assertEquals("""{"ok":false,"error":"missing_cache_path"}""", run(fake, """{"verb":"hydration.open_write","handle_id":"h","path":"/a"}"""))
        assertEquals(
            """{"ok":false,"error":"missing_cache_path"}""",
            run(fake, """{"verb":"hydration.open_write","handle_id":"h","path":"/a","cache_path":""}"""),
        )
        assertEquals("""{"ok":false,"error":"missing_local_path"}""", run(fake, """{"verb":"hydration.matches_local","path":"/a"}"""))
        assertEquals(
            """{"ok":false,"error":"missing_local_path"}""",
            run(fake, """{"verb":"hydration.matches_local","path":"/a","local_path":""}"""),
        )
        assertEquals("""{"ok":false,"error":"missing_kind"}""", run(fake, """{"verb":"view.page"}"""))
        assertEquals("""{"ok":false,"error":"invalid_path"}""", run(fake, """{"verb":"hydration.hydrate","path":"/docs/../report.txt"}"""))
        assertEquals("""{"ok":false,"error":"invalid_path"}""", run(fake, """{"verb":"hydration.list","prefix":"/docs/.."}"""))
        assertEquals(
            """{"ok":false,"error":"invalid_local_path"}""",
            run(fake, """{"verb":"hydration.matches_local","path":"/a","local_path":"/cache/a\u0000b"}"""),
        )
        assertEquals(
            """{"ok":false,"error":"invalid_handle_id"}""",
            run(fake, """{"verb":"hydration.open_read","handle_id":"dh-0123456789ab-1","path":"/a"}"""),
        )
        assertTrue(fake.calls.isEmpty(), "none of these reached Hydration: ${fake.calls}")
        // the one that does: a profile with no such view
        assertEquals("""{"ok":false,"error":"unknown_view"}""", run(fake, """{"verb":"view.page","kind":"shared"}"""))
    }

    // ── hydration.list and view.page replies ──

    private val plainEntry = ListResult.Entry(
        path = "/docs/report.txt",
        size = 42,
        mtimeEpochMillis = 1234567890123,
        isHydrated = false,
        isFolder = false,
    )

    private val plainEntryJson =
        """{"path":"/docs/report.txt","size":42,"mtime_ms":1234567890123,"hydrated":false,"folder":false,""" +
            """"remote_modified_ms":null,"remote_id":null,"etag":null,"pending_upload":false,"error":false}"""

    @Test
    fun `list entry with every field absent`() = runTest {
        val fake = ScriptedHydration().apply { list = ListResult.Ok(listOf(plainEntry)) }
        assertEquals("""{"ok":true,"entries":[$plainEntryJson]}""", run(fake, """{"verb":"hydration.list","prefix":"/docs"}"""))
    }

    @Test
    fun `list entry with every field set`() = runTest {
        val full = ListResult.Entry(
            path = "/Recents/todo.txt",
            size = 5,
            mtimeEpochMillis = 1234567890123,
            isHydrated = true,
            isFolder = true,
            remoteModifiedEpochMillis = 1234567890000,
            remoteId = "unidrive:view:recents:22222222-todo",
            etag = "etag-todo",
            contentId = "obj-report|42",
            pendingUpload = true,
            hasError = true,
            excluded = true,
            readOnly = true,
            trashRoot = true,
            blocked = "similar_name",
            view = "recents",
            viewTarget = "/Notes/todo.txt",
        )
        val fake = ScriptedHydration().apply { list = ListResult.Ok(listOf(plainEntry, full)) }
        assertEquals(
            """{"ok":true,"entries":[$plainEntryJson,""" +
                """{"path":"/Recents/todo.txt","size":5,"mtime_ms":1234567890123,"hydrated":true,"folder":true,""" +
                """"remote_modified_ms":1234567890000,"remote_id":"unidrive:view:recents:22222222-todo","etag":"etag-todo",""" +
                """"content_id":"obj-report|42","pending_upload":true,"error":true,"excluded":true,"read_only":true,"trash_root":true,""" +
                """"blocked":"similar_name","view":"recents","view_target":"/Notes/todo.txt"}]}""",
            run(fake, """{"verb":"hydration.list","prefix":"/Recents"}"""),
        )
    }

    @Test
    fun `list failure`() = runTest {
        val fake = ScriptedHydration().apply { list = ListResult.Failed(HydrationError.Generic("boom")) }
        assertEquals("""{"ok":false,"error":"boom"}""", run(fake, """{"verb":"hydration.list","prefix":"/docs"}"""))
    }

    @Test
    fun `view page replies`() = runTest {
        val fake = ScriptedHydration()
        fake.viewPage = ViewPageResult.Ok("recents", 0, listOf(plainEntry), skipped = 1, nextOffset = 2, state = "ok", asOfMs = 1700000000000)
        assertEquals(
            """{"ok":true,"kind":"recents","offset":0,"entries":[$plainEntryJson],"skipped":1,"next_offset":2,"state":"ok","as_of_ms":1700000000000}""",
            run(fake, """{"verb":"view.page","kind":"recents","limit":2}"""),
        )
        fake.viewPage = ViewPageResult.Ok("recents", 1000, emptyList(), skipped = 0, nextOffset = null, state = "stale", asOfMs = null)
        assertEquals(
            """{"ok":true,"kind":"recents","offset":1000,"entries":[],"skipped":0,"end":true,"state":"stale","as_of_ms":null}""",
            run(fake, """{"verb":"view.page","kind":"recents","offset":1000}"""),
        )
        fake.viewPage = ViewPageResult.Failed(HydrationError.Generic("boom"))
        assertEquals("""{"ok":false,"error":"boom"}""", run(fake, """{"verb":"view.page","kind":"recents"}"""))
    }

    @Test
    fun `view page counts accept a whole number in range and nothing else`() = runTest {
        // limit: 1..1000 (default 50). offset: 0..Int.MAX_VALUE (default 0).
        fun page(extra: String) = callsOf("""{"verb":"view.page","kind":"recents"$extra}""")
        assertEquals(listOf(HydrationCall("view_page", listOf("recents", 0, 1))), page(""","limit":1"""))
        assertEquals(listOf(HydrationCall("view_page", listOf("recents", 0, 1000))), page(""","limit":1000"""))
        assertEquals(listOf(HydrationCall("view_page", listOf("recents", 0, 50))), page(""","limit":0050"""))
        assertEquals(listOf(HydrationCall("view_page", listOf("recents", 0, 50))), page(""","offset":0"""))
        assertEquals(listOf(HydrationCall("view_page", listOf("recents", Int.MAX_VALUE, 50))), page(""","offset":2147483647"""))
        for (bad in listOf(
            ""","limit":0""", ""","limit":1001""", ""","limit":-1""", ""","limit":"5"""", ""","limit":null""", ""","limit":1.0""",
            ""","limit":1e2""", ""","limit":true""", ""","limit":[5]""", ""","limit":{"a":5}""", ""","limit":12345678901""", ""","limit":""""",
            ""","offset":-1""", ""","offset":2147483648""", ""","offset":"0"""", ""","offset":null""", ""","offset":0.0""",
        )) {
            assertEquals(emptyList(), page(bad), "$bad is refused before Hydration")
        }
        val fake = ScriptedHydration()
        assertEquals("""{"ok":false,"error":"invalid_limit"}""", run(fake, """{"verb":"view.page","kind":"recents","limit":0}"""))
        assertEquals("""{"ok":false,"error":"invalid_offset"}""", run(fake, """{"verb":"view.page","kind":"recents","offset":-1}"""))
        // offset is read first
        assertEquals("""{"ok":false,"error":"invalid_offset"}""", run(fake, """{"verb":"view.page","kind":"recents","offset":-1,"limit":0}"""))
    }

    // ── optional flags ──

    @Test
    fun `optional flags read the bare literals and the quoted words, anything else is the default`() = runTest {
        fun permanent(value: String): Boolean =
            callsOf("""{"verb":"hydration.unlink","path":"/a","permanent":$value}""").single().args[1] as Boolean
        assertEquals(true, permanent("true"))
        assertEquals(false, permanent("false"))
        assertEquals(true, permanent(""""true""""))
        assertEquals(true, permanent(""""TRUE""""))
        assertEquals(true, permanent(""""True""""))
        assertEquals(false, permanent(""""false""""))
        assertEquals(false, permanent("null"))
        assertEquals(false, permanent("1"))
        assertEquals(false, permanent("0"))
        assertEquals(false, permanent(""""yes""""))
        assertEquals(false, permanent(""""""""))
        assertEquals(false, permanent("[true]"))
        assertEquals(false, permanent("""{"a":true}"""))
        // rename's replace: absent and "false" are the same, and junk is the default too
        fun replace(value: String?): Boolean {
            val member = if (value == null) "" else ""","replace":$value"""
            return callsOf("""{"verb":"hydration.rename","old_path":"/a","new_path":"/b"$member}""").single().args[2] as Boolean
        }
        assertEquals(false, replace(null))
        assertEquals(true, replace("true"))
        assertEquals(true, replace(""""true""""))
        assertEquals(false, replace("false"))
        assertEquals(false, replace("null"))
        assertEquals(false, replace("2"))
        // recursive and clear
        assertEquals(true, callsOf("""{"verb":"hydration.rmdir","path":"/a","recursive":"true"}""").single().args[1])
        assertEquals(false, callsOf("""{"verb":"hydration.rmdir","path":"/a","recursive":1}""").single().args[1])
        assertEquals("refuse_name", callsOf("""{"verb":"hydration.refuse_name","path":"/a","clear":false}""").single().verb)
        assertEquals("refuse_name", callsOf("""{"verb":"hydration.refuse_name","path":"/a","clear":"no"}""").single().verb)
        assertEquals("clear_name_refusal", callsOf("""{"verb":"hydration.refuse_name","path":"/a","clear":"true"}""").single().verb)
    }

    // ── how a field is found in the request ──

    @Test
    fun `a field is found by its name at the top level, wherever it stands and however the line is spaced`() = runTest {
        val expected = listOf(HydrationCall("open_read", listOf("c1", "h-1", "/docs/report.txt")))
        val lines = listOf(
            """{"path":"/docs/report.txt","handle_id":"h-1","verb":"hydration.open_read"}""",
            """ { "verb" : "hydration.open_read" , "handle_id" : "h-1" , "path" : "/docs/report.txt" } """,
            "\t{\t\"verb\"\t:\t\"hydration.open_read\",\t\"handle_id\":\"h-1\",\t\"path\":\"/docs/report.txt\"\t}",
            // members this verb does not read, of every kind, before and after the ones it does
            """{"extra":{"path":"/nested/wrong.txt","handle_id":"nope"},"verb":"hydration.open_read","list":[1,"path",{"path":"/x"}],"handle_id":"h-1","n":null,"path":"/docs/report.txt","t":true,"f":1.5e3}""",
            // a value that spells a key, and a key spelled with escapes
            """{"note":"\"path\":\"/wrong\"","verb":"hydration.open_read","handle_id":"h-1","pa\u0074h":"/docs/report.txt"}""",
            """{"verb":"hydration.open_read","handle_id":"h-1","path":"\/docs\/report.txt"}""",
            """{"verb":"hydration.open_read","handle_id":"h-1","path":"/docs/report.txt","trailing":"}{,"}""",
        )
        for (line in lines) {
            assertEquals(expected, callsOf(line), line)
        }
    }

    @Test
    fun `a field that is not a string is missing`() = runTest {
        val fake = ScriptedHydration()
        for (value in listOf("5", "null", "true", "[]", """{"a":"/x"}""", """["/x"]""")) {
            assertEquals(
                """{"ok":false,"error":"missing_path"}""",
                run(fake, """{"verb":"hydration.mkdir","path":$value}"""),
                "path: $value",
            )
        }
        assertEquals("""{"ok":false,"error":"missing_verb"}""", run(fake, """{"verb":5,"path":"/a"}"""))
        assertEquals("""{"ok":false,"error":"missing_verb"}""", run(fake, """{"verb":null}"""))
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `a line that is not an object has no verb`() = runTest {
        val fake = ScriptedHydration()
        for (line in listOf("", "   ", "null", "5", """"hydration.mkdir"""", """["hydration.mkdir"]""", "not json", "{", "}", """[{"verb":"hydration.mkdir"}]""")) {
            assertEquals("""{"ok":false,"error":"missing_verb"}""", run(fake, line), "line: $line")
        }
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `string escapes of a field decode to the text`() {
        val bs = '\\'
        // quote, backslash, solidus, the control escapes and a surrogate pair as two escapes, in the one field a client may put them in: the handle id
        val line = """{"verb":"hydration.close_handle","handle_id":"a${bs}"b${bs}${bs}c${bs}/d${bs}be${bs}ff${bs}ng${bs}rh${bs}ti${bs}uD83D${bs}uDE00j${bs}u00e9"}"""
        val text = "a\"b\\c/d\be\u000cf\ng\rh\ti\uD83D\uDE00j\u00e9"
        assertEquals(listOf(HydrationCall("close_handle", listOf("c1", text))), callsOf(line))
    }

    // ── events ──

    @Test
    fun `every event line`() {
        assertEquals("""{"event":"hydrating","path":"/a"}""", serialiseHydrationEvent(HydrationEvent.Hydrating("/a")))
        assertEquals("""{"event":"hydrated","path":"/a","bytes":12}""", serialiseHydrationEvent(HydrationEvent.Hydrated("/a", 12, fetched = true)))
        assertEquals("""{"event":"dehydrated","path":"/a"}""", serialiseHydrationEvent(HydrationEvent.Dehydrated("/a")))
        assertEquals("""{"event":"skipped","path":"/a"}""", serialiseHydrationEvent(HydrationEvent.Skipped("/a")))
        assertEquals("""{"event":"queued","path":"/a"}""", serialiseHydrationEvent(HydrationEvent.Queued("/a")))
        assertEquals(
            """{"event":"uploading","path":"/a","handle_id":"h1","bytes_done":4096,"bytes_total":65536}""",
            serialiseHydrationEvent(HydrationEvent.Uploading("/a", "h1", 4096, 65536)),
        )
        assertEquals(
            """{"event":"failed","path":"/a","error":"boom"}""",
            serialiseHydrationEvent(HydrationEvent.Failed("/a", HydrationError.Generic("boom"))),
        )
        assertEquals(
            """{"event":"failed","path":"/Trashed folder/report.docx","error":"parent_gone: /Trashed folder","retry_scheduled":false}""",
            serialiseHydrationEvent(HydrationEvent.Failed("/Trashed folder/report.docx", HydrationError.Generic("parent_gone: /Trashed folder"), retryScheduled = false)),
        )
        assertEquals(
            """{"event":"failed","path":"/a","error":"x","retry_scheduled":true}""",
            serialiseHydrationEvent(HydrationEvent.Failed("/a", HydrationError.Generic("x"), retryScheduled = true)),
        )
        assertEquals(
            """{"event":"completed","path":"/a","handle_id":"h1","direction":"upload","ok":true}""",
            serialiseHydrationEvent(HydrationEvent.Completed("/a", "h1", HydrationEvent.Completed.Direction.UPLOAD, ok = true)),
        )
        assertEquals(
            """{"event":"completed","path":"/Trashed folder/report.docx","handle_id":"h1","direction":"upload","ok":false,"error":"parent_gone: /Trashed folder"}""",
            serialiseHydrationEvent(
                HydrationEvent.Completed(
                    "/Trashed folder/report.docx", "h1", HydrationEvent.Completed.Direction.UPLOAD, ok = false,
                    error = HydrationError.Generic("parent_gone: /Trashed folder"),
                ),
            ),
        )
        assertEquals(
            """{"event":"completed","path":"/a","handle_id":"h1","direction":"download","ok":false,"error":"unknown"}""",
            serialiseHydrationEvent(HydrationEvent.Completed("/a", "h1", HydrationEvent.Completed.Direction.DOWNLOAD, ok = false)),
        )
        assertEquals(
            """{"event":"paused","reason":"metered","since_ms":1700000000000,"auto":true}""",
            serialiseHydrationEvent(HydrationEvent.Paused("metered", 1700000000000, auto = true)),
        )
        assertEquals(
            """{"event":"paused","reason":"user","since_ms":1700000000000,"auto":false,"until_ms":4102444800000}""",
            serialiseHydrationEvent(HydrationEvent.Paused("user", 1700000000000, auto = false, untilMs = 4102444800000)),
        )
        assertEquals("""{"event":"resumed"}""", serialiseHydrationEvent(HydrationEvent.Resumed()))
        assertEquals("""{"event":"resumed","reason":"timeout"}""", serialiseHydrationEvent(HydrationEvent.Resumed(HydrationEvent.Resumed.TIMEOUT)))
        assertEquals("""{"event":"view.invalidated","paths":["/Recents"]}""", serialiseHydrationEvent(HydrationEvent.ViewInvalidated(listOf("/Recents"))))
        assertEquals("""{"event":"view.invalidated","paths":[]}""", serialiseHydrationEvent(HydrationEvent.ViewInvalidated(emptyList())))
        assertEquals("""{"event":"view.invalidated","full":true}""", serialiseHydrationEvent(HydrationEvent.ViewInvalidated(emptyList(), full = true)))
        assertEquals(
            """{"event":"view.invalidated","paths":["/a","/b"],"moved":[{"from":"/a","to":"/b"},{"from":"/c","to":"/d"}]}""",
            serialiseHydrationEvent(
                HydrationEvent.ViewInvalidated(
                    listOf("/a", "/b"),
                    moved = listOf(HydrationEvent.ViewInvalidated.Moved("/a", "/b"), HydrationEvent.ViewInvalidated.Moved("/c", "/d")),
                ),
            ),
        )
    }

    // ── what a string value is written as ──

    @Test
    fun `strings are written as JSON strings that read back as the text, on one line`() {
        // every character class: ASCII, quote, backslash, each control character, DEL, NEL, the line and paragraph separators, a
        // surrogate pair, a lone surrogate. The event path is one place a name goes; the failure message is another.
        val texts = listOf(
            "plain", "q\"uote", "back\\slash", "tab\tcr\rlf\n", "\u0000\u0001\u0008\u000b\u000c\u001f", "del\u007f", "nel\u0085ls\u2028ps\u2029",
            "caf\u00e9 \uD83D\uDE00", "lone\uD83D", "{\"event\":\"hydrating\"}", "a/b\\c\"d",
        )
        for (text in texts) {
            val line = serialiseHydrationEvent(HydrationEvent.Failed(text, HydrationError.Generic(text)))
            assertTrue('\n' !in line && '\r' !in line, "one line for ${text.map { it.code }}: $line")
            val parsed = Json.parseToJsonElement(line) as JsonObject
            assertEquals(text, (parsed["path"] as JsonPrimitive).content, "path ${text.map { it.code }}")
            assertEquals(text, (parsed["error"] as JsonPrimitive).content, "error ${text.map { it.code }}")
        }
    }

    @Test
    fun `the characters every writer escapes the same way`() {
        fun path(s: String) = serialiseHydrationEvent(HydrationEvent.Hydrating(s))
        assertEquals("""{"event":"hydrating","path":"q\"b\\"}""", path("q\"b\\"))
        assertEquals("""{"event":"hydrating","path":"\n\r\t"}""", path("\n\r\t"))
        assertEquals("""{"event":"hydrating","path":"\u0001\u001f"}""", path("\u0001\u001f"))
        assertEquals("{\"event\":\"hydrating\",\"path\":\"caf\u00e9 /\uD83D\uDE00\"}", path("caf\u00e9 /\uD83D\uDE00"))
    }
}
