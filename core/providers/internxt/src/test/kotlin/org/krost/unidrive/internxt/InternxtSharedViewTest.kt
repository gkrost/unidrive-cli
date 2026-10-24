package org.krost.unidrive.internxt

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.ViewKind
import org.krost.unidrive.ViewList
import org.krost.unidrive.ViewRef
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The provider's side of the Shared view: `listView(SHARED_BY_ME, limit)` adapts the listing of what the account shares
 * ([InternxtApiService.listSharedByMe], covered by InternxtSharedByMeTest) to the engine's view SPI. A reference is the
 * uuid of a real file or folder, and whether it is a folder, and nothing else (the engine shows a folder as an alias of the real
 * one); what is shared with the account is left out; a gateway without the routes is an
 * unsupported view, not an error; any other failure throws. The fixtures carry a canary in every secret of the answers.
 */
class InternxtSharedViewTest {
    private val shared = ViewKind.SHARED_BY_ME

    private fun providerOn(sharings: FakeSharings) = internxtProviderOn(FakeDrive(emptyList(), emptyList(), sharings = sharings).engine)

    private fun files(
        count: Int,
        from: Int = 0,
    ) = (from until from + count).map { FakeShare("fi-$it", "file$it.txt", isFolder = false) }

    private fun folders(count: Int) = (0 until count).map { FakeShare("fo-$it", "Folder $it") }

    private fun refs(result: CapabilityResult<List<ViewRef>>) = assertIs<CapabilityResult.Success<List<ViewRef>>>(result).value

    // ---- what is listed --------------------------------------------------------------------------------------------

    @Test
    fun `the provider declares the views it has and no other`() {
        val capabilities = providerOn(FakeSharings()).capabilities()

        assertTrue(Capability.View(shared) in capabilities)
        assertEquals(setOf(shared, ViewKind.RECENTS, ViewKind.FAVORITES), capabilities.filterIsInstance<Capability.View>().map { it.kind }.toSet())
    }

    @Test
    fun `the files the account shares are the references, by uuid, once each and in the order the server served them`() =
        runTest {
            val sharings =
                FakeSharings(
                    files =
                        FakeSharings.pages(
                            listOf(
                                FakeShare("fi-b", "b.txt", isFolder = false),
                                FakeShare("fi-a", "a.txt", isFolder = false, publicLink = true),
                                FakeShare("fi-b", "b.txt", isFolder = false),
                            ),
                        ),
                )

            val listed = refs(providerOn(sharings).listView(shared, 100))

            assertEquals(listOf(ViewRef("fi-b", false), ViewRef("fi-a", false)), listed)
        }

    @Test
    fun `the folders it shares are references, first, and what is shared with it is not`() =
        runTest {
            val sharings =
                FakeSharings(
                    folders = FakeSharings.pages(folders(2) + FakeShare("fo-theirs", "Theirs", sharedWithMe = true)),
                    files =
                        FakeSharings.pages(
                            listOf(
                                FakeShare("fi-1", "mine.txt", isFolder = false),
                                FakeShare("fi-theirs", "theirs.txt", isFolder = false, sharedWithMe = true),
                                FakeShare("fi-trashed", "gone.txt", isFolder = false, status = "TRASHED"),
                            ),
                        ),
                )

            val listed = refs(providerOn(sharings).listView(shared, 100))

            assertEquals(listOf(ViewRef("fo-0", true), ViewRef("fo-1", true), ViewRef("fi-1", false)), listed)
        }

    @Test
    fun `a run of folders past the cap cuts the list and says so, the files are still read`() =
        runTest {
            val sharings =
                FakeSharings(
                    folders = FakeSharings.pages(folders(InternxtApiService.MAX_SHARED_REFS + 1)),
                    files = FakeSharings.pages(files(3)),
                )

            val answer = providerOn(sharings).listView(shared, 10_000)

            assertTrue(((answer as CapabilityResult.Success).value as ViewList).truncated)
            assertEquals(InternxtApiService.MAX_SHARED_REFS, refs(answer).size.coerceAtMost(InternxtApiService.MAX_SHARED_REFS))
        }

