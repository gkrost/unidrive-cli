package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.http.HttpRetryBudget
import org.krost.unidrive.internxt.model.SharedByMe
import org.krost.unidrive.internxt.model.SharedRef
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `InternxtApiService.listSharedByMe`: what the account shares by itself, read from `GET sharings/folders` and `GET
 * sharings/files`, on fixtures shaped like the server's answers (a canary in every field a listing must never keep; what the
 * canaries prove is in InternxtSharingsSecretsTest).
 *
 * Paging: `page` from 0 until a page with fewer than 50 items, folders first and then files. The server's paging has no stable
 * order, so a page can repeat an item and skip one: an item is taken once, by uuid, and a skipped one is found by the next
 * call.
 */
class InternxtSharedByMeTest {
    private fun drive(sharings: FakeSharings) = FakeDrive(emptyList(), emptyList(), sharings = sharings)

    private fun folders(
        count: Int,
        prefix: String = "fo",
        from: Int = 0,
    ) = (from until from + count).map { FakeShare("$prefix-$it", "Folder $it") }

    private fun files(
        count: Int,
        prefix: String = "fi",
        from: Int = 0,
    ) = (from until from + count).map { FakeShare("$prefix-$it", "file$it.txt", isFolder = false) }

    private fun refs(result: SharedByMe) = assertIs<SharedByMe.Listed>(result).refs

    private fun routeRequests(
        sharings: FakeSharings,
        route: String,
    ) = sharings.requests.filter { it.startsWith("$route ") }

    // ---- what is listed --------------------------------------------------------------------------------------------

    @Test
    fun `the account's own folders and files are listed, folders first, and what is shared with it is dropped`() =
        runTest {
            val sharings =
                FakeSharings(
                    folders =
                        FakeSharings.pages(
                            listOf(
                                FakeShare("fo-1", "Projects"),
                                FakeShare("fo-theirs", "Theirs", sharedWithMe = true),
                                FakeShare("fo-2", "Photos"),
                            ),
                        ),
                    files =
                        FakeSharings.pages(
                            listOf(
                                FakeShare("fi-1", "report.txt", isFolder = false),
                                FakeShare("fi-theirs", "theirs.txt", isFolder = false, sharedWithMe = true),
                            ),
                        ),
                )
            val service = internxtApiOn(drive(sharings))

            val listed = assertIs<SharedByMe.Listed>(service.listSharedByMe())

            assertEquals(
                listOf(SharedRef("fo-1", true, "Projects"), SharedRef("fo-2", true, "Photos"), SharedRef("fi-1", false, "report.txt")),
                listed.refs,
            )
            assertFalse(listed.truncated)
            assertEquals(listOf("folders page=0 perPage=50", "files page=0 perPage=50"), sharings.requests)
            service.close()
        }

    @Test
    fun `a sharing that is a public link is the account's own and is listed`() =
        runTest {
            val sharings =
                FakeSharings(
                    folders = FakeSharings.pages(listOf(FakeShare("fo-link", "Public folder", publicLink = true))),
                    files = FakeSharings.pages(listOf(FakeShare("fi-link", "public.pdf", isFolder = false, publicLink = true))),
                )
            val service = internxtApiOn(drive(sharings))

            assertEquals(listOf(SharedRef("fo-link", true, "Public folder"), SharedRef("fi-link", false, "public.pdf")), refs(service.listSharedByMe()))
            service.close()
        }

    @Test
    fun `an answer with only items shared with the account lists nothing`() =
        runTest {
            val sharings =
                FakeSharings(
                    folders = FakeSharings.pages(listOf(FakeShare("fo-theirs", "Theirs", sharedWithMe = true))),
                    files = FakeSharings.pages(listOf(FakeShare("fi-theirs", "theirs.txt", isFolder = false, sharedWithMe = true))),
                )
            val service = internxtApiOn(drive(sharings))

            val listed = assertIs<SharedByMe.Listed>(service.listSharedByMe())

            assertEquals(emptyList(), listed.refs)
            assertFalse(listed.truncated)
            service.close()
        }