    @Test
    fun `the limit counts folders and files together`() =
        runTest {
            val sharings = FakeSharings(folders = FakeSharings.pages(folders(2)), files = FakeSharings.pages(files(3)))

            val answer = providerOn(sharings).listView(shared, 3)

            assertEquals(listOf("fo-0", "fo-1", "fi-0"), refs(answer).map { it.id })
            assertTrue((answer as CapabilityResult.Success).value.let { (it as ViewList).truncated })
        }

    @Test
    fun `an account that shares nothing has an empty view, which is a view`() =
        runTest {
            assertEquals(emptyList(), refs(providerOn(FakeSharings()).listView(shared, 100)))
        }

    @Test
    fun `a list of more than a page is read whole`() =
        runTest {
            val sharings = FakeSharings(files = FakeSharings.pages(files(107)))

            val listed = refs(providerOn(sharings).listView(shared, 1_000))

            assertEquals((0 until 107).map { ViewRef("fi-$it", false) }, listed)
        }

    @Test
    fun `a reference holds no name and no secret, and prints none`() =
        runTest {
            val sharings = FakeSharings(files = FakeSharings.pages(listOf(FakeShare("fi-link", "Tax return 2026.pdf", isFolder = false, publicLink = true))))

            val listed = refs(providerOn(sharings).listView(shared, 100))

            val printed = listed.joinToString("\n") { it.toString() }
            assertEquals(emptyList(), Canary.foundIn(printed))
            assertFalse("Tax return" in printed, "the name is the engine's to read from its own row")
            assertEquals(setOf("id", "isFolder"), ViewRef::class.java.declaredFields.filter { !it.isSynthetic }.map { it.name }.toSet())
        }

    // ---- the limit and the cap -------------------------------------------------------------------------------------

    @Test
    fun `the list is cut at the limit the engine asked for, from the front`() =
        runTest {
            val sharings = FakeSharings(files = FakeSharings.pages(files(5)))

            val listed = refs(providerOn(sharings).listView(shared, 3))

            assertEquals(listOf("fi-0", "fi-1", "fi-2"), listed.map { it.id })
        }

    @Test
    fun `more references than the module's cap give the first ones, and the cut is said once, in counts`() =
        runTest {
            val sharings = FakeSharings(files = FakeSharings.pages(files(InternxtApiService.MAX_SHARED_REFS + 1)))
            val provider = providerOn(sharings)

            val lines =
                logLines(Level.DEBUG) {
                    val first = refs(provider.listView(shared, 10_000))
                    assertEquals(InternxtApiService.MAX_SHARED_REFS, first.size)
                    assertEquals("fi-0", first.first().id)
                    assertEquals(InternxtApiService.MAX_SHARED_REFS, refs(provider.listView(shared, 10_000)).size)
                }

            val cuts = lines.filter { it.startsWith("WARN") && "cut" in it }
            assertEquals(1, cuts.size, "a cut that lasts is said once: $lines")
            assertTrue("5000" in cuts.single(), cuts.single())
            assertFalse(lines.any { "file0.txt" in it || "fi-0" in it }, "no name and no id in the log")
        }

    @Test
    fun `a cut that is over and comes back is said again`() =
        runTest {
            var count = InternxtApiService.MAX_SHARED_REFS + 1
            val sharings = FakeSharings(files = { page -> files(count).drop(page * 50).take(50) })
            val provider = providerOn(sharings)

            val lines =
                logLines(Level.WARN) {
                    provider.listView(shared, 10_000)
                    count = 10
                    provider.listView(shared, 10_000)
                    count = InternxtApiService.MAX_SHARED_REFS + 1
                    provider.listView(shared, 10_000)
                }

            assertEquals(2, lines.count { "cut" in it }, lines.toString())
        }

    @Test
    fun `a limit below the number of files is a cut too`() =
        runTest {
            val provider = providerOn(FakeSharings(files = FakeSharings.pages(files(5))))

            val lines = logLines(Level.WARN) { provider.listView(shared, 2) }

            assertEquals(1, lines.count { "cut" in it }, lines.toString())
        }