    @Test
    fun `an empty answer lists nothing and is two requests`() =
        runTest {
            val sharings = FakeSharings()
            val service = internxtApiOn(drive(sharings))

            val listed = assertIs<SharedByMe.Listed>(service.listSharedByMe())

            assertEquals(emptyList(), listed.refs)
            assertFalse(listed.truncated)
            assertEquals(listOf("folders page=0 perPage=50", "files page=0 perPage=50"), sharings.requests)
            service.close()
        }

    @Test
    fun `items that are not live are dropped, whatever way the row says so`() =
        runTest {
            val sharings =
                FakeSharings(
                    folders =
                        FakeSharings.pages(
                            listOf(
                                FakeShare("fo-live", "Live"),
                                FakeShare("fo-no-status", "No status key", status = null),
                                FakeShare("fo-deleted", "Deleted flag", deleted = true),
                                FakeShare("fo-removed", "Removed flag", removed = true),
                                FakeShare("fo-trashed", "Trashed status", status = "TRASHED"),
                            ),
                        ),
                    files =
                        FakeSharings.pages(
                            listOf(
                                FakeShare("fi-live", "live.txt", isFolder = false),
                                FakeShare("fi-trashed", "trashed.txt", isFolder = false, status = "TRASHED"),
                                FakeShare("fi-deleted", "deleted.txt", isFolder = false, status = "DELETED"),
                                FakeShare("fi-removed", "removed.txt", isFolder = false, removed = true),
                            ),
                        ),
                )
            val service = internxtApiOn(drive(sharings))

            assertEquals(listOf("fo-live", "fo-no-status", "fi-live"), refs(service.listSharedByMe()).map { it.uuid })
            service.close()
        }

    // ---- paging ----------------------------------------------------------------------------------------------------

    @Test
    fun `pages of 50, 50 and 7 are three requests and 107 references`() =
        runTest {
            val sharings = FakeSharings(folders = FakeSharings.pages(folders(107)))
            val service = internxtApiOn(drive(sharings))

            val listed = refs(service.listSharedByMe())

            assertEquals((0 until 107).map { "fo-$it" }, listed.map { it.uuid })
            assertEquals(
                listOf("folders page=0 perPage=50", "folders page=1 perPage=50", "folders page=2 perPage=50"),
                routeRequests(sharings, "folders"),
            )
            assertEquals(listOf("files page=0 perPage=50"), routeRequests(sharings, "files"), "a short folders page does not stop the files")
            service.close()
        }

    @Test
    fun `a full page is followed by one more request, and an empty one ends the route`() =
        runTest {
            val sharings = FakeSharings(folders = FakeSharings.pages(folders(100)), files = FakeSharings.pages(files(50)))
            val service = internxtApiOn(drive(sharings))

            assertEquals(150, refs(service.listSharedByMe()).size)
            assertEquals(3, routeRequests(sharings, "folders").size, "pages 0 and 1 are full, page 2 is empty")
            assertEquals(2, routeRequests(sharings, "files").size)
            service.close()
        }

    @Test
    fun `an item that a later page repeats is listed once, at the place it first came`() =
        runTest {
            val all = folders(120)
            // Page 1 starts with the last item of page 0 (item 49), as an unstable offset can serve it.
            val pages = listOf(all.subList(0, 50), all.subList(49, 99), all.subList(99, 120))
            val sharings = FakeSharings(folders = { page -> pages.getOrElse(page) { emptyList() } })
            val service = internxtApiOn(drive(sharings))

            val listed = refs(service.listSharedByMe()).map { it.uuid }

            assertEquals((0 until 120).map { "fo-$it" }, listed)
            assertEquals(3, routeRequests(sharings, "folders").size)
            service.close()
        }