    @Test
    fun `a cut list tells the engine it was cut, at the limit and at the cap, and a whole one does not`() =
        runTest {
            fun truncated(answer: CapabilityResult<List<ViewRef>>) = ((answer as CapabilityResult.Success).value as? ViewList)?.truncated == true

            assertTrue(truncated(providerOn(FakeSharings(files = FakeSharings.pages(files(5)))).listView(shared, 2)), "cut at the limit")
            val overCap = FakeSharings(files = FakeSharings.pages(files(InternxtApiService.MAX_SHARED_REFS + 1)))
            assertTrue(truncated(providerOn(overCap).listView(shared, 10_000)), "cut at the cap")
            assertFalse(truncated(providerOn(FakeSharings(files = FakeSharings.pages(files(5)))).listView(shared, 5)), "a whole list")
        }

    // ---- what is not a list ----------------------------------------------------------------------------------------

    @Test
    fun `the other kinds are not this provider's and cost no request`() =
        runTest {
            val sharings = FakeSharings(files = FakeSharings.pages(files(3)))
            val provider = providerOn(sharings)

            for (kind in ViewKind.entries - shared - ViewKind.RECENTS - ViewKind.FAVORITES) {
                val answer = provider.listView(kind, 10)

                assertEquals(Capability.View(kind), assertIs<CapabilityResult.Unsupported>(answer).capability, "$kind")
            }
            assertEquals(emptyList(), sharings.requests.toList())
        }

    @Test
    fun `a gateway without the routes is an unsupported view, not an error`() =
        runTest {
            for ((route, status) in listOf("folders" to 404, "folders" to 405, "files" to 404, "files" to 405)) {
                val sharings = FakeSharings(failure = { r, page -> if (r == route && page == 0) status else null })

                val answer = providerOn(sharings).listView(shared, 10)

                assertEquals(Capability.View(shared), assertIs<CapabilityResult.Unsupported>(answer).capability, "$route $status")
                assertEquals(emptyList(), Canary.foundIn(answer.toString()), "$route $status")
            }
        }

    @Test
    fun `any other failure throws, and says nothing the answer held`() =
        runTest {
            for (status in listOf(400, 401, 503)) {
                val sharings = FakeSharings(failure = { _, _ -> status })

                val failure = assertFailsWith<Exception>("status $status") { providerOn(sharings).listView(shared, 10) }

                assertEquals(emptyList(), Canary.foundIn(generateSequence<Throwable>(failure) { it.cause }.joinToString("\n") { it.toString() }), "status $status")
            }
        }

    // ---- the log ---------------------------------------------------------------------------------------------------

    @Test
    fun `a whole list-and-cut cycle leaves no canary in the log, at trace`() =
        runTest {
            val rows = (0 until InternxtApiService.MAX_SHARED_REFS + 1).map { FakeShare("fi-$it", "file$it.txt", isFolder = false, publicLink = it % 2 == 0) }
            val provider = providerOn(FakeSharings(folders = FakeSharings.pages(listOf(FakeShare("fo-link", "Public", publicLink = true))), files = FakeSharings.pages(rows)))

            val lines = logLines(Level.TRACE) { provider.listView(shared, 10_000) }

            assertTrue(lines.isNotEmpty(), "the capture saw the run")
            assertEquals(emptyList(), lines.filter { Canary.foundIn(it).isNotEmpty() })
        }

    // Every log event of the process while [block] runs, as text.
    private suspend fun logLines(
        level: Level,
        block: suspend () -> Unit,
    ): List<String> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val before = root.level
        root.level = level
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
            root.level = before
        }
        return appender.list.map { event ->
            buildString {
                append(event.level).append(' ').append(event.loggerName).append(' ').append(event.formattedMessage)
                event.argumentArray?.forEach { append(' ').append(it) }
                event.mdcPropertyMap?.forEach { (key, value) -> append(' ').append(key).append('=').append(value) }
            }
        }
    }
}