    @Test
    fun `an item that a page boundary skips is missing from that listing and found by the next one`() =
        runTest {
            val all = folders(101)
            var skipping = true
            val sharings =
                FakeSharings(
                    folders = { page ->
                        when {
                            page == 0 -> all.subList(0, 50)
                            // Page 1 starts one late while skipping: item 50 is on neither page.
                            page == 1 -> if (skipping) all.subList(51, 101) else all.subList(50, 100)
                            page == 2 && !skipping -> all.subList(100, 101)
                            else -> emptyList()
                        }
                    },
                )
            val service = internxtApiOn(drive(sharings))

            val first = refs(service.listSharedByMe()).map { it.uuid }
            skipping = false
            val second = refs(service.listSharedByMe()).map { it.uuid }

            assertEquals(100, first.size)
            assertFalse("fo-50" in first, "the skipped item is not guessed at")
            assertEquals((0 until 101).map { "fo-$it" }, second, "the next listing has it")
            service.close()
        }

    @Test
    fun `a server that answers every page with the same page fails the listing instead of reading it for ever`() =
        runTest {
            val page = folders(50)
            val sharings = FakeSharings(folders = { page })
            val service = internxtApiOn(drive(sharings))

            val e = assertFailsWith<InternxtApiException> { service.listSharedByMe() }

            assertEquals(503, e.statusCode)
            assertTrue("does not honour the page" in e.message.orEmpty(), e.message)
            assertEquals(2, sharings.requests.size, "the repeat of page 0 is seen on the second request")
            service.close()
        }

    // ---- the cap ---------------------------------------------------------------------------------------------------

    @Test
    fun `the 5001st file is not listed and makes the list truncated`() =
        runTest {
            val sharings = FakeSharings(files = FakeSharings.pages(files(5_001)))
            val service = internxtApiOn(drive(sharings))

            val listed = assertIs<SharedByMe.Listed>(service.listSharedByMe())

            assertEquals(InternxtApiService.MAX_SHARED_REFS, listed.refs.size)
            assertEquals("fi-4999", listed.refs.last().uuid)
            assertTrue(listed.truncated)
            assertEquals(101, routeRequests(sharings, "files").size)
            service.close()
        }

    @Test
    fun `exactly 5000 files are not truncated`() =
        runTest {
            val sharings = FakeSharings(files = FakeSharings.pages(files(5_000)))
            val service = internxtApiOn(drive(sharings))

            val listed = assertIs<SharedByMe.Listed>(service.listSharedByMe())

            assertEquals(5_000, listed.refs.size)
            assertFalse(listed.truncated)
            service.close()
        }

    @Test
    fun `folders do not count toward the cap of files, however many the account shares`() =
        runTest {
            // 5,000 shared folders must not leave room for no file.
            val sharings = FakeSharings(folders = FakeSharings.pages(folders(5_000)), files = FakeSharings.pages(files(5)))
            val service = internxtApiOn(drive(sharings))

            val listed = assertIs<SharedByMe.Listed>(service.listSharedByMe())

            assertEquals(5, listed.refs.count { !it.isFolder })
            assertFalse(listed.truncated)
            assertEquals(listOf("files page=0 perPage=50"), routeRequests(sharings, "files"), "a short page ends the route")
            service.close()
        }

    @Test
    fun `a run of folders past the cap stops being read and leaves the files whole`() =
        runTest {
            val sharings = FakeSharings(folders = FakeSharings.pages(folders(5_001)), files = FakeSharings.pages(files(5)))
            val service = internxtApiOn(drive(sharings))

            val listed = assertIs<SharedByMe.Listed>(service.listSharedByMe())

            assertEquals(5, listed.refs.count { !it.isFolder })
            assertEquals(InternxtApiService.MAX_SHARED_REFS, listed.refs.count { it.isFolder })
            assertFalse(listed.truncated, "the files are whole")
            assertTrue(listed.foldersCut, "and the folders are said to be cut")
            assertEquals(101, routeRequests(sharings, "folders").size)
            service.close()
        }

    @Test
    fun `the cap counts the files only, and what is not listed does not count`() =
        runTest {
            // 4,999 own files among foreign ones, then 3 more: the 5,000th is the first of them, the next is one too many.
            val all = files(4_999) + (0 until 2_000).map { FakeShare("theirs-$it", "Theirs $it", isFolder = false, sharedWithMe = true) } + files(3, from = 4_999)
            val sharings = FakeSharings(files = FakeSharings.pages(all))
            val service = internxtApiOn(drive(sharings))

            val listed = assertIs<SharedByMe.Listed>(service.listSharedByMe())

            assertEquals(5_000, listed.refs.size)
            assertEquals(SharedRef("fi-4999", false, "file4999.txt"), listed.refs.last())
            assertTrue(listed.truncated)
            service.close()
        }

    // ---- failures --------------------------------------------------------------------------------------------------

    @Test
    fun `a 400 propagates, is asked once, and its error text names the status and nothing of the body`() =
        runTest {
            val sharings = FakeSharings(failure = { _, _ -> 400 })
            val service = internxtApiOn(drive(sharings))

            val e = assertFailsWith<InternxtApiException> { service.listSharedByMe() }

            assertEquals(400, e.statusCode)
            assertEquals("API error: 400 Bad Request", e.message)
            assertEquals("srv-sharings", e.requestId)
            assertEquals(1, sharings.requests.size)
            service.close()
        }

    @Test
    fun `a 404 or 405 on the first page is Unsupported, not an exception`() =
        runTest {
            for (status in listOf(404, 405)) {
                val sharings = FakeSharings(failure = { _, _ -> status })
                val service = internxtApiOn(drive(sharings))

                assertEquals(SharedByMe.Unsupported, service.listSharedByMe(), "HTTP $status")
                assertEquals(1, sharings.requests.size, "HTTP $status: the files route is not asked")
                service.close()
            }
        }

    @Test
    fun `a gateway that has the folders route and lacks the files route is Unsupported, not half a list`() =
        runTest {
            val sharings = FakeSharings(folders = FakeSharings.pages(folders(3)), failure = { route, _ -> if (route == "files") 404 else null })
            val service = internxtApiOn(drive(sharings))

            assertEquals(SharedByMe.Unsupported, service.listSharedByMe())
            service.close()
        }

    @Test
    fun `a 404 on a later page is an error, not a gateway without the route`() =
        runTest {
            val sharings = FakeSharings(folders = FakeSharings.pages(folders(60)), failure = { _, page -> if (page == 1) 404 else null })
            val service = internxtApiOn(drive(sharings))

            val e = assertFailsWith<InternxtApiException> { service.listSharedByMe() }

            assertEquals(404, e.statusCode)
            service.close()
        }

    @Test
    fun `a 401 refreshes the token once and the listing goes on`() =
        runTest {
            val unauthorized = AtomicInteger()
            val forced = AtomicInteger()
            val sharings =
                FakeSharings(
                    folders = FakeSharings.pages(folders(2)),
                    failure = { _, _ -> if (unauthorized.getAndIncrement() == 0) 401 else null },
                )
            val service =
                internxtApiOn(drive(sharings)) { forceRefresh ->
                    if (forceRefresh) forced.incrementAndGet()
                    fakeCredentials()
                }

            assertEquals(listOf("fo-0", "fo-1"), refs(service.listSharedByMe()).map { it.uuid })

            assertEquals(1, forced.get(), "one forced refresh")
            assertEquals(3, sharings.requests.size, "the refused page again with the new token, then the files")
            service.close()
        }

    @Test
    fun `a second 401 is an authentication failure that names no body`() =
        runTest {
            val forced = AtomicInteger()
            val sharings = FakeSharings(failure = { _, _ -> 401 })
            val service =
                internxtApiOn(drive(sharings)) { forceRefresh ->
                    if (forceRefresh) forced.incrementAndGet()
                    fakeCredentials()
                }

            val e = assertFailsWith<AuthenticationException> { service.listSharedByMe() }

            assertEquals("Authentication failed (401)", e.message)
            assertEquals(1, forced.get())
            assertEquals(2, sharings.requests.size)
            service.close()
        }

    @Test
    fun `a 503 is retried and the body's retry_after is honoured though the body is not in the error text`() =
        runTest {
            val calls = AtomicInteger()
            val sharings = FakeSharings(folders = FakeSharings.pages(folders(2)), failure = { _, _ -> if (calls.getAndIncrement() == 0) 503 else null })
            val service = internxtApiOn(drive(sharings))

            assertEquals(2, refs(service.listSharedByMe()).size)

            assertEquals(7_000L, testScheduler.currentTime, "the 7 s of the body's retry_after, not the 2 s of the ladder")
            service.close()
        }

    @Test
    fun `a 503 that stays is three attempts and then the exception, with no body in its text`() =
        runTest {
            val sharings = FakeSharings(failure = { _, _ -> 503 })
            val service = internxtApiOn(drive(sharings))

            val e = assertFailsWith<InternxtApiException> { service.listSharedByMe() }

            assertEquals(503, e.statusCode)
            assertEquals("API error: 503 Service Unavailable", e.message)
            assertEquals(3, sharings.requests.size)
            service.close()
        }

    // ---- a body that cannot be read --------------------------------------------------------------------------------

    @Test
    fun `an item without a uuid fails the page, names the missing field and quotes nothing of the answer`() =
        runTest {
            val body = """{"folders":[{"plainName":"No identity","sharedWithMe":false,"encryptionKey":"${Canary.ENCRYPTION_KEY}"}],"files":[]}"""
            val sharings = FakeSharings(raw = { _, _ -> body })
            val service = internxtApiOn(drive(sharings))

            val e = assertFailsWith<InternxtUnreadableResponse> { service.listSharedByMe() }

            assertEquals("The sharings folders listing (page 0) cannot be read: a required field is missing: uuid", e.message)
            assertEquals("srv-sharings", e.requestId)
            assertNull(e.cause, "the decoder's exception quotes the body")
            assertEquals(1, sharings.requests.size, "the same body would come again: not retried")
            service.close()
        }

    @Test
    fun `an item that does not say whose it is fails the page instead of being listed or dropped by a guess`() =
        runTest {
            val body = """{"folders":[{"uuid":"fo-1","plainName":"Whose?"}],"files":[]}"""
            val service = internxtApiOn(drive(FakeSharings(raw = { _, _ -> body })))

            val e = assertFailsWith<InternxtUnreadableResponse> { service.listSharedByMe() }

            assertTrue(e.message.orEmpty().endsWith("a required field is missing: sharedWithMe"), e.message)
            service.close()
        }

    @Test
    fun `a body that is no JSON fails the page with the decoder's class and none of its text`() =
        runTest {
            val body = """{"folders":[{"uuid":"fo-1","sharedWithMe":false,"encryptionKey":"${Canary.ENCRYPTION_KEY}"""
            val service = internxtApiOn(drive(FakeSharings(raw = { _, _ -> body })))

            val e = assertFailsWith<InternxtUnreadableResponse> { service.listSharedByMe() }

            assertTrue(e.message.orEmpty().startsWith("The sharings folders listing (page 0) cannot be read: "), e.message)
            assertFalse("fo-1" in e.message.orEmpty())
            service.close()
        }

    @Test
    fun `a null flag takes the model's default, and the other route's array may be null`() =
        runTest {
            val body = """{"folders":[{"uuid":"fo-1","plainName":null,"sharedWithMe":false,"status":null,"deleted":null,"removed":null}],"files":null}"""
            val service = internxtApiOn(drive(FakeSharings(raw = { route, _ -> if (route == "folders") body else null })))

            assertEquals(listOf(SharedRef("fo-1", true, null)), refs(service.listSharedByMe()))
            service.close()
        }

    // ---- an answer that does not list anything is not an empty page -----------------------------------------------

    // The failure of an unreadable page names the route and the page and one of: the array that is missing, or the decoder's
    // class. It holds nothing of the body (every body below carries canaries and recognisable text), has no cause (the decoder's
    // exception quotes the body) and is not retried, since the same body would come again.
    private fun assertUnreadablePage(
        e: InternxtUnreadableResponse,
        route: String,
        reason: String?,
        bodyText: List<String>,
    ) {
        val prefix = "The sharings $route listing (page 0) cannot be read: "
        val message = e.message.orEmpty()
        if (reason != null) assertEquals(prefix + reason, message) else assertTrue(message.startsWith(prefix), message)
        assertEquals("srv-sharings", e.requestId)
        assertNull(e.cause, "the decoder's exception quotes the body")
        assertEquals(emptyList(), Canary.foundIn(e.stackTraceToString()))
        for (text in bodyText) assertFalse(text in e.stackTraceToString(), "the failure quotes the body: $text")
    }

    @Test
    fun `a folders answer without a folders array is unreadable, not an empty page`() =
        runTest {
            val body = """{"files":[{"uuid":"fi-1","plainName":"Quoted.txt","sharedWithMe":false,"encryptionKey":"${Canary.ENCRYPTION_KEY}"}],"token":"${Canary.TOKEN}"}"""
            val sharings = FakeSharings(raw = { _, _ -> body })
            val service = internxtApiOn(drive(sharings))

            val e = assertFailsWith<InternxtUnreadableResponse> { service.listSharedByMe() }

            assertUnreadablePage(e, "folders", "the folders array is missing or null", listOf("Quoted.txt", "fi-1"))
            assertEquals(listOf("folders page=0 perPage=50"), sharings.requests, "not retried, and the files route is never asked")
            service.close()
        }

    @Test
    fun `a folders answer holding only an empty files array is unreadable, not an empty page`() =
        runTest {
            val service = internxtApiOn(drive(FakeSharings(raw = { _, _ -> """{"files":[]}""" })))

            val e = assertFailsWith<InternxtUnreadableResponse> { service.listSharedByMe() }

            assertUnreadablePage(e, "folders", "the folders array is missing or null", emptyList())
            service.close()
        }

    @Test
    fun `a files answer without a files array is unreadable, not an empty page`() =
        runTest {
            val bodies =
                listOf(
                    """{"folders":[]}""",
                    "{}",
                    """{"folders":[{"uuid":"fo-9","plainName":"Quoted folder","sharedWithMe":false,"encryptionKey":"${Canary.ENCRYPTION_KEY}"}],"token":"${Canary.TOKEN}"}""",
                )
            for (body in bodies) {
                val sharings = FakeSharings(folders = FakeSharings.pages(folders(2)), raw = { route, _ -> if (route == "files") body else null })
                val service = internxtApiOn(drive(sharings))

                val e = assertFailsWith<InternxtUnreadableResponse>(body.take(40)) { service.listSharedByMe() }

                assertUnreadablePage(e, "files", "the files array is missing or null", listOf("Quoted folder", "fo-9"))
                assertEquals(listOf("folders page=0 perPage=50", "files page=0 perPage=50"), sharings.requests, "not retried")
                service.close()
            }
        }

    @Test
    fun `a null or a non-array value in place of the requested array is unreadable`() =
        runTest {
            val missing = "the %s array is missing or null"
            // A null is the missing array; any other value fails the decoder, which names only its class (reason null).
            val folderBodies =
                listOf(
                    """{"folders":null,"files":[]}""" to missing.format("folders"),
                    """{"folders":"Quoted-text","files":[]}""" to null,
                    """{"folders":{"Quoted-object":[]},"files":[]}""" to null,
                    """{"folders":7,"files":[]}""" to null,
                    """{"folders":true,"files":[]}""" to null,
                )
            for ((body, reason) in folderBodies) {
                val sharings = FakeSharings(raw = { _, _ -> body })
                val service = internxtApiOn(drive(sharings))

                val e = assertFailsWith<InternxtUnreadableResponse>(body) { service.listSharedByMe() }

                assertUnreadablePage(e, "folders", reason, listOf("Quoted-text", "Quoted-object"))
                assertEquals(1, sharings.requests.size, body)
                service.close()
            }
            val fileBodies =
                listOf(
                    """{"folders":[],"files":null}""" to missing.format("files"),
                    """{"folders":[],"files":"Quoted-text"}""" to null,
                    """{"folders":[],"files":{"Quoted-object":[]}}""" to null,
                )
            for ((body, reason) in fileBodies) {
                val service = internxtApiOn(drive(FakeSharings(raw = { route, _ -> if (route == "files") body else null })))

                val e = assertFailsWith<InternxtUnreadableResponse>(body) { service.listSharedByMe() }

                assertUnreadablePage(e, "files", reason, listOf("Quoted-text", "Quoted-object"))
                service.close()
            }
        }

    @Test
    fun `an error object without arrays is unreadable on either route, and quotes none of the error`() =
        runTest {
            for (route in listOf("folders", "files")) {
                val sharings = FakeSharings(raw = { r, _ -> if (r == route) fakeHostileErrorBody(200) else null })
                val service = internxtApiOn(drive(sharings))

                val e = assertFailsWith<InternxtUnreadableResponse>(route) { service.listSharedByMe() }

                assertUnreadablePage(e, route, "the $route array is missing or null", listOf("failed for", "statusCode", "retry_after"))
                service.close()
            }
        }

    @Test
    fun `an empty array of the requested route is a valid empty page, and the other route's array may be absent`() =
        runTest {
            val sharings = FakeSharings(raw = { route, _ -> if (route == "folders") """{"folders":[]}""" else """{"files":[]}""" })
            val service = internxtApiOn(drive(sharings))

            val listed = assertIs<SharedByMe.Listed>(service.listSharedByMe())

            assertEquals(emptyList(), listed.refs)
            assertFalse(listed.truncated)
            assertEquals(listOf("folders page=0 perPage=50", "files page=0 perPage=50"), sharings.requests)
            service.close()
        }

    @Test
    fun `items in the requested array are listed when the other route's array is absent`() =
        runTest {
            val folderBody = """{"folders":[{"uuid":"fo-1","plainName":"Projects","sharedWithMe":false}]}"""
            val fileBody = """{"files":[{"uuid":"fi-1","plainName":"report.txt","sharedWithMe":false}]}"""
            val service = internxtApiOn(drive(FakeSharings(raw = { route, _ -> if (route == "folders") folderBody else fileBody })))

            assertEquals(listOf(SharedRef("fo-1", true, "Projects"), SharedRef("fi-1", false, "report.txt")), refs(service.listSharedByMe()))
            service.close()
        }

    @Test
    fun `an unreadable page after full pages ends the call with the exception and no partial list`() =
        runTest {
            val sharings =
                FakeSharings(
                    folders = FakeSharings.pages(folders(60)),
                    raw = { route, page -> if (route == "folders" && page == 1) """{"files":[]}""" else null },
                )
            val service = internxtApiOn(drive(sharings))

            val e = assertFailsWith<InternxtUnreadableResponse> { service.listSharedByMe() }

            assertEquals("The sharings folders listing (page 1) cannot be read: the folders array is missing or null", e.message)
            assertEquals(listOf("folders page=0 perPage=50", "folders page=1 perPage=50"), sharings.requests, "the files route is not asked after it")
            service.close()
        }

    // ---- what else follows from the route --------------------------------------------------------------------------

    @Test
    fun `the sharings routes are named in the endpoint classes the request metrics report`() {
        assertEquals("GET /drive/sharings/folders", InternxtEndpoints.classifier.classify("GET", "gateway.internxt.com", "/drive/sharings/folders"))
        assertEquals("GET /drive/sharings/files", InternxtEndpoints.classifier.classify("GET", "gateway.internxt.com", "/drive/sharings/files"))
    }

    @Test
    fun `the error text of any other route still quotes the body`() =
        runTest {
            val engine = MockEngine { respond("""{"message":"limit must not be less than 1"}""", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/json")) }
            val service =
                InternxtApiService(
                    InternxtConfig(credentialProtector = null),
                    credentialsProvider = { _ -> fakeCredentials() },
                    driveBudget = HttpRetryBudget(maxConcurrency = 2, minSpacingMs = 0, stormSpacingMs = 0),
                    httpClient = HttpClient(engine),
                )

            val e = assertFailsWith<InternxtApiException> { service.listFiles() }

            assertEquals("""API error: 400 Bad Request - {"message":"limit must not be less than 1"}""", e.message)
            service.close()
        }
}
